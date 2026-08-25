/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 *    Licensed under the Apache License, Version 2.0 (the License); you may
 *    not use this file except in compliance with the License. You may obtain
 *    a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an AS IS BASIS, WITHOUT
 *    WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *    License for the specific language governing permissions and limitations
 *    under the License.
 */

package dev.openan.workflow.engine.examples.gateway;

import com.eastcom.apollo.orders.internal.shaded.v11x.com.eastcom.apollo.orders.client.core.common.ServerInfo;
import com.eastcom.apollo.orders.internal.shaded.v11x.com.eastcom.apollo.orders.commons.metadata.httpsession.OrderHttpSessionStrRequest;
import com.eastcom.apollo.orders.internal.shaded.v11x.com.eastcom.apollo.orders.commons.metadata.httpsession.OrderHttpSessionStrResponse;

import com.google.protobuf.util.JsonFormat;

import dev.openan.workflow.engine.client.A2AJavaClientRuntime;
import dev.openan.workflow.engine.client.A2ATExtension;
import dev.openan.workflow.engine.client.A2ATransport;
import dev.openan.workflow.engine.client.ConversationScopedA2AJavaClientRuntime;
import dev.openan.workflow.engine.model.SendMessageResult;

import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.client.transport.spi.interceptors.ClientCallContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.a2aproject.sdk.grpc.SendMessageRequest;
import org.a2aproject.sdk.grpc.utils.ProtoUtils;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.MessageSendParams;
import org.a2aproject.sdk.spec.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * A2A client runtime backed by the Eastcom Order SDK.
 *
 * <p>The adapter is deliberately split into routing, conversation-scoped client management and
 * response parsing. A logical Order client is reused for A2A requests in the same {@code contextId
 * + NE + channel} conversation and access to that client is serialized. The public vendor {@code
 * HttpClient} API does not expose login/init/logout semantics, so this logical lifecycle must not
 * be interpreted as a physical platform session. Agent-to-NE routing is supplied by {@link
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
    private static final String INCLUDE_BODY = "WORKFLOW_ENGINE_PROTOCOL_INCLUDE_BODY";
    private static final String MAX_BODY_CHARS = "WORKFLOW_ENGINE_PROTOCOL_MAX_BODY_CHARS";
    private static final int DEFAULT_MAX_BODY_CHARS = 100_000;
    private static final String REQUEST_CHANNEL = "request";
    private static final String NOTIFICATION_CHANNEL = "notification";
    private static final String TASK_SUBSCRIPTION_CHANNEL = "task-subscription";

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
    public org.a2aproject.sdk.spec.Task getTask(
            AgentCard agentCard, String taskId,
            org.a2aproject.sdk.client.transport.spi.interceptors.ClientCallContext callContext) {
        AgentGatewayRoute route = routeResolver.resolve(agentCard);
        return executeTaskRequest(
                agentCard,
                route,
                taskId,
                "GET",
                route.taskPath(null, taskId, ""),
                "",
                callContext);
    }

    @Override
    public org.a2aproject.sdk.spec.Task cancelTask(
            AgentCard agentCard, String taskId,
            org.a2aproject.sdk.client.transport.spi.interceptors.ClientCallContext callContext) {
        AgentGatewayRoute route = routeResolver.resolve(agentCard);
        return executeTaskRequest(
                agentCard,
                route,
                taskId,
                "POST",
                route.taskPath(null, taskId, ":cancel"),
                "{}",
                callContext);
    }

    @Override
    public CompletableFuture<SendMessageResult> subscribeToTask(
            AgentCard agentCard, String taskId,
            org.a2aproject.sdk.client.transport.spi.interceptors.ClientCallContext callContext,
            java.util.function.Consumer<ClientEvent> eventSink) {
        if (!supportsStreaming(agentCard)) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException(
                            "subscribeToTask requires agent streaming capability: "
                                    + agentCard.name()));
        }
        return CompletableFuture.supplyAsync(
                () -> subscribeToTaskBlocking(agentCard, taskId, callContext, eventSink));
    }

    private Task executeTaskRequest(
            AgentCard agentCard,
            AgentGatewayRoute route,
            String taskId,
            String method,
            String path,
            String body,
            ClientCallContext callContext) {
        String requestId = java.util.UUID.randomUUID().toString();
        ConversationSessionHandle handle = null;
        try {
            // Task query/cancel has no conversation id in the A2A client API. Use an ephemeral
            // authenticated Order session so it cannot interfere with a workflow or Notification.
            handle = sessionManager.acquire(null, route, REQUEST_CHANNEL);
            OrderHttpSessionStrRequest request =
                    OrderHttpSessionStrRequest.newBuilder()
                            .setUriPath(path)
                            .setMethod(method)
                            .putAllHeaders(extractHeaders(callContext, false))
                            .setBody(body)
                            .build();
            logProtocolRequest(requestId, agentCard.name(), route.ne(), request);
            OrderHttpSessionStrResponse response =
                    handle.session().execute(request, config.timeoutMillis);
            logProtocolResponse(requestId, agentCard.name(), 1, response);
            validateResponse(response);
            org.a2aproject.sdk.grpc.Task.Builder task =
                    org.a2aproject.sdk.grpc.Task.newBuilder();
            JsonFormat.parser().merge(response.getBody(), task);
            handle.release();
            handle = null;
            log.info(
                    "[OrderGateway] TASK_{} requestId={}, agent={}, ne={}, taskId={}, path={}",
                    method,
                    requestId,
                    agentCard.name(),
                    route.ne(),
                    taskId,
                    path);
            return ProtoUtils.FromProto.task(task.build());
        } catch (Exception e) {
            if (handle != null) handle.invalidate();
            throw new IllegalStateException(
                    "Eastcom gateway task " + method + " failed for " + taskId + ": "
                            + e.getMessage(),
                    e);
        }
    }

    private SendMessageResult subscribeToTaskBlocking(
            AgentCard agentCard,
            String taskId,
            ClientCallContext callContext,
            Consumer<ClientEvent> eventSink) {
        String requestId = java.util.UUID.randomUUID().toString();
        AgentGatewayRoute route = routeResolver.resolve(agentCard);
        ConversationSessionHandle handle = null;
        try {
            handle = sessionManager.acquire(taskId, route, TASK_SUBSCRIPTION_CHANNEL);
            OrderHttpSessionStrRequest request =
                    OrderHttpSessionStrRequest.newBuilder()
                            .setUriPath(route.taskPath(null, taskId, ":subscribe"))
                            .setMethod("POST")
                            .putAllHeaders(extractHeaders(callContext, true))
                            .setBody("{}")
                            .build();
            logProtocolRequest(requestId, agentCard.name(), route.ne(), request);
            List<ClientEvent> events =
                    executeStreaming(
                            handle.session(), request, eventSink, requestId, agentCard.name());
            // A task subscription ends at the terminal event; do not retain its authenticated
            // vendor session. Notification-T has its own explicitly managed long-lived lane.
            handle.invalidate();
            handle = null;
            return SendMessageResult.builder()
                    .text(A2ATransport.extractResponseText(events))
                    .task(A2ATransport.extractResponseTask(events))
                    .taskState(A2ATransport.extractResponseTaskState(events))
                    .metadata(A2ATransport.extractResponseMetadata(events))
                    .build();
        } catch (Exception e) {
            if (handle != null) handle.invalidate();
            throw new IllegalStateException(
                    "Eastcom gateway subscribeToTask failed for " + taskId + ": "
                            + e.getMessage(),
                    e);
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
        AtomicInteger sseFrameCount = new AtomicInteger();
        GatewayA2AResponseParser.StreamingSession parserSession =
                responseParser.newStreamingSession(
                        eventSink,
                        frame ->
                                logSseFrame(
                                        requestId,
                                        agentName,
                                        sseFrameCount.incrementAndGet(),
                                        frame));
        AtomicInteger chunkCount = new AtomicInteger();
        session.executeStreaming(
                request,
                config.timeoutMillis,
                response -> {
                    if (chunkCount.incrementAndGet() == 1) {
                        logProtocolResponseHead(requestId, agentName, response);
                    }
                    validateResponse(response);
                    log.debug(
                            "[OrderGateway] STREAM_CHUNK requestId={}, agent={}, chunk={}, bodyChars={}",
                            requestId,
                            agentName,
                            chunkCount.get(),
                            response.getBody().length());
                    boolean terminal = parserSession.accept(response.getBody());
                    if (terminal) {
                        log.info(
                                "[OrderGateway] STREAM_TERMINAL requestId={}, agent={}, chunk={}, sseFrame={}",
                                requestId,
                                agentName,
                                chunkCount.get(),
                                sseFrameCount.get());
                    }
                    return terminal;
                });
        List<ClientEvent> events = parserSession.complete();
        if (events.isEmpty()) {
            throw new IllegalStateException("Gateway message:stream closed without an A2A event");
        }
        log.info(
                "[OrderGateway] STREAM_CLOSED requestId={}, agent={}, chunks={}, sseFrames={}, events={}",
                requestId,
                agentName,
                chunkCount.get(),
                sseFrameCount.get(),
                events.size());
        return events;
    }

    private static void logSseFrame(
            String requestId, String agentName, int frame, String sseFrame) {
        if (!protocolLog.isDebugEnabled()) {
            return;
        }
        protocolLog.debug(
                "<<< [OrderSDK] SSE_FRAME requestId={}, agent={}, frame={}\n=== Body ===\n{}",
                requestId,
                agentName,
                frame,
                formatProtocolBody(formatSseFrame(sseFrame)));
    }

    /**
     * Pretty-prints an SSE frame for protocol logging: control fields ({@code id:}, {@code
     * event:}, ...) stay as-is, and the {@code data:} payload is indented as JSON. A frame without
     * {@code data:} lines is treated as one plain JSON payload.
     */
    static String formatSseFrame(String sseFrame) {
        String normalized = sseFrame.replace("\r\n", "\n");
        StringBuilder control = new StringBuilder();
        StringBuilder data = new StringBuilder();
        boolean dataStarted = false;
        for (String line : normalized.split("\n", -1)) {
            if (line.startsWith("data:")) {
                dataStarted = true;
                appendSseLine(data, line.substring(5).stripLeading());
            } else if (dataStarted && !isSseControlField(line)) {
                appendSseLine(data, line);
            } else {
                appendSseLine(control, line);
            }
        }
        if (data.isEmpty()) {
            return normalizeNewlines(prettyJson(normalized));
        }
        StringBuilder result = new StringBuilder(control.toString().stripTrailing());
        if (!result.isEmpty()) {
            result.append('\n');
        }
        result.append("data: ").append(normalizeNewlines(prettyJson(data.toString())));
        return result.toString();
    }

    private static String normalizeNewlines(String value) {
        return value.replace("\r\n", "\n");
    }

    private static void appendSseLine(StringBuilder target, String value) {
        if (!target.isEmpty()) {
            target.append('\n');
        }
        target.append(value);
    }

    private static boolean isSseControlField(String line) {
        return line.startsWith("event:")
                || line.startsWith("id:")
                || line.startsWith("retry:")
                || line.startsWith(":");
    }

    private static void logProtocolResponseHead(
            String requestId, String agentName, OrderHttpSessionStrResponse response) {
        if (!protocolLog.isDebugEnabled()) {
            return;
        }
        protocolLog.debug(
                "<<< [OrderSDK] RESPONSE requestId={}, agent={}\n"
                        + "=== Status ===\n{}\n"
                        + "=== Headers ===\n{}",
                requestId,
                agentName,
                response.getStatus(),
                formatProtocolHeaders(response.getHeadersMap()));
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
                formatProtocolBody(prettyJson(request.getBody())));
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
                formatProtocolBody(prettyJson(response.getBody())));
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
        return booleanSetting(INCLUDE_SENSITIVE_HEADERS, false);
    }

    static String formatProtocolBody(String body) {
        if (!booleanSetting(INCLUDE_BODY, false)) {
            return "(body logging disabled)";
        }
        String value = body != null ? body : "";
        int maxChars = intSetting(MAX_BODY_CHARS, DEFAULT_MAX_BODY_CHARS);
        if (value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, maxChars)
                + "\n... (truncated, originalChars="
                + value.length()
                + ")";
    }

    private static boolean booleanSetting(String name, boolean defaultValue) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            value = System.getenv(name);
        }
        return value == null || value.isBlank()
                ? defaultValue
                : Boolean.parseBoolean(value);
    }

    private static int intSetting(String name, int defaultValue) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            value = System.getenv(name);
        }
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(value);
            return parsed > 0 ? parsed : defaultValue;
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
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
        return params.message().metadata().containsKey(A2ATExtension.NOTIFICATION_T.uri())
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
            // A conversation owns its logical client. Independent channels targeting the same NE
            // cannot block or close one another's adapter.
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
            for (var entry : new java.util.ArrayList<>(sessions.values())) {
                closeSession(entry);
            }
            sessions.clear();
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
            long started = System.nanoTime();
            try {
                log.info(
                        "[OrderGateway] SESSION_CREATE ne={}, host={}:{}, https={}",
                        route.ne(),
                        config.host,
                        config.port,
                        route.https());
                ServerInfo serverInfo = ServerInfo.builder()
                        .host(config.host)
                        .port(config.port)
                        .username(config.username)
                        .password(config.password)
                        .clientId(config.clientId)
                        .clientSecret(config.clientSecret)
                        .build();
                OrderHttpClientAdapter adapter =
                        new OrderHttpClientAdapter(serverInfo, route.ne(), route.https());
                log.info(
                        "[OrderGateway] SESSION_READY ne={}, elapsedMs={}",
                        route.ne(),
                        elapsedMillis(started));
                return adapter;
            } catch (RuntimeException e) {
                log.error(
                        "[OrderGateway] SESSION_SETUP_FAILED ne={}, host={}:{}, "
                                + "elapsedMs={}, errorType={}, message={}",
                        route.ne(),
                        config.host,
                        config.port,
                        elapsedMillis(started),
                        e.getClass().getSimpleName(),
                        e.getMessage());
                throw e;
            }
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
