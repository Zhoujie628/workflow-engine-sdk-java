/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.examples.gateway;

import com.eastcom.apollo.orders.internal.shaded.v11x.com.eastcom.apollo.orders.client.core.common.ServerInfo;
import com.eastcom.apollo.orders.internal.shaded.v11x.com.eastcom.apollo.orders.client.core.config.ConfigOption;
import com.eastcom.apollo.orders.internal.shaded.v11x.com.eastcom.apollo.orders.client.httpsession.OrderHttpSessionClient;
import com.eastcom.apollo.orders.internal.shaded.v11x.com.eastcom.apollo.orders.commons.metadata.httpsession.OrderHttpSessionStrRequest;
import com.eastcom.apollo.orders.internal.shaded.v11x.com.eastcom.apollo.orders.commons.metadata.httpsession.OrderHttpSessionStrResponse;

import com.google.protobuf.util.JsonFormat;

import dev.openan.workflow.engine.client.A2AJavaClientRuntime;
import dev.openan.workflow.engine.client.ConversationScopedA2AJavaClientRuntime;

import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.client.transport.spi.interceptors.ClientCallContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.a2aproject.sdk.grpc.SendMessageRequest;
import org.a2aproject.sdk.grpc.utils.ProtoUtils;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.MessageSendParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * A2A client runtime backed by the Eastcom Order SDK.
 *
 * <p>The adapter is deliberately split into routing, conversation-scoped session management and
 * response parsing. An authenticated Order session is reused for all A2A requests in the same
 * {@code contextId + NE} conversation, including Negotiation-T follow-ups, and access to that
 * mutable vendor client is serialized. Agent-to-NE routing is supplied by {@link
 * AgentGatewayRouteResolver}. The selected endpoint and response consumption mode follow the
 * streaming capability declared by the AgentCard.
 */
