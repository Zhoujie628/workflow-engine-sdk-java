/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.examples.gateway;

import com.eastcom.apollo.orders.internal.shaded.reactor.netty.http.client.HttpClientResponse;
import com.eastcom.apollo.orders.internal.shaded.v11x.com.eastcom.apollo.orders.client.core.common.ServerInfo;
import com.eastcom.apollo.orders.internal.shaded.v11x.com.eastcom.apollo.orders.client.http.HttpClient;
import com.eastcom.apollo.orders.internal.shaded.v11x.com.eastcom.apollo.orders.client.http.HttpRequestConfig;
import com.eastcom.apollo.orders.internal.shaded.v11x.com.eastcom.apollo.orders.client.http.HttpResponse;
import com.eastcom.apollo.orders.internal.shaded.v11x.com.eastcom.apollo.orders.client.http.internal.RequestBodyUriSpec;
import com.eastcom.apollo.orders.internal.shaded.v11x.com.eastcom.apollo.orders.client.http.internal.SseListener;
import com.eastcom.apollo.orders.internal.shaded.v11x.com.eastcom.apollo.orders.commons.metadata.httpsession.OrderHttpSessionStrRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;
import java.util.List;
import java.util.LinkedHashMap;
import dev.openan.workflow.engine.client.WireLog;
import java.util.function.Predicate;

/**
 * Wraps the Eastcom Order SDK's officially documented {@code HttpClient} API.
 *
 * <p>Replaces the previous {@code StreamingOrderHttpSessionClient} which relied on the internal
 * RSocket-RPC {@code OrderHttpSessionClient} and directly accessed the vendor's shaded
 * {@code HttpSessionService} field. Requests use the public API surface described in the Eastcom
 * instruction-platform interface specification v1.8 (2026-01-27). Client construction additionally
 * installs the isolated {@link EastcomOrder118ByteBufWorkaround} required by the pinned vendor
 * version:
 * <ul>
 *   <li>{@code HttpClient.create(serverInfo, config)} - the HTTP flow documented by v1.8
 *   <li>{@code .post().uri(path).header(name, value).body(obj).send()} - synchronous HTTP
 *   <li>{@code .sendSse(listener)} - SSE streaming with onHeader/onBodyString
 *   <li>{@code .secure(spec -> ...)} - HTTPS with InsecureTrustManagerFactory
 * </ul>
 *
 * <p>The platform connection and NE binding are handled internally by the SDK via
 * {@code ServerInfo} (platform credentials) and {@code HttpRequestConfig.deviceName} (target NE).
 * The adapter does not call the JAR's undocumented static {@code HttpClient.login} helper. Its
 * conversation lifecycle is local resource management, not an {@code OrdersClientImpl}
 * login/logout session.
 */
final class OrderHttpClientAdapter implements OrderGatewayClientRuntime.OrderSession {
    private static final Logger log = LoggerFactory.getLogger(OrderHttpClientAdapter.class);

    private final HttpClient httpClient;
    private final String ne;

    /**
     * Creates an adapter bound to the given platform credentials and target NE.
     *
     * @param serverInfo platform login credentials (host, port, username, password, clientId, clientSecret)
     * @param ne target network element name (resolved by the platform to the actual device)
     * @param https whether the target device endpoint uses HTTPS
     */
    OrderHttpClientAdapter(ServerInfo serverInfo, String ne, boolean https) {
        this.ne = ne;
        HttpRequestConfig config = HttpRequestConfig.builder()
                .deviceName(ne)
                .build();
        this.httpClient = EastcomOrder118ByteBufWorkaround.createClient(serverInfo, config);
        // HTTPS to the target device is handled by the platform internally.
        // HttpClient.secure() would configure TLS on the RSocket transport and corrupt
        // the connection. Do not call .secure() here.
        log.info(
                "[OrderHttpClient] CREATED ne={}, https={}, host={}:{}",
                ne,
                https,
                serverInfo.getHost(),
                serverInfo.getPort());
    }

    /** Test-only constructor for injecting a pre-configured HttpClient (e.g. mock). */
    OrderHttpClientAdapter(HttpClient httpClient, String ne) {
        this.httpClient = httpClient;
        this.ne = ne;
    }

    @Override
    public OrderResponse execute(OrderHttpSessionStrRequest request, int timeoutMillis) {
        String requestId = java.util.UUID.randomUUID().toString();
        Map<String, String> trace = trace();
        try {
            RequestBodyUriSpec spec = prepareRequest(request, timeoutMillis);
            logRequest(requestId, request, trace);
            HttpResponse response = spec.send();
            OrderResponse result = new OrderResponse(response.statusCode(),
                    response.responseContent().asString(), responseHeaders(response.headers().entries()), "sdk-body");
            logResponse(requestId, request, result, trace);
            return result;
        } catch (RuntimeException error) {
            logFailure(requestId, request, trace, error);
            throw error;
        }
    }

