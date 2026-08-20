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
import com.eastcom.apollo.orders.internal.shaded.v11x.com.eastcom.apollo.orders.commons.metadata.httpsession.OrderHttpSessionStrResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Wraps the Eastcom Order SDK's officially documented {@code HttpClient} API.
 *
 * <p>Replaces the previous {@code StreamingOrderHttpSessionClient} which relied on the internal
 * RSocket-RPC {@code OrderHttpSessionClient} and directly accessed the vendor's shaded
 * {@code HttpSessionService} field. This adapter uses only the public API surface described in
 * the Eastcom instruction-platform interface specification v1.8 (2026-01-27):
 * <ul>
 *   <li>{@code HttpClient.create(serverInfo, config)} - no explicit login/init/logout
 *   <li>{@code .post().uri(path).header(name, value).body(obj).send()} - synchronous HTTP
 *   <li>{@code .sendSse(listener)} - SSE streaming with onHeader/onBodyString
 *   <li>{@code .secure(spec -> ...)} - HTTPS with InsecureTrustManagerFactory
 * </ul>
 *
 * <p>The platform connection and NE binding are handled internally by the SDK via
 * {@code ServerInfo} (platform credentials) and {@code HttpRequestConfig.deviceName} (target NE).
 * There is no persistent session lifecycle to manage - each request is self-contained.
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
        this.httpClient = HttpClient.create(serverInfo, config);
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
    public OrderHttpSessionStrResponse execute(
            OrderHttpSessionStrRequest request, int timeoutMillis) {
        RequestBodyUriSpec spec = prepareRequest(request, timeoutMillis);
        HttpResponse response = spec.send();
        int status = response.statusCode();
        String body = response.responseContent().asString();
        log.info(
                "[OrderHttpClient] EXECUTE_DONE ne={}, status={}, bodyChars={}",
                ne,
                status,
                body != null ? body.length() : 0);
        return OrderHttpSessionStrResponse.newBuilder()
                .setStatus(status)
                .setBody(body != null ? body : "")
                .build();
    }

    @Override
    public void executeStreaming(
            OrderHttpSessionStrRequest request,
            int timeoutMillis,
            Predicate<OrderHttpSessionStrResponse> responseSink) {
        RequestBodyUriSpec spec = prepareRequest(request, timeoutMillis);
        spec.sendSse(new SseListener() {
            @Override
            public void onHeader(HttpClientResponse r) {
                int code = r.status().code();
                if (code != 200) {
                    throw new IllegalStateException(
                            "SSE response error status: " + code);
                }
            }

            @Override
            public void onBodyString(String content) {
                OrderHttpSessionStrResponse chunk = OrderHttpSessionStrResponse.newBuilder()
                        .setStatus(200)
                        .setBody(content != null ? content : "")
                        .build();
                responseSink.test(chunk);
            }

            @Override
            public java.lang.Boolean isOnString() {
                return true;
            }
        });
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
            spec = httpClient.post();
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
