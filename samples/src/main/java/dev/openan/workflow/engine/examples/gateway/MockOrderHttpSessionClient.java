/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 *    Licensed under the Apache License, Version 2.0 (the "License"); you may
 *    not use this file except in compliance with the License. You may obtain
 *    a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *    WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *    License for the specific language governing permissions and limitations
 *    under the License.
 */

package dev.openan.workflow.engine.examples.gateway;

import com.eastcom.apollo.orders.internal.shaded.v11x.com.eastcom.apollo.orders.commons.metadata.httpsession.OrderHttpSessionStrRequest;

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
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * Mock {@link OrderGatewayClientRuntime.OrderSession} that bypasses the Eastcom SDK entirely
 * and does plain HTTP forwarding to the mock gateway server.
 *
 * <p>Previously this class extended {@code OrderHttpSessionClient} and overrode
 * {@code login/init/execute}. After migrating to the officially documented
 * {@code HttpClient} API, the production adapter no longer uses
 * {@code OrderHttpSessionClient}, so this mock now implements {@link
 * OrderGatewayClientRuntime.OrderSession} directly.
 *
 * <h2>Flow</h2>
 * <pre>
 *   MockGatewayClientRuntime  ->  MockOrderHttpSessionClient  ->  MockGatewayServer  ->  OMC
 *        (A2A-T)                   (implements OrderSession)       (HTTP proxy)         (A2A server)
 * </pre>
 *
 * <p>The {@code targetUrl} (set via constructor) stores the target OMC URL.
 * The {@code execute()} call forwards the HTTP request to the mock gateway with an
 * {@code X-Target-URL} header, and the gateway pipes it to the OMC.
 */
public class MockOrderHttpSessionClient implements OrderGatewayClientRuntime.OrderSession {

    private static final Logger log = LoggerFactory.getLogger(MockOrderHttpSessionClient.class);

    private final String gatewayUrl;
    private final SSLContext sslContext;
    private final String targetUrl;

    public MockOrderHttpSessionClient(String gatewayUrl, SSLContext sslContext, String targetUrl) {
        this.gatewayUrl = gatewayUrl.endsWith("/")
                ? gatewayUrl.substring(0, gatewayUrl.length() - 1)
                : gatewayUrl;
        this.sslContext = sslContext;
        this.targetUrl = targetUrl;
    }

    @Override
    public OrderResponse execute(
            OrderHttpSessionStrRequest request, int timeoutMillis) {
        long started = System.nanoTime();
        String uriPath = request.getUriPath();
        String method = request.getMethod();
        String body = request.getBody();
        Map<String, String> headers = request.getHeadersMap();

        String url = gatewayUrl + (uriPath != null ? uriPath : "");
        log.info(
                "[MockOrderClient] EXECUTE_START method={}, uriPath={}, gatewayUrl={}, "
                        + "targetUrl={}, timeoutMs={}, bodyChars={}, headerNames={}",
                method,
                uriPath,
                gatewayUrl,
                targetUrl,
                timeoutMillis,
                body != null ? body.length() : 0,
                headers != null ? headers.keySet() : java.util.Set.of());

        try {
            HttpURLConnection conn = openConnection(url);
            conn.setRequestMethod(method != null && !method.isEmpty() ? method : "POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(30_000);
            conn.setReadTimeout(timeoutMillis);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("X-Target-URL", targetUrl);

            if (headers != null) {
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    String key = entry.getKey();
                    if (key == null) continue;
                    String lowerKey = key.toLowerCase(Locale.ROOT);
                    if (lowerKey.equals("content-type") || lowerKey.equals("x-target-url"))
                        continue;
                    conn.setRequestProperty(key, entry.getValue());
                }
            }

            if (body != null && !body.isEmpty()) {
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.getBytes(StandardCharsets.UTF_8));
                }
            }