    @Override
    public void executeStreaming(OrderHttpSessionStrRequest request, int timeoutMillis,
            Predicate<OrderResponse> responseSink) {
        String requestId = java.util.UUID.randomUUID().toString();
        Map<String, String> trace = trace();
        java.util.concurrent.atomic.AtomicInteger status = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<Map<String, List<String>>> headers =
                new java.util.concurrent.atomic.AtomicReference<>(Map.of());
        WireLog.Body observation = new WireLog.Body(true, true, frame ->
                WireLog.inContext(trace, () -> WireLog.record("ORDER_SDK_RESPONSE", "RESPONSE_BODY", requestId,
                        request.getUriPath(), request.getMethod(), status.get() == 0 ? null : status.get(),
                        Map.of(), "sdk-sse-text", frame[0],
                        "SDK string callbacks assembled as SSE; original byte encoding/OMC wire=unobserved; "
                                + frame[1])));
        boolean interrupted = true;
        try {
            RequestBodyUriSpec spec = prepareRequest(request, timeoutMillis);
            logRequest(requestId, request, trace);
            spec.sendSse(new SseListener() {
                @Override
                public void onHeader(HttpClientResponse response) {
                    status.set(response.status().code());
                    headers.set(responseHeaders(response.responseHeaders().entries()));
                    logResponse(requestId, request, new OrderResponse(status.get(), "", headers.get(), "sdk-headers"), trace);
                    if (status.get() < 200 || status.get() >= 300)
                        throw new IllegalStateException("SSE response error status: " + status.get());
                }

                @Override
                public void onBodyString(String content) {
                    if (status.get() == 0) throw new IllegalStateException("SDK delivered a body before response headers");
                    OrderResponse chunk = new OrderResponse(status.get(), content, headers.get(), "sdk-text-chunk");
                    observation.accept(java.nio.ByteBuffer.wrap(content.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                    responseSink.test(chunk);
                }

                @Override
                public java.lang.Boolean isOnString() { return true; }
            });
            interrupted = false;
        } catch (RuntimeException error) {
            logFailure(requestId, request, trace, error);
            throw error;
        } finally {
            observation.end(interrupted);
            WireLog.inContext(trace, () -> WireLog.record("ORDER_SDK_RESPONSE", "STREAM_EXIT", requestId,
                    request.getUriPath(), request.getMethod(), status.get() == 0 ? null : status.get(),
                    Map.of(), "sdk-lifecycle", "", "sendSse returned; OMC wire=unobserved"));
        }
    }

    private Map<String, String> trace() {
        Map<String, String> trace = new LinkedHashMap<>(WireLog.context());
        trace.put("NE", ne);
        trace.put("transport", "order");
        return Map.copyOf(trace);
    }

    private static Map<String, List<String>> responseHeaders(Iterable<Map.Entry<String, String>> entries) {
        Map<String, List<String>> result = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (var entry : entries) result.computeIfAbsent(entry.getKey(), key -> new java.util.ArrayList<>()).add(entry.getValue());
        return result;
    }

    private static void logRequest(String id, OrderHttpSessionStrRequest request, Map<String, String> trace) {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        request.getHeadersMap().forEach((key, value) -> headers.put(key, List.of(value)));
        WireLog.inContext(trace, () -> WireLog.record("ORDER_FORWARD_REQUEST", "REQUEST", id,
                request.getUriPath(), request.getMethod(), null, headers, "serialized-utf8", request.getBody(),
                "supplied to vendor HTTP SDK; platform credentials/OMC wire=unobserved"));
    }

    private static void logResponse(String id, OrderHttpSessionStrRequest request,
            OrderResponse response, Map<String, String> trace) {
        WireLog.inContext(trace, () -> WireLog.record("ORDER_SDK_RESPONSE", "RESPONSE", id,
                request.getUriPath(), request.getMethod(), response.status(), response.headers(),
                response.representation(), response.body(),
                "vendor callback; only SDK-delivered fields observed; original byte encoding/OMC wire=unobserved"));
    }

    private static void logFailure(String id, OrderHttpSessionStrRequest request,
            Map<String, String> trace, RuntimeException error) {
        WireLog.inContext(trace, () -> WireLog.record("ORDER_SDK_RESPONSE", "FAILURE", id,
                request.getUriPath(), request.getMethod(), null, Map.of(), "sdk-error", "",
                "errorType=" + error.getClass().getSimpleName()));
    }

    @Override
    public String sessionType() {
        return "OrderHttpClientAdapter";
    }

    /** HttpClient has no persistent session to close; the underlying connection pool is managed by Netty. */
    @Override
    public void close() {
        log.info("[OrderHttpClient] CLOSE ne={}", ne);
    }

    private RequestBodyUriSpec prepareRequest(
            OrderHttpSessionStrRequest request, int timeoutMillis) {
        httpClient.responseTimeout(Duration.ofMillis(timeoutMillis));
        String method = request.getMethod();
        RequestBodyUriSpec spec;
        if (method == null || method.isBlank() || "POST".equalsIgnoreCase(method)) {
            spec = httpClient.post();
        } else if ("GET".equalsIgnoreCase(method)) {
            spec = httpClient.get();
        } else if ("PUT".equalsIgnoreCase(method)) {
            spec = httpClient.put();
        } else if ("DELETE".equalsIgnoreCase(method)) {
            spec = httpClient.delete();
        } else {
            throw new IllegalArgumentException("Unsupported forwarded HTTP method: " + method);
        }
        spec.uri(request.getUriPath());
        Map<String, String> headers = request.getHeadersMap();
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                if (entry.getKey() == null) {
                    continue;
                }
                spec.header(entry.getKey(), entry.getValue());
            }
        }
        String body = request.getBody();
        if (body != null && !body.isEmpty()) {
            spec.body(body);
        }
        return spec;
    }
}
