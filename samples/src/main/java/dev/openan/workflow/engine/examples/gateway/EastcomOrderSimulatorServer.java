/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.examples.gateway;

import com.eastcom.apollo.orders.commons.api.HttpSessionService;
import com.eastcom.apollo.orders.internal.shaded.com.google.protobuf.BoolValue;
import com.eastcom.apollo.orders.internal.shaded.com.google.protobuf.ByteString;
import com.eastcom.apollo.orders.internal.shaded.com.google.protobuf.StringValue;
import com.eastcom.apollo.orders.internal.shaded.reactor.core.publisher.Flux;
import com.eastcom.apollo.orders.internal.shaded.reactor.core.publisher.Mono;
import com.eastcom.apollo.orders.internal.shaded.reactor.core.scheduler.Schedulers;
import com.eastcom.apollo.orders.internal.shaded.v11x.com.eastcom.apollo.order.transport.rpc.RpcServer;
import com.eastcom.apollo.orders.internal.shaded.v11x.com.eastcom.apollo.order.transport.rpc.Transport;
import com.eastcom.apollo.orders.internal.shaded.v11x.com.eastcom.apollo.order.transport.rpc.connection.RSocketConnectionListener;
import com.eastcom.apollo.orders.internal.shaded.io.rsocket.ConnectionSetupPayload;
import com.eastcom.apollo.orders.internal.shaded.io.rsocket.DuplexConnection;
import com.eastcom.apollo.orders.internal.shaded.io.rsocket.RSocket;
import com.eastcom.apollo.orders.internal.shaded.v11x.com.eastcom.apollo.orders.commons.metadata.AuthRequest;
import com.eastcom.apollo.orders.internal.shaded.v11x.com.eastcom.apollo.orders.commons.metadata.AuthResponse;
import com.eastcom.apollo.orders.internal.shaded.v11x.com.eastcom.apollo.orders.commons.metadata.Code;
import com.eastcom.apollo.orders.internal.shaded.v11x.com.eastcom.apollo.orders.commons.metadata.DisconnectRequest;
import com.eastcom.apollo.orders.internal.shaded.v11x.com.eastcom.apollo.orders.commons.metadata.httpsession.OrderHttpSessionInitRequest;
import com.eastcom.apollo.orders.internal.shaded.v11x.com.eastcom.apollo.orders.commons.metadata.httpsession.OrderHttpSessionInitResponse;
import com.eastcom.apollo.orders.internal.shaded.v11x.com.eastcom.apollo.orders.commons.metadata.httpsession.OrderHttpSessionRequest;
import com.eastcom.apollo.orders.internal.shaded.v11x.com.eastcom.apollo.orders.commons.metadata.httpsession.OrderHttpSessionResponse;
import com.eastcom.apollo.orders.internal.shaded.v11x.com.eastcom.apollo.orders.commons.util.ObjectUtil;
import dev.openan.workflow.engine.client.SslContextFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * Local protocol simulator for Eastcom Order SDK 1.1.18.
 *
 * <p>Unlike the adapter-level HTTP mock, this server speaks the vendor's real RSocket RPC
 * protocol. The unmodified {@code OrderHttpSessionClient} performs login, session init,
 * execute and logout against this service. The simulator resolves the requested NE and
 * forwards the embedded A2A HTTP request to a local agent.
 *
 * <p>This class deliberately lives in the samples module: it verifies SDK compatibility but is
 * not a production instruction-platform implementation.
 */