            int status = conn.getResponseCode();
            String responseBody = readAll(
                    status >= 400 ? conn.getErrorStream() : conn.getInputStream());
            log.info(
                    "[MockOrderClient] EXECUTE_DONE status={}, responseChars={}, elapsedMs={}",
                    status,
                    responseBody.length(),
                    elapsedMillis(started));

            return new OrderResponse(status, responseBody, Map.of(), "mock-raw-data");
        } catch (Exception e) {
            log.error(
                    "[MockOrderClient] EXECUTE_FAILED elapsedMs={}, errorType={}, message={}",
                    elapsedMillis(started),
                    e.getClass().getSimpleName(),
                    e.getMessage(),
                    e);
            return new OrderResponse(500, "Error: " + e.getMessage(), Map.of(), "mock-raw-data");
        }
    }

    /** Streams response characters to the sink as soon as the mock platform forwards them. */
    @Override
    public void executeStreaming(
            OrderHttpSessionStrRequest request,
            int timeoutMillis,
            Predicate<OrderResponse> responseSink) {
        long started = System.nanoTime();
        HttpURLConnection conn = null;
        int chunks = 0;
        try {
            conn = prepareConnection(request, timeoutMillis);
            int status = conn.getResponseCode();
            if (status >= 400) {
                String errorBody = readAll(conn.getErrorStream());
                responseSink.test(response(status, errorBody));
                return;
            }
            try (InputStreamReader reader =
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8)) {
                char[] buffer = new char[1024];
                int length;
                while ((length = reader.read(buffer)) != -1) {
                    chunks++;
                    String chunk = new String(buffer, 0, length);
                    log.debug(
                            "[MockOrderClient] STREAM_CHUNK index={}, chars={}, elapsedMs={}",
                            chunks,
                            chunk.length(),
                            elapsedMillis(started));
                    if (responseSink.test(response(status, chunk))) {
                        break;
                    }
                }
            }
            log.info(
                    "[MockOrderClient] STREAM_DONE chunks={}, elapsedMs={}",
                    chunks,
                    elapsedMillis(started));
        } catch (Exception e) {
            throw new IllegalStateException("Mock Order streaming failed: " + e.getMessage(), e);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    @Override
    public String sessionType() {
        return "MockOrderHttpSessionClient";
    }

    @Override
    public void close() {
        log.info("[MockOrderClient] CLOSE targetUrl={}", targetUrl);
    }

    private HttpURLConnection prepareConnection(
            OrderHttpSessionStrRequest request, int timeoutMillis) throws IOException {
        String uriPath = request.getUriPath();
        HttpURLConnection conn =
                openConnection(gatewayUrl + (uriPath != null ? uriPath : ""));
        String method = request.getMethod();
        conn.setRequestMethod(method != null && !method.isEmpty() ? method : "POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(30_000);
        conn.setReadTimeout(timeoutMillis);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("X-Target-URL", targetUrl);
        for (Map.Entry<String, String> entry : request.getHeadersMap().entrySet()) {
            String key = entry.getKey();
            if (key == null) {
                continue;
            }
            String lowerKey = key.toLowerCase(Locale.ROOT);
            if (lowerKey.equals("content-type") || lowerKey.equals("x-target-url")) {
                continue;
            }
            conn.setRequestProperty(key, entry.getValue());
        }
        String body = request.getBody();
        if (body != null && !body.isEmpty()) {
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        return conn;
    }

    private static OrderResponse response(int status, String body) {
        return new OrderResponse(status, body, Map.of(), "mock-raw-data");
    }

    private HttpURLConnection openConnection(String urlStr) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI.create(urlStr).toURL().openConnection();
        if (conn instanceof HttpsURLConnection httpsConn && sslContext != null) {
            httpsConn.setSSLSocketFactory(sslContext.getSocketFactory());
            httpsConn.setHostnameVerifier((hostname, session) -> true);
        }
        return conn;
    }

    private static String readAll(InputStream is) throws IOException {
        if (is == null) return "";
        return new String(is.readAllBytes(), StandardCharsets.UTF_8);
    }

    private static long elapsedMillis(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }
}