public final class OrderGatewayClientRuntime
        implements A2AJavaClientRuntime, ConversationScopedA2AJavaClientRuntime {
    private static final Logger log = LoggerFactory.getLogger(OrderGatewayClientRuntime.class);
    private static final Logger protocolLog = LoggerFactory.getLogger("PROTOCOL");

    private static final ObjectMapper prettyMapper =
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private static String prettyJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return raw;
        }
        try {
            Object parsed = prettyMapper.readValue(raw, Object.class);
            return prettyMapper.writeValueAsString(parsed);
        } catch (Exception e) {
            return raw;
        }
    }
    private static final String INCLUDE_SENSITIVE_HEADERS =
            "WORKFLOW_ENGINE_PROTOCOL_INCLUDE_SENSITIVE_HEADERS";
    private static final String REQUEST_CHANNEL = "request";
    private static final String NOTIFICATION_CHANNEL = "notification";

    private final OrderConfig config;
    private final AgentGatewayRouteResolver routeResolver;
    private final OrderSessionFactory sessionFactory;
    private final ConversationSessionManager sessionManager;
    private final GatewayA2AResponseParser responseParser;

    public OrderGatewayClientRuntime(OrderConfig config) {
        this(
                config,
                new ConfiguredAgentGatewayRouteResolver(config.agentNeRoutes, config.defaultNe),
                new DefaultOrderSessionFactory(config),
                new GatewayA2AResponseParser());
    }

    OrderGatewayClientRuntime(
            OrderConfig config,
            AgentGatewayRouteResolver routeResolver,
            OrderSessionFactory sessionFactory,
            GatewayA2AResponseParser responseParser) {
        this.config = Objects.requireNonNull(config, "config");
        this.routeResolver = Objects.requireNonNull(routeResolver, "routeResolver");
        this.sessionFactory = Objects.requireNonNull(sessionFactory, "sessionFactory");
        this.sessionManager = new ConversationSessionManager(this.sessionFactory);
        this.responseParser = Objects.requireNonNull(responseParser, "responseParser");
        log.info(
                "[OrderGateway] INITIALIZED host={}:{}, routes={}, defaultNe={}",
                config.host,
                config.port,
                config.agentNeRoutes.keySet(),
                config.defaultNe);
    }

    @Override
    public Iterable<ClientEvent> sendMessage(
            AgentCard agentCard,
            MessageSendParams params,
            ClientCallContext callContext,
            Consumer<ClientEvent> eventSink,
            Consumer<String> logSink) {
        String requestId = java.util.UUID.randomUUID().toString();
        long started = System.nanoTime();
        AgentGatewayRoute route = routeResolver.resolve(agentCard);
        boolean streaming = supportsStreaming(agentCard);
        String uriPath = route.messagePath(params.tenant(), streaming);
        log.info(
                "[OrderGateway] SEND_START requestId={}, agent={}, ne={}, mode={}, path={}, "
                        + "https={}, timeoutMs={}",
                requestId,
                agentCard.name(),
                route.ne(),
                streaming ? "streaming" : "blocking",
                uriPath,
                route.https(),
                config.timeoutMillis);
        if (logSink != null) {
            logSink.accept("[OrderSDK] Forwarding " + agentCard.name() + " via NE " + route.ne());
        }

        String contextId = params.message() != null ? params.message().contextId() : null;
        String channel = sessionChannel(params);
        ConversationSessionHandle sessionHandle = null;
        try {
            long sessionStarted = System.nanoTime();
            sessionHandle = sessionManager.acquire(contextId, route, channel);
            OrderSession session = sessionHandle.session();
            log.info(
                    "[OrderGateway] SESSION_{} requestId={}, contextId={}, ne={}, channel={}, sessionType={}, "
                            + "host={}:{}, elapsedMs={}",
                    sessionHandle.reused() ? "REUSE" : "OPEN",
                    requestId,
                    contextId,
                    route.ne(),
                    channel,
                    session.sessionType(),
                    config.host,
                    config.port,
                    elapsedMillis(sessionStarted));
            String body = serialize(params);
            Map<String, String> headers = extractHeaders(callContext, streaming);
            OrderHttpSessionStrRequest request =
                    OrderHttpSessionStrRequest.newBuilder()
                            .setUriPath(uriPath)
                            .setMethod("POST")
                            .putAllHeaders(headers)
                            .setBody(body)
                            .build();
            logProtocolRequest(requestId, agentCard.name(), route.ne(), request);
            var events =
                    streaming
                            ? executeStreaming(
                                    session, request, eventSink, requestId, agentCard.name())
                            : executeBlocking(
                                    session, request, eventSink, requestId, agentCard.name());
            sessionHandle.release();
            sessionHandle = null;
            log.info(
                    "[OrderGateway] SEND_DONE requestId={}, agent={}, ne={}, mode={}, events={}, elapsedMs={}",
                    requestId,
                    agentCard.name(),
                    route.ne(),
                    streaming ? "streaming" : "blocking",
                    events.size(),
                    elapsedMillis(started));
            return events;
        } catch (Exception e) {
            if (sessionHandle != null) {
                sessionHandle.invalidate();
            }
            if (NOTIFICATION_CHANNEL.equals(channel) && isCausedByInterruption(e)) {
                Thread.currentThread().interrupt();
                log.info(
                        "[OrderGateway] SEND_CANCELLED requestId={}, agent={}, ne={}, channel={}, "
                                + "elapsedMs={}, reason=thread_interrupted",
                        requestId,
                        agentCard.name(),
                        route.ne(),
                        channel,
                        elapsedMillis(started));
            } else {
                log.error(
                        "[OrderGateway] SEND_FAILED requestId={}, agent={}, ne={}, elapsedMs={}, "
                                + "message={}",
                        requestId,
                        agentCard.name(),
                        route.ne(),
                        elapsedMillis(started),
                        e.getMessage(),
                        e);
            }
            throw new IllegalStateException(
                    "Eastcom gateway send failed for " + agentCard.name() + ": " + e.getMessage(), e);
        }
    }

    @Override
    public void closeConversation(AgentCard agentCard, String contextId) {
        AgentGatewayRoute route = routeResolver.resolve(agentCard);
        if (sessionManager.closeConversation(contextId, route.ne(), REQUEST_CHANNEL)) {
            log.info(
                    "[OrderGateway] SESSION_CLOSE contextId={}, ne={}, reason=conversation_complete",
                    contextId,
                    route.ne());
        }
    }

    @Override
    public void close() {
        sessionManager.close();
        sessionFactory.close();
        log.info("[OrderGateway] CLOSED");
    }

    private static String serialize(MessageSendParams params) throws Exception {
        SendMessageRequest request = ProtoUtils.ToProto.sendMessageRequest(params);
        return JsonFormat.printer().print(request);
    }

    private List<ClientEvent> executeBlocking(
            OrderSession session,
            OrderHttpSessionStrRequest request,
            Consumer<ClientEvent> eventSink,
            String requestId,
            String agentName) {
        OrderHttpSessionStrResponse response = session.execute(request, config.timeoutMillis);
        logProtocolResponse(requestId, agentName, 1, response);
        validateResponse(response);
        List<ClientEvent> events =
                responseParser.parseNonStreaming(response.getBody(), eventSink);
        if (events.isEmpty()) {
            throw new IllegalStateException("Gateway message:send response has no A2A payload");
        }
        return events;
    }

    private List<ClientEvent> executeStreaming(
            OrderSession session,
            OrderHttpSessionStrRequest request,
            Consumer<ClientEvent> eventSink,
            String requestId,
            String agentName) {
        GatewayA2AResponseParser.StreamingSession parserSession =
                responseParser.newStreamingSession(eventSink);
        AtomicInteger frameCount = new AtomicInteger();
        session.executeStreaming(
                request,
                config.timeoutMillis,
                response -> {
                    int frame = frameCount.incrementAndGet();
                    logProtocolResponse(requestId, agentName, frame, response);
                    validateResponse(response);
                    log.debug(
                            "[OrderGateway] STREAM_FRAME requestId={}, agent={}, frame={}, bodyChars={}",
                            requestId,
                            agentName,
                            frame,
                            response.getBody().length());
                    boolean terminal = parserSession.accept(response.getBody());
                    if (terminal) {
                        log.info(
                                "[OrderGateway] STREAM_TERMINAL requestId={}, agent={}, frame={}",
                                requestId,
                                agentName,
                                frame);
                    }
                    return terminal;
                });
        List<ClientEvent> events = parserSession.complete();
        if (events.isEmpty()) {
            throw new IllegalStateException("Gateway message:stream closed without an A2A event");
        }
        log.info(
                "[OrderGateway] STREAM_CLOSED requestId={}, agent={}, frames={}, events={}",
                requestId,
                agentName,
                frameCount.get(),
                events.size());
        return events;
    }

    private static void logProtocolRequest(
            String requestId,
            String agentName,
            String ne,
            OrderHttpSessionStrRequest request) {
        if (!protocolLog.isDebugEnabled()) {
            return;
        }
        protocolLog.debug(
                ">>> [OrderSDK] REQUEST requestId={}, agent={}, ne={}\n"
                        + "=== Method/Path ===\n{} {}\n"
                        + "=== Headers ===\n{}\n"
                        + "=== Body ===\n{}",
                requestId,
                agentName,
                ne,
                request.getMethod(),
                request.getUriPath(),
                formatProtocolHeaders(request.getHeadersMap()),
                prettyJson(request.getBody()));
    }

    private static void logProtocolResponse(
            String requestId,
            String agentName,
            int frame,
            OrderHttpSessionStrResponse response) {
        if (!protocolLog.isDebugEnabled()) {
            return;
        }
        protocolLog.debug(
                "<<< [OrderSDK] RESPONSE requestId={}, agent={}, frame={}\n"
                        + "=== Status ===\n{}\n"
                        + "=== Headers ===\n{}\n"
                        + "=== Body ===\n{}",
                requestId,
                agentName,
                frame,
                response.getStatus(),
                formatProtocolHeaders(response.getHeadersMap()),
                prettyJson(response.getBody()));
    }

    private static String formatProtocolHeaders(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return "(none)";
        }
        boolean includeSensitive = includeSensitiveHeaders();
        StringBuilder formatted = new StringBuilder();
        headers.forEach(
                (name, value) ->
                        formatted
                                .append(name)
                                .append(": ")
                                .append(
                                        !includeSensitive && isSensitiveHeader(name)
                                                ? "***REDACTED***"
                                                : value)
                                .append('\n'));
        return formatted.toString().trim();
    }

    private static boolean includeSensitiveHeaders() {
        String configured = System.getProperty(INCLUDE_SENSITIVE_HEADERS);
        if (configured == null || configured.isBlank()) {
            configured = System.getenv(INCLUDE_SENSITIVE_HEADERS);
        }
        return Boolean.parseBoolean(configured);
    }

    private static boolean isSensitiveHeader(String name) {
        if (name == null) {
            return false;
        }
        String normalized = name.toLowerCase(Locale.ROOT);
        return normalized.contains("authorization")
                || normalized.contains("token")
                || normalized.contains("secret")
                || normalized.contains("api-key")
                || normalized.contains("apikey")
                || normalized.contains("cookie");
    }

    private static void validateResponse(OrderHttpSessionStrResponse response) {
        Objects.requireNonNull(response, "Gateway returned a null response");
        int status = response.getStatus();
        if (status != 0 && (status < 200 || status >= 300)) {
            throw new IllegalStateException(
                    "Gateway HTTP " + status + ": " + abbreviate(response.getBody(), 500));
        }
    }

    private static boolean supportsStreaming(AgentCard agentCard) {
        return agentCard.capabilities() != null && agentCard.capabilities().streaming();
    }

    private static String sessionChannel(MessageSendParams params) {
        if (params.message() == null || params.message().metadata() == null) {
            return REQUEST_CHANNEL;
        }
        return params.message().metadata().keySet().stream()
                        .anyMatch(key -> key != null && key.contains("Notification-T"))
                ? NOTIFICATION_CHANNEL
                : REQUEST_CHANNEL;
    }

    private static Map<String, String> extractHeaders(
            ClientCallContext callContext, boolean streaming) {
        Map<String, String> headers = new HashMap<>();
        if (callContext != null && callContext.getHeaders() != null) {
            headers.putAll(callContext.getHeaders());
        }
        headers.putIfAbsent("Content-Type", "application/json");
        headers.putIfAbsent("Accept", streaming ? "text/event-stream" : "application/json");
        headers.putIfAbsent("A2A-Version", "1.0");
        return headers;
    }

    private static String abbreviate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.substring(0, Math.min(maxLength, value.length()));
    }

    private static long elapsedMillis(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }

    private static boolean isCausedByInterruption(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof InterruptedException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    interface OrderSessionFactory extends AutoCloseable {
        OrderSession open(AgentGatewayRoute route);

        @Override
        default void close() {}
    }

    interface OrderSession extends AutoCloseable {
        OrderHttpSessionStrResponse execute(OrderHttpSessionStrRequest request, int timeoutMillis);

        void executeStreaming(
                OrderHttpSessionStrRequest request,
                int timeoutMillis,
                Predicate<OrderHttpSessionStrResponse> responseSink);

        default String sessionType() {
            String simpleName = getClass().getSimpleName();
            return simpleName.isEmpty() ? getClass().getName() : simpleName;
        }

        @Override
        void close();
    }

    /** Owns the mutable vendor sessions and their logical conversation lifecycle. */
    private static final class ConversationSessionManager implements AutoCloseable {
        private final OrderSessionFactory sessionFactory;
        private final ConcurrentMap<ConversationSessionKey, ManagedSession> sessions =
                new ConcurrentHashMap<>();
        private final AtomicBoolean closed = new AtomicBoolean();

        private ConversationSessionManager(OrderSessionFactory sessionFactory) {
            this.sessionFactory = sessionFactory;
        }

        private ConversationSessionHandle acquire(
                String contextId, AgentGatewayRoute route, String channel) {
            if (contextId == null || contextId.isBlank()) {
                ensureOpen();
                ManagedSession entry = new ManagedSession();
                entry.lock.lock();
                try {
                    entry.session = sessionFactory.open(route);
                    return new ConversationSessionHandle(this, null, entry, false);
                } catch (RuntimeException e) {
                    entry.lock.unlock();
                    throw e;
                }
            }

            ConversationSessionKey key =
                    new ConversationSessionKey(contextId, route.ne(), channel);
            while (true) {
                ensureOpen();
                ManagedSession entry = sessions.computeIfAbsent(key, ignored -> new ManagedSession());
                entry.lock.lock();
                if (sessions.get(key) != entry) {
                    entry.lock.unlock();
                    continue;
                }
                if (closed.get()) {
                    sessions.remove(key, entry);
                    entry.lock.unlock();
                    throw new IllegalStateException("Order gateway runtime is closed");
                }
                boolean reused = entry.session != null;
                try {
                    if (!reused) {
                        entry.session = sessionFactory.open(route);
                    }
                    return new ConversationSessionHandle(this, key, entry, reused);
                } catch (RuntimeException e) {
                    sessions.remove(key, entry);
                    closeSession(entry);
                    entry.lock.unlock();
                    throw e;
                }
            }
        }

        private void release(
                ConversationSessionKey key, ManagedSession entry, boolean invalidate) {
            try {
                if (key == null || invalidate) {
                    if (key != null) {
                        sessions.remove(key, entry);
                    }
                    closeSession(entry);
                }
            } finally {
                entry.lock.unlock();
            }
        }

        private boolean closeConversation(String contextId, String ne, String channel) {
            if (contextId == null || contextId.isBlank()) {
                return false;
            }
            ConversationSessionKey key = new ConversationSessionKey(contextId, ne, channel);
            ManagedSession entry = sessions.get(key);
            if (entry == null) {
                return false;
            }
            entry.lock.lock();
            try {
                if (!sessions.remove(key, entry)) {
                    return false;
                }
                closeSession(entry);
                return true;
            } finally {
                entry.lock.unlock();
            }
        }

        private void ensureOpen() {
            if (closed.get()) {
                throw new IllegalStateException("Order gateway runtime is closed");
            }
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            while (!sessions.isEmpty()) {
                ConversationSessionKey key = sessions.keySet().iterator().next();
                closeConversation(key.contextId(), key.ne(), key.channel());
            }
        }

        private static void closeSession(ManagedSession entry) {
            OrderSession session = entry.session;
            entry.session = null;
            if (session == null) {
                return;
            }
            try {
                session.close();
            } catch (RuntimeException e) {
                log.warn("[OrderGateway] Session close failed: {}", e.getMessage(), e);
            }
        }
    }

    private record ConversationSessionKey(String contextId, String ne, String channel) {}

    private static final class ManagedSession {
        private final ReentrantLock lock = new ReentrantLock();
        private OrderSession session;
    }

    private static final class ConversationSessionHandle {
        private final ConversationSessionManager manager;
        private final ConversationSessionKey key;
        private final ManagedSession entry;
        private final boolean reused;
        private final AtomicBoolean released = new AtomicBoolean();

        private ConversationSessionHandle(
                ConversationSessionManager manager,
                ConversationSessionKey key,
                ManagedSession entry,
                boolean reused) {
            this.manager = manager;
            this.key = key;
            this.entry = entry;
            this.reused = reused;
        }

        private OrderSession session() {
            return entry.session;
        }

        private boolean reused() {
            return reused;
        }

        private void release() {
            finish(false);
        }

        private void invalidate() {
            finish(true);
        }

        private void finish(boolean invalidate) {
            if (released.compareAndSet(false, true)) {
                manager.release(key, entry, invalidate);
            }
        }
    }

    private static final class DefaultOrderSessionFactory implements OrderSessionFactory {
        private final OrderConfig config;

        private DefaultOrderSessionFactory(OrderConfig config) {
            this.config = config;
        }

        @Override
        public OrderSession open(AgentGatewayRoute route) {
            StreamingOrderHttpSessionClient client = new StreamingOrderHttpSessionClient();
            String stage = "configure";
            long started = System.nanoTime();
            try {
                client.configuration(
                        ConfigOption.LOGIN_TIMEOUT,
                        Integer.toString(config.loginTimeoutSeconds));
                stage = "login";
                log.info(
                        "[OrderGateway] LOGIN_START ne={}, host={}:{}, timeoutSeconds={}",
                        route.ne(),
                        config.host,
                        config.port,
                        config.loginTimeoutSeconds);
                client.login(
                        ServerInfo.builder()
                                .host(config.host)
                                .port(config.port)
                                .username(config.username)
                                .password(config.password)
                                .clientId(config.clientId)
                                .clientSecret(config.clientSecret)
                                .build());
                log.info(
                        "[OrderGateway] LOGIN_DONE ne={}, host={}:{}, elapsedMs={}",
                        route.ne(),
                        config.host,
                        config.port,
                        elapsedMillis(started));
                stage = "init";
                long initStarted = System.nanoTime();
                log.info(
                        "[OrderGateway] INIT_START ne={}, https={}", route.ne(), route.https());
                client.init(route.ne(), route.https());
                log.info(
                        "[OrderGateway] INIT_DONE ne={}, https={}, elapsedMs={}",
                        route.ne(),
                        route.https(),
                        elapsedMillis(initStarted));
                return new DefaultOrderSession(client);
            } catch (RuntimeException e) {
                log.error(
                        "[OrderGateway] SESSION_SETUP_FAILED stage={}, ne={}, host={}:{}, "
                                + "elapsedMs={}, errorType={}, message={}",
                        stage,
                        route.ne(),
                        config.host,
                        config.port,
                        elapsedMillis(started),
                        e.getClass().getSimpleName(),
                        e.getMessage());
                safeLogout(client);
                throw e;
            }
        }
    }

    private static final class DefaultOrderSession implements OrderSession {
        private final StreamingOrderHttpSessionClient client;

        private DefaultOrderSession(StreamingOrderHttpSessionClient client) {
            this.client = client;
        }

        @Override
        public OrderHttpSessionStrResponse execute(
                OrderHttpSessionStrRequest request, int timeoutMillis) {
            return client.execute(request, timeoutMillis);
        }

        @Override
        public void executeStreaming(
                OrderHttpSessionStrRequest request,
                int timeoutMillis,
                Predicate<OrderHttpSessionStrResponse> responseSink) {
            client.executeStreaming(request, timeoutMillis, responseSink);
        }

        @Override
        public void close() {
            safeLogout(client);
        }
    }

    private static void safeLogout(OrderHttpSessionClient client) {
        if (client.getSessionId() == null || client.getSessionId().isBlank()) {
            return;
        }
        try {
            client.logout();
        } catch (RuntimeException e) {
            log.warn("[OrderGateway] Logout failed: {}", e.getMessage());
        }
    }

    /** Immutable configuration for the Eastcom Order SDK adapter. */
    public static final class OrderConfig {
        private final String host;
        private final int port;
        private final String username;
        private final String password;
        private final String clientId;
        private final String clientSecret;
        private final String defaultNe;
        private final Map<String, String> agentNeRoutes;
        private final int timeoutMillis;
        private final int loginTimeoutSeconds;

        private OrderConfig(Builder builder) {
            host = builder.host;
            port = builder.port;
            username = builder.username;
            password = builder.password;
            clientId = builder.clientId;
            clientSecret = builder.clientSecret;
            defaultNe = builder.defaultNe;
            agentNeRoutes = Map.copyOf(builder.agentNeRoutes);
            timeoutMillis = builder.timeoutMillis;
            loginTimeoutSeconds = builder.loginTimeoutSeconds;
        }

        public static Builder builder() {
            return new Builder();
        }

        public static final class Builder {
            private String host;
            private int port;
            private String username;
            private String password;
            private String clientId;
            private String clientSecret;
            private String defaultNe;
            private Map<String, String> agentNeRoutes = Map.of();
            private int timeoutMillis = 600_000;
            private int loginTimeoutSeconds = 15;

            public Builder host(String value) {
                host = value;
                return this;
            }

            public Builder port(int value) {
                port = value;
                return this;
            }

            public Builder username(String value) {
                username = value;
                return this;
            }

            public Builder password(String value) {
                password = value;
                return this;
            }

            public Builder clientId(String value) {
                clientId = value;
                return this;
            }

            public Builder clientSecret(String value) {
                clientSecret = value;
                return this;
            }

            public Builder defaultNe(String value) {
                defaultNe = value;
                return this;
            }

            /** Backward-compatible alias for a single-NE deployment. */
            public Builder ne(String value) {
                defaultNe = value;
                return this;
            }

            public Builder agentNeRoutes(Map<String, String> value) {
                agentNeRoutes = value == null ? Map.of() : Map.copyOf(value);
                return this;
            }

            /** Retained for source compatibility; HTTPS is resolved per AgentCard route. */
            public Builder https(boolean ignored) {
                return this;
            }

            /** Retained for source compatibility; TLS is controlled by the Order SDK. */
            public Builder sslVerify(boolean ignored) {
                return this;
            }

            public Builder timeoutMillis(int value) {
                timeoutMillis = value;
                return this;
            }

            /** Backward-compatible configuration in seconds. */
            public Builder timeoutSeconds(int value) {
                timeoutMillis = Math.multiplyExact(value, 1_000);
                return this;
            }

            public Builder loginTimeoutSeconds(int value) {
                loginTimeoutSeconds = value;
                return this;
            }

            public OrderConfig build() {
                Objects.requireNonNull(host, "host");
                Objects.requireNonNull(username, "username");
                Objects.requireNonNull(password, "password");
                if (port <= 0) {
                    throw new IllegalArgumentException("port must be positive");
                }
                if (timeoutMillis <= 0) {
                    throw new IllegalArgumentException("timeoutMillis must be positive");
                }
                if (loginTimeoutSeconds <= 0) {
                    throw new IllegalArgumentException("loginTimeoutSeconds must be positive");
                }
                if ((defaultNe == null || defaultNe.isBlank()) && agentNeRoutes.isEmpty()) {
                    throw new IllegalArgumentException("defaultNe or agentNeRoutes is required");
                }
                return new OrderConfig(this);
            }
        }
    }
}
