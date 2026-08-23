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

import com.eastcom.apollo.orders.commons.api.HttpService;
import com.eastcom.apollo.orders.commons.api.base.OrderService;
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
import com.eastcom.apollo.orders.internal.shaded.v11x.com.eastcom.apollo.orders.commons.metadata.HttpInfo;
import com.eastcom.apollo.orders.internal.shaded.v11x.com.eastcom.apollo.orders.commons.metadata.HttpServerBindRequest;
import com.eastcom.apollo.orders.internal.shaded.v11x.com.eastcom.apollo.orders.commons.metadata.HttpServerBindResponse;
import com.eastcom.apollo.orders.internal.shaded.v11x.com.eastcom.apollo.orders.commons.metadata.HttpServerUnbindRequest;
import com.eastcom.apollo.orders.internal.shaded.v11x.com.eastcom.apollo.orders.commons.metadata.HttpServerUnbindResponse;
import com.eastcom.apollo.orders.internal.shaded.v11x.com.eastcom.apollo.orders.commons.metadata.OrderHttpRequest;
import com.eastcom.apollo.orders.internal.shaded.v11x.com.eastcom.apollo.orders.commons.metadata.OrderHttpResponse;
import com.eastcom.apollo.orders.internal.shaded.v11x.com.eastcom.apollo.orders.commons.metadata.OrderResourceRequest;
import com.eastcom.apollo.orders.internal.shaded.v11x.com.eastcom.apollo.orders.commons.metadata.OrderResourceResponse;
import com.eastcom.apollo.orders.internal.shaded.v11x.com.eastcom.apollo.orders.commons.util.ObjectUtil;
import dev.openan.workflow.engine.client.SslContextFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
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
 * protocol. The {@code HttpClient} API performs login, loadNeResource, execute and logout
 * via the {@code HttpService} RSocket interface. The simulator resolves the requested NE and
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
    private static final Pattern A2A_ANY_STATE =
            Pattern.compile("\\\"state\\\"\\s*:\\s*\\\"TASK_STATE_");
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
                        .addService(HttpService.class, service)
                        .addService(OrderService.class, service)
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

    private static final class SimulatorService implements HttpService, OrderService {
        private final String username;
        private final String password;
        private final String clientId;
        private final String clientSecret;
        private final Map<String, String> neTargets;
        private volatile String lastResolvedTarget;
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
        public Mono<OrderResourceResponse> loadNeResource(OrderResourceRequest request) {
            String neName = request.getNeName();
            if (!neTargets.containsKey(neName)) {
                log.warn("[EastcomSimulator] LOAD_NE_REJECTED ne={}", neName);
                return Mono.just(OrderResourceResponse.getDefaultInstance());
            }
            String targetUrl = neTargets.get(neName);
            lastResolvedTarget = targetUrl;
            log.info(
                    "[EastcomSimulator] LOAD_NE_ACCEPTED ne={}, target={}",
                    neName, targetUrl);
            return Mono.just(
                    OrderResourceResponse.newBuilder()
                            .setNeUrl(targetUrl)
                            .build());
        }

        @Override
        public Mono<HttpServerBindResponse> bind(HttpServerBindRequest request) {
            log.warn("[EastcomSimulator] BIND not supported");
            return Mono.just(HttpServerBindResponse.getDefaultInstance());
        }

        @Override
        public Mono<HttpServerUnbindResponse> unbind(HttpServerUnbindRequest request) {
            log.warn("[EastcomSimulator] UNBIND not supported");
            return Mono.just(HttpServerUnbindResponse.getDefaultInstance());
        }

        @Override
        public Flux<OrderHttpResponse> execute(Flux<OrderHttpRequest> requests) {
            // The SDK sends HTTP request as multiple OrderHttpRequest items via RSocket
            // requestChannel. The SDK does NOT complete the request Flux after sending,
            // so we must complete the response Flux independently when the HTTP exchange
            // is done, otherwise the RSocket channel stays open and blocks subsequent
            // requestResponse calls (loadNeResource for the next NE).
            return Flux.create(sink -> {
                AtomicBoolean headersParsed = new AtomicBoolean(false);
                AtomicBoolean forwarded = new AtomicBoolean(false);
                StringBuilder bodyBuf = new StringBuilder();
                String[] parsedMethod = {"POST"};
                String[] parsedPath = {"/"};
                Map<String, String> parsedHeaders = new LinkedHashMap<>();

                requests.subscribe(
                    req -> {
                        String data = req.getData().toStringUtf8();
                        if (!headersParsed.get() && (data.startsWith("POST") || data.startsWith("GET")
                                || data.startsWith("PUT") || data.startsWith("DELETE"))) {
                            // Parse HTTP request headers
                            int headerEnd = data.indexOf("\r\n\r\n");
                            if (headerEnd < 0) headerEnd = data.indexOf("\n\n");
                            String headerSection = headerEnd > 0 ? data.substring(0, headerEnd) : data;
                            String[] headerLines = headerSection.split("\r?\n");
                            if (headerLines.length > 0) {
                                String[] reqLine = headerLines[0].split(" ");
                                if (reqLine.length >= 2) {
                                    parsedMethod[0] = reqLine[0];
                                    parsedPath[0] = reqLine[1];
                                }
                            }
                            for (int i = 1; i < headerLines.length; i++) {
                                int colon = headerLines[i].indexOf(':');
                                if (colon > 0) {
                                    parsedHeaders.put(headerLines[i].substring(0, colon).trim(),
                                            headerLines[i].substring(colon + 1).trim());
                                }
                            }
                            headersParsed.set(true);
                            if (headerEnd > 0) {
                                int sepLen = data.charAt(headerEnd + 1) == '\n' ? 2 : 4;
                                if (headerEnd + sepLen < data.length()) {
                                    bodyBuf.append(data.substring(headerEnd + sepLen));
                                }
                            }
                        } else {
                            bodyBuf.append(data);
                        }
                        // Forward once we have headers and sufficient body
                        if (!forwarded.get() && headersParsed.get() && bodyBuf.length() > 10) {
                            forwarded.set(true);
                            String httpMethod = parsedMethod[0];
                            String httpPath = parsedPath[0];
                            String body = bodyBuf.toString();
                            // The SDK's configureHeaderHost sets the HTTP Host header to the
                            // full neUrl (e.g. "https://127.0.0.1:26335") returned by
                            // loadNeResource.  Using this per-request header avoids the race
                            // condition on the shared lastResolvedTarget field when two NEs
                            // call loadNeResource concurrently on the same RSocket connection.
                            String hostHeader = findHeader(parsedHeaders, "Host");
                            String targetBase;
                            if (hostHeader != null
                                    && (hostHeader.startsWith("http://")
                                            || hostHeader.startsWith("https://"))) {
                                targetBase = withoutTrailingSlash(hostHeader);
                            } else {
                                targetBase = lastResolvedTarget != null
                                        ? lastResolvedTarget : "http://127.0.0.1:26335";
                            }
                            String forwardUrl = targetBase
                                    + (httpPath.startsWith("/") ? httpPath : "/" + httpPath);
                            boolean streaming = forwardUrl.endsWith("/message:stream");
                           log.info("[EastcomSimulator] FORWARD_START method={}, mode={}, target={}, bodyChars={}",
                                   httpMethod, streaming ? "stream" : "send", forwardUrl, body.length());
                           forwardParsed(httpMethod, httpPath, parsedHeaders, body, forwardUrl, streaming)
                                    .subscribeOn(Schedulers.boundedElastic())
                                   .subscribe(
                                       sink::next,
                                       sink::error,
                                       () -> {
                                            // Response Flux completed - complete the sink
                                            // to close the RSocket channel
                                            sink.complete();
                                        });
                        }
                    },
                    sink::error,
                    () -> {
                        // Request Flux completed - if not forwarded yet, this is an error
                        if (!forwarded.get()) {
                            sink.error(new IllegalStateException("request completed without body"));
                        }
                        // If already forwarded, the forwardParsed completion will call sink.complete()
                    });
            });
        }

        private Flux<OrderHttpResponse> forwardParsed(
                String httpMethod, String httpPath,
                Map<String, String> httpHeaders, String body,
                String forwardUrl, boolean streaming) {
            long started = System.nanoTime();
            final String fMethod = httpMethod;
            final Map<String, String> fHeaders = httpHeaders;
            final String fBody = body;
            final String fForwardUrl = forwardUrl;
            log.info("[EastcomSimulator] FORWARD_PARSED method={}, bodyChars={}, target={}",
                    fMethod, fBody.length(), fForwardUrl);
            return Flux.create(
                    sink -> {
                        HttpURLConnection connection = null;
                        int chunks = 0;
                        boolean terminalRound = false;
                        try {
                            connection = openConnection(fForwardUrl);
                            HttpURLConnection activeConnection = connection;
                            AtomicBoolean disconnectScheduled = new AtomicBoolean();
                            Runnable disconnect =
                                    () -> {
                                        if (disconnectScheduled.compareAndSet(false, true)) {
                                            Schedulers.boundedElastic()
                                                    .schedule(activeConnection::disconnect);
                                        }
                                    };
                            sink.onCancel(disconnect::run);
                            sink.onDispose(disconnect::run);
                            connection.setRequestMethod(fMethod.isBlank() ? "POST" : fMethod);
                            connection.setDoOutput(true);
            connection.setConnectTimeout(30_000);
            // Read timeout must cover LLM-backed OMC processing (A2AT_LLM_TIMEOUT_SECONDS
            // defaults to 60s). Notification-T streams are no longer long-lived
            // (ack-and-release), so this only applies to active diagnosis requests.
            connection.setReadTimeout(65_000);
            copyHeaders(fHeaders, connection);
                            try (OutputStream output = connection.getOutputStream()) {
                                output.write(fBody.getBytes(StandardCharsets.UTF_8));
                            }
                            int status = connection.getResponseCode();
                            InputStream input = status >= 400
                                    ? connection.getErrorStream()
                                    : connection.getInputStream();
                            if (input == null) {
                                sink.next(responseWithHeader(""));
                                sink.next(responseEnd());
                            } else if (streaming && status < 400) {
                                InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8);
                                char[] buffer = new char[4096];
                                StringBuilder terminalScan = new StringBuilder();
                                boolean terminal = false;
                                int length;
                                while (!sink.isCancelled()
                                        && (length = reader.read(buffer)) != -1) {
                                    chunks++;
                                    String chunk = new String(buffer, 0, length);
                                    log.debug("[EastcomSimulator] FORWARD_CHUNK index={}, chars={}, elapsedMs={}",
                                            chunks, chunk.length(), elapsedMillis(started));
                                    sink.next(chunks == 1 ? responseWithHeader(chunk) : responseChunk(chunk));
                                    terminalScan.append(chunk);
                                    if (containsA2ATerminalEvent(terminalScan)) {
                                        terminal = true;
                                        terminalRound = true;
                                        log.info("[EastcomSimulator] FORWARD_TERMINAL index={}, action=terminal-drain", chunks);
                                        // Close immediately; do not drain the OMC stream
                                        // (it may stay open and cause read timeout)
                                        sink.next(responseEnd());
                                        sink.complete();
                                        log.info("[EastcomSimulator] FORWARD_DONE mode=stream-terminal, status={}, chunks={}, elapsedMs={}",
                                                status, chunks, elapsedMillis(started));
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
                                sink.next(responseEnd());
                                sink.complete();
                                log.info("[EastcomSimulator] FORWARD_DONE mode=stream, status={}, chunks={}, elapsedMs={}",
                                        status, chunks, elapsedMillis(started));
                            } else {
                                try (input) {
                                    sink.next(responseWithHeader(
                                            new String(input.readAllBytes(), StandardCharsets.UTF_8)));
                                }
                                sink.next(responseEnd());
                                sink.complete();
                                log.info("[EastcomSimulator] FORWARD_DONE mode=send, status={}, chunks={}, elapsedMs={}",
                                        status, chunks, elapsedMillis(started));
                            }
                        } catch (Exception e) {
                            if (e instanceof java.net.SocketTimeoutException && !terminalRound) {
                                // Notification-T idle read timeout: complete the response
                                // gracefully so the RSocket channel releases for the next NE.
                                // If no data was received (chunks=0), send a proper HTTP
                                // response header before the end chunk to avoid Netty parse errors.
                                if (chunks == 0) {
                                    sink.next(responseWithHeader(""));
                                } else {
                                    sink.next(responseEnd());
                                }
                                sink.complete();
                                log.info("[EastcomSimulator] FORWARD_IDLE_TIMEOUT chunks={}, elapsedMs={}, action=close-idle",
                                        chunks, elapsedMillis(started));
                            } else {
                                log.error("[EastcomSimulator] FORWARD_FAILED elapsedMs={}, message={}",
                                        elapsedMillis(started), e.getMessage(), e);
                                sink.error(e);
                            }
                        } finally {
                            if (connection != null && !terminalRound) {
                                HttpURLConnection connectionToClose = connection;
                                Schedulers.boundedElastic().schedule(connectionToClose::disconnect);
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

        /** Case-insensitive header lookup (HTTP header names are case-insensitive). */
        private static String findHeader(Map<String, String> headers, String name) {
            String exact = headers.get(name);
            if (exact != null) {
                return exact;
            }
            for (var entry : headers.entrySet()) {
                if (name.equalsIgnoreCase(entry.getKey())) {
                    return entry.getValue();
                }
            }
            return null;
        }

        private static OrderHttpResponse responseWithHeader(String body) {
            // The SDK feeds OrderHttpResponse.data through Netty's HttpResponseDecoder.
            // The first chunk must include HTTP status line + headers.
            // Use UTF-8 byte length for chunk size, not String char length.
            byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
            String withHeader = "HTTP/1.1 200 OK\r\n"
                    + "Content-Type: text/event-stream\r\n"
                    + "Transfer-Encoding: chunked\r\n"
                    + "\r\n"
                    + Integer.toHexString(bodyBytes.length) + "\r\n"
                    + body + "\r\n";
            return OrderHttpResponse.newBuilder()
                    .setData(ByteString.copyFromUtf8(withHeader))
                    .build();
        }

        private static OrderHttpResponse responseChunk(String body) {
            // Subsequent chunks: use UTF-8 byte length for chunk size
            byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
            String chunk = Integer.toHexString(bodyBytes.length) + "\r\n" + body + "\r\n";
            return OrderHttpResponse.newBuilder()
                    .setData(ByteString.copyFromUtf8(chunk))
                    .build();
        }

        private static OrderHttpResponse responseEnd() {
            // Final chunk: zero-length chunk to signal end
            return OrderHttpResponse.newBuilder()
                    .setData(ByteString.copyFromUtf8("0\r\n\r\n"))
                    .build();
        }

        private static OrderHttpResponse response(String body) {
            return OrderHttpResponse.newBuilder()
                    .setData(ByteString.copyFromUtf8(body))
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

        private static boolean containsA2AAnyState(CharSequence payload) {
            String normalized = payload.toString().replace("\r\n", "\n");
            return A2A_ANY_STATE.matcher(normalized).find()
                    || A2A_FINAL_EVENT.matcher(normalized).find();
        }

        /**
         * Detects any A2A state event within a COMPLETE SSE frame (delimited by \n\n).
         * Unlike containsA2AAnyState which may match partial data, this method only
         * returns true when a full SSE frame containing a state field has been received.
         * This prevents JSON truncation when the state appears mid-frame.
         *
         * For Notification-T, the initial TASK_STATE_WORKING frame triggers completion,
         * allowing the RSocket channel to close and unblock subsequent NE requests.
         * For Task-T, terminal states (COMPLETED, INPUT_REQUIRED, etc.) trigger completion.
         * Intermediate WORKING frames for Task-T also trigger completion, but the engine
         * re-opens a new stream for follow-up requests (Negotiation-T).
         */
        private static boolean containsA2AStateInCompleteFrame(CharSequence payload) {
            String normalized = payload.toString().replace("\r\n", "\n");
            int frameStart = 0;
            int frameEnd;
            while ((frameEnd = normalized.indexOf("\n\n", frameStart)) >= 0) {
                String completeFrame = normalized.substring(frameStart, frameEnd);
                if (A2A_ANY_STATE.matcher(completeFrame).find()
                        || A2A_FINAL_EVENT.matcher(completeFrame).find()
                        || A2A_TERMINAL_STATE.matcher(completeFrame).find()) {
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