public final class EastcomOrderSimulatorServer implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(EastcomOrderSimulatorServer.class);
    private static final Pattern A2A_TERMINAL_STATE =
            Pattern.compile(
                    "\\\"state\\\"\\s*:\\s*\\\"TASK_STATE_(?:COMPLETED|FAILED|CANCELED|REJECTED|INPUT_REQUIRED|AUTH_REQUIRED)\\\"");
    private static final Pattern A2A_FINAL_EVENT =
            Pattern.compile("\\\"isFinal\\\"\\s*:\\s*true");

    private final String host;
    private final int port;
    private final SimulatorService service;
    private final SimulatorConnectionLogger connectionLogger = new SimulatorConnectionLogger();
    private RpcServer server;

    public EastcomOrderSimulatorServer(
            String host,
            int port,
            String username,
            String password,
            String clientId,
            String clientSecret,
            Map<String, String> neTargets) {
        this.host = Objects.requireNonNull(host, "host");
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("port must be between 1 and 65535");
        }
        this.port = port;
        this.service =
                new SimulatorService(
                        username,
                        password,
                        emptyToNull(clientId),
                        emptyToNull(clientSecret),
                        normalizeTargets(neTargets));
    }

    public synchronized void start() {
        if (server != null) {
            return;
        }
        log.info(
                "[EastcomSimulator] START host={}:{}, transport=TCP, neMappings={}",
                host,
                port,
                service.targetNames());
        server =
                RpcServer.create(host, port)
                        .transport(Transport.TCP)
                        .addConnectionListener(connectionLogger)
                        .addService(HttpSessionService.class, service)
                        .start();
        log.info("[EastcomSimulator] READY host={}:{}, protocol=RSocket-RPC", host, port);
    }

    @Override
    public synchronized void close() {
        if (server == null) {
            return;
        }
        connectionLogger.closeAll();
        server.stop();
        server = null;
        service.clear();
        log.info("[EastcomSimulator] STOPPED host={}:{}", host, port);
    }

    private static Map<String, String> normalizeTargets(Map<String, String> targets) {
        Map<String, String> normalized = new LinkedHashMap<>();
        Objects.requireNonNull(targets, "neTargets")
                .forEach(
                        (ne, target) -> {
                            if (ne == null || ne.isBlank() || target == null || target.isBlank()) {
                                throw new IllegalArgumentException("NE and target URL must not be blank");
                            }
                            URI uri = URI.create(target);
                            if (uri.getHost() == null
                                    || !("http".equalsIgnoreCase(uri.getScheme())
                                            || "https".equalsIgnoreCase(uri.getScheme()))) {
                                throw new IllegalArgumentException("Invalid target URL for NE " + ne);
                            }
                            normalized.put(ne, withoutTrailingSlash(target));
                        });
        return Map.copyOf(normalized);
    }

    private static String withoutTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static final class SimulatorConnectionLogger
            implements RSocketConnectionListener {
        private final java.util.Set<RSocket> connections = ConcurrentHashMap.newKeySet();

        @Override
        public void connectionCreated(
                RSocket socket,
                ConnectionSetupPayload setup,
                DuplexConnection connection) {
            connections.add(socket);
            log.info(
                    "[EastcomSimulator] CONNECTION_OPEN remote={}, dataMimeType={}, "
                            + "metadataMimeType={}, keepAliveMs={}, lifetimeMs={}, active={}",
                    connection.remoteAddress(),
                    setup.dataMimeType(),
                    setup.metadataMimeType(),
                    setup.keepAliveInterval(),
                    setup.keepAliveMaxLifetime(),
                    connections.size());
        }

        @Override
        public void connectionClosed(
                RSocket socket,
                ConnectionSetupPayload setup,
                DuplexConnection connection) {
            connections.remove(socket);
            log.info(
                    "[EastcomSimulator] CONNECTION_CLOSE remote={}, active={}",
                    connection.remoteAddress(),
                    connections.size());
        }

        private void closeAll() {
            int count = connections.size();
            connections.forEach(RSocket::dispose);
            connections.clear();
            if (count > 0) {
                log.info("[EastcomSimulator] CONNECTIONS_DISPOSED count={}", count);
            }
        }
    }

    private static final class SimulatorService implements HttpSessionService {
        private final String username;
        private final String password;
        private final String clientId;
        private final String clientSecret;
        private final Map<String, String> neTargets;
        private final Map<String, SessionState> sessions = new ConcurrentHashMap<>();
        private final SSLContext sslContext = SslContextFactory.createTrustAll();

        private SimulatorService(
                String username,
                String password,
                String clientId,
                String clientSecret,
                Map<String, String> neTargets) {
            this.username = Objects.requireNonNull(username, "username");
            this.password = Objects.requireNonNull(password, "password");
            this.clientId = clientId;
            this.clientSecret = clientSecret;
            this.neTargets = neTargets;
        }

        private java.util.Set<String> targetNames() {
            return neTargets.keySet();
        }

        @Override
        public Mono<AuthResponse> login(AuthRequest request) {
            boolean accepted =
                    username.equals(request.getUsername())
                            && password.equals(request.getPassword())
                            && matchesOptional(clientId, request.getParamsMap().get("clientId"))
                            && matchesOptional(clientSecret, request.getParamsMap().get("clientSecret"));
            if (!accepted) {
                log.warn(
                        "[EastcomSimulator] LOGIN_REJECTED username={}, clientIdPresent={}",
                        request.getUsername(),
                        request.getParamsMap().containsKey("clientId"));
                return Mono.just(
                        AuthResponse.newBuilder()
                                .setCode(Code.FAILURE)
                                .setMsg("invalid simulator credentials")
                                .build());
            }
            String sessionId = "sim-" + UUID.randomUUID();
            sessions.put(sessionId, new SessionState(null, null));
            log.info(
                    "[EastcomSimulator] LOGIN_ACCEPTED sessionId={}, username={}",
                    sessionId,
                    request.getUsername());
            return Mono.just(
                    AuthResponse.newBuilder()
                            .setSessionId(sessionId)
                            .setCode(Code.SUCCESS)
                            .setMsg("ok")
                            .build());
        }

        @Override
        public Mono<Void> logout(DisconnectRequest request) {
            sessions.remove(request.getSessionId());
            log.info("[EastcomSimulator] LOGOUT sessionId={}", request.getSessionId());
            return Mono.empty();
        }

        @Override
        public Mono<BoolValue> isConnected(StringValue request) {
            return Mono.just(BoolValue.of(sessions.containsKey(request.getValue())));
        }

        @Override
        public Mono<Void> close(StringValue request) {
            sessions.remove(request.getValue());
            log.info("[EastcomSimulator] CLOSE sessionId={}", request.getValue());
            return Mono.empty();
        }

        @Override
        public Mono<OrderHttpSessionInitResponse> init(OrderHttpSessionInitRequest request) {
            if (!sessions.containsKey(request.getSessionId())) {
                return Mono.error(new IllegalStateException("unknown session"));
            }
            if (!neTargets.containsKey(request.getNe())) {
                log.warn(
                        "[EastcomSimulator] INIT_REJECTED sessionId={}, ne={}",
                        request.getSessionId(),
                        request.getNe());
                return Mono.just(
                        OrderHttpSessionInitResponse.newBuilder().setResult(false).build());
            }
            sessions.put(
                    request.getSessionId(),
                    new SessionState(request.getNe(), request.getSchema()));
            log.info(
                    "[EastcomSimulator] INIT_ACCEPTED sessionId={}, ne={}, schema={}, target={}",
                    request.getSessionId(),
                    request.getNe(),
                    request.getSchema(),
                    neTargets.get(request.getNe()));
            return Mono.just(OrderHttpSessionInitResponse.newBuilder().setResult(true).build());
        }

        @Override
        public Flux<OrderHttpSessionResponse> execute(Flux<OrderHttpSessionRequest> requests) {
            return requests.concatMap(this::forward);
        }

        private Flux<OrderHttpSessionResponse> forward(OrderHttpSessionRequest request) {
            return Flux.defer(() -> forwardBlocking(request))
                    .subscribeOn(Schedulers.boundedElastic());
        }

        private Flux<OrderHttpSessionResponse> forwardBlocking(OrderHttpSessionRequest request) {
            SessionState session = sessions.get(request.getSessionId());
            if (session == null || session.ne() == null) {
                return Flux.error(new IllegalStateException("session is not initialized"));
            }
            String target = neTargets.get(session.ne());
            if (target == null) {
                return Flux.error(new IllegalStateException("NE is not registered: " + session.ne()));
            }
            String path = request.getUriPath().startsWith("/")
                    ? request.getUriPath()
                    : "/" + request.getUriPath();
            String forwardUrl = target + path;
            Object decoded = request.getBody().isEmpty()
                    ? ""
                    : ObjectUtil.b2o(request.getBody().toByteArray());
            String body = decoded == null ? "" : decoded.toString();
            boolean streaming = path.endsWith("/message:stream");
            long started = System.nanoTime();
            log.info(
                    "[EastcomSimulator] FORWARD_START sessionId={}, ne={}, mode={}, method={}, "
                            + "target={}, bodyChars={}, headers={}",
                    request.getSessionId(),
                    session.ne(),
                    streaming ? "stream" : "send",
                    request.getMethod(),
                    forwardUrl,
                    body.length(),
                    request.getHeadersMap().keySet());

            return Flux.create(
                    sink -> {
                        HttpURLConnection connection = null;
                        int chunks = 0;
                        boolean terminalRound = false;
                        try {
                            connection = openConnection(forwardUrl);
                            HttpURLConnection activeConnection = connection;
                            AtomicBoolean disconnectScheduled = new AtomicBoolean();
                            Runnable disconnect =
                                    () -> {
                                        if (disconnectScheduled.compareAndSet(false, true)) {
                                            Schedulers.boundedElastic()
                                                    .schedule(activeConnection::disconnect);
                                        }
                                    };
                            // A2A streaming ends as soon as a terminal event is observed. Propagate
                            // the downstream RSocket cancellation to the blocking HTTP read so the
                            // OMC SSE connection and its event-consumer thread are released promptly.
                            // HttpURLConnection.disconnect() can itself block in ChunkedInputStream
                            // close, so never execute it on an RSocket or Spring shutdown thread.
                            sink.onCancel(disconnect::run);
                            sink.onDispose(disconnect::run);
                            connection.setRequestMethod(
                                    request.getMethod().isBlank() ? "POST" : request.getMethod());
                            connection.setDoOutput(true);
                            connection.setConnectTimeout(30_000);
                            connection.setReadTimeout(600_000);
                            copyHeaders(request.getHeadersMap(), connection);
                            try (OutputStream output = connection.getOutputStream()) {
                                output.write(body.getBytes(StandardCharsets.UTF_8));
                            }
                            int status = connection.getResponseCode();
                            Map<String, String> responseHeaders = responseHeaders(connection);
                            InputStream input = status >= 400
                                    ? connection.getErrorStream()
                                    : connection.getInputStream();
                            if (input == null) {
                                sink.next(response(status, responseHeaders, ""));
                            } else if (streaming && status < 400) {
                                byte[] buffer = new byte[1024];
                                StringBuilder terminalScan = new StringBuilder();
                                boolean terminal = false;
                                int length;
                                while (!sink.isCancelled()
                                        && (length = input.read(buffer)) != -1) {
                                    chunks++;
                                    String chunk =
                                            new String(buffer, 0, length, StandardCharsets.UTF_8);
                                    log.debug(
                                            "[EastcomSimulator] FORWARD_CHUNK sessionId={}, "
                                                    + "index={}, chars={}, elapsedMs={}",
                                            request.getSessionId(),
                                            chunks,
                                            chunk.length(),
                                            elapsedMillis(started));
                                    sink.next(response(status, responseHeaders, chunk));
                                    terminalScan.append(chunk);
                                    if (containsA2ATerminalEvent(terminalScan)) {
                                        terminal = true;
                                        terminalRound = true;
                                        log.info(
                                                "[EastcomSimulator] FORWARD_TERMINAL "
                                                        + "sessionId={}, index={}, action=close-round",
                                                request.getSessionId(),
                                                chunks);
                                        // Complete the RPC response before disconnecting the
                                        // blocking HttpURLConnection. Both disconnect() and
                                        // HttpInputStream.close() may block while an interrupted A2A
                                        // task keeps its SSE response open.
                                        sink.complete();
                                        log.info(
                                                "[EastcomSimulator] FORWARD_DONE sessionId={}, ne={}, "
                                                        + "mode=stream, status={}, chunks={}, elapsedMs={}",
                                                request.getSessionId(),
                                                session.ne(),
                                                status,
                                                chunks,
                                                elapsedMillis(started));
                                        disconnect.run();
                                        return;
                                    }
                                    if (terminalScan.length() > 32_768) {
                                        terminalScan.delete(0, terminalScan.length() - 16_384);
                                    }
                                }
                                if (!terminal && !sink.isCancelled()) {
                                    input.close();
                                }
                            } else {
                                try (input) {
                                    sink.next(
                                            response(
                                                    status,
                                                    responseHeaders,
                                                    new String(input.readAllBytes(), StandardCharsets.UTF_8)));
                                }
                            }
                            sink.complete();
                            log.info(
                                    "[EastcomSimulator] FORWARD_DONE sessionId={}, ne={}, mode={}, "
                                            + "status={}, chunks={}, elapsedMs={}",
                                    request.getSessionId(),
                                    session.ne(),
                                    streaming ? "stream" : "send",
                                    status,
                                    chunks,
                                    elapsedMillis(started));
                        } catch (Exception e) {
                            log.error(
                                    "[EastcomSimulator] FORWARD_FAILED sessionId={}, ne={}, "
                                            + "elapsedMs={}, message={}",
                                    request.getSessionId(),
                                    session.ne(),
                                    elapsedMillis(started),
                                    e.getMessage(),
                                    e);
                            sink.error(e);
                        } finally {
                            if (connection != null && !terminalRound) {
                                HttpURLConnection connectionToClose = connection;
                                Schedulers.boundedElastic()
                                        .schedule(connectionToClose::disconnect);
                            }
                        }
                    });
        }

        private HttpURLConnection openConnection(String url) throws Exception {
            HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
            if (connection instanceof HttpsURLConnection https) {
                https.setSSLSocketFactory(sslContext.getSocketFactory());
                https.setHostnameVerifier((host, session) -> true);
            }
            return connection;
        }

        private static void copyHeaders(
                Map<String, String> headers, HttpURLConnection connection) {
            headers.forEach(
                    (name, value) -> {
                        String lower = name.toLowerCase(Locale.ROOT);
                        if (!List.of("host", "content-length", "connection").contains(lower)) {
                            connection.setRequestProperty(name, value);
                        }
                    });
            if (connection.getRequestProperty("Content-Type") == null) {
                connection.setRequestProperty("Content-Type", "application/json");
            }
        }

        private static Map<String, String> responseHeaders(HttpURLConnection connection) {
            Map<String, String> result = new LinkedHashMap<>();
            connection.getHeaderFields()
                    .forEach(
                            (name, values) -> {
                                if (name != null && values != null && !values.isEmpty()) {
                                    result.put(name, String.join(",", values));
                                }
                            });
            return result;
        }

        private static OrderHttpSessionResponse response(
                int status, Map<String, String> headers, String body) {
            return OrderHttpSessionResponse.newBuilder()
                    .setStatus(status)
                    .putAllHeaders(headers)
                    .setBody(ByteString.copyFrom(ObjectUtil.o2b(body)))
                    .build();
        }

        private static boolean containsA2ATerminalEvent(CharSequence payload) {
            String normalized = payload.toString().replace("\r\n", "\n");
            int frameStart = 0;
            int frameEnd;
            while ((frameEnd = normalized.indexOf("\n\n", frameStart)) >= 0) {
                String completeFrame = normalized.substring(frameStart, frameEnd);
                if (A2A_TERMINAL_STATE.matcher(completeFrame).find()
                        || A2A_FINAL_EVENT.matcher(completeFrame).find()) {
                    return true;
                }
                frameStart = frameEnd + 2;
            }
            return false;
        }

        private static boolean matchesOptional(String expected, String actual) {
            return expected == null || expected.equals(actual);
        }

        private void clear() {
            sessions.clear();
        }

        private record SessionState(String ne, String schema) {}
    }

    private static long elapsedMillis(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }
}
