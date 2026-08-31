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

import com.google.protobuf.util.JsonFormat;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.openan.workflow.engine.client.SslContextFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import org.a2aproject.sdk.grpc.SendMessageRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mock instruction-center (Gateway) server for the end-to-end demo.
 *
 * <p>This is NOT an A2A agent -- it is a simple HTTP reverse proxy that simulates the forwarding
 * behaviour of the Eastcom Order gateway platform. The real gateway accepts requests via its SDK
 * ({@code OrderHttpSessionClient}), authenticates the caller, and transparently forwards HTTP
 * traffic to the target OMC. This mock replaces the SDK with plain HTTP: the caller sends a POST
 * with an {@code X-Target-URL} header telling the gateway where to forward, and the gateway pipes
 * the response back.
 *
 * <h2>Supported routes</h2>
 *
 * <ul>
 *   <li>{@code POST /message:stream} -- forwards to {@code {target-url}/message:stream}, pipes the
 *       SSE response back in real time.
 *   <li>{@code POST /message:send} -- forwards to {@code {target-url}/message:send}, returns the
 *       JSON body as-is.
 * </ul>
 *
 * <h2>Usage</h2>
 *
 * <pre>{@code
 * MockGatewayServer gateway = new MockGatewayServer("127.0.0.1", 26400);
 * gateway.start();
 * // ... demo runs ...
 * gateway.close();
 * }</pre>
 *
 * <p>Pair with {@code MockGatewayClientRuntime} on the caller side.
 */
public class MockGatewayServer implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(MockGatewayServer.class);

  private static final Set<String> HOP_BY_HOP =
      Set.of(
          "connection",
          "keep-alive",
          "proxy-authenticate",
          "proxy-authorization",
          "te",
          "trailers",
          "transfer-encoding",
          "upgrade",
          "host",
          "content-length",
          "x-target-url");

  private final HttpServer server;
  private final ExecutorService executor;
  private final SSLContext sslContext;
  private final int port;
  private final Set<String> allowedTargets;

  public MockGatewayServer(String host, int port) throws IOException {
    this(host, port, Set.of());
  }

  /** Creates a mock gateway which only forwards to the supplied A2A base URLs. */
  public MockGatewayServer(String host, int port, Set<String> allowedTargets) throws IOException {
    this.port = port;
    this.allowedTargets =
        allowedTargets == null
            ? Set.of()
            : allowedTargets.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(MockGatewayServer::withoutTrailingSlash)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    this.sslContext = SslContextFactory.createTrustAll();
    this.executor =
        Executors.newFixedThreadPool(
            8,
            r -> {
              Thread t = new Thread(r, "mock-gateway");
              t.setDaemon(true);
              return t;
            });
    this.server = HttpServer.create(new InetSocketAddress(host, port), 0);
    this.server.setExecutor(executor);
    this.server.createContext("/message:stream", this::handleStream);
    this.server.createContext("/message:send", this::handleSend);
    this.server.createContext("/a2a/json/message:stream", this::handleStream);
    this.server.createContext("/a2a/json/message:send", this::handleSend);
    this.server.createContext("/", this::handleRoot);
    log.info(
        "[MockGateway] HTTP server created on http://{}:{}/, allowedTargets={}",
        host,
        port,
        this.allowedTargets);
  }

  private static String readBody(HttpExchange exchange) throws IOException {
    return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
  }

  private static String readAll(InputStream is) throws IOException {
    if (is == null) return "";
    return new String(is.readAllBytes(), StandardCharsets.UTF_8);
  }

  private static void sendError(HttpExchange exchange, int status, String message)
      throws IOException {
    byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "text/plain");
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream os = exchange.getResponseBody()) {
      os.write(bytes);
    }
    exchange.close();
  }

  private static long elapsedMillis(long startedNanos) {
    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
  }

  private static String withoutTrailingSlash(String value) {
    return value.length() > 1 && value.endsWith("/")
        ? value.substring(0, value.length() - 1)
        : value;
  }

  public void start() {
    server.start();
    log.info("[MockGateway] Started -- ready to forward A2A requests");
  }

  // ------------------------------------------------------------------
  // Connection helpers
  // ------------------------------------------------------------------

  @Override
  public void close() {
    log.info("[MockGateway] Stopping...");
    server.stop(1);
    executor.shutdownNow();
    try {
      executor.awaitTermination(2, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    log.info("[MockGateway] Stopped");
  }

  public int getPort() {
    return port;
  }

  private void handleRoot(HttpExchange exchange) throws IOException {
    String method = exchange.getRequestMethod();
    if ("GET".equalsIgnoreCase(method)) {
      String body = "{\"service\":\"mock-gateway\",\"status\":\"running\"}";
      byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, bytes.length);
      try (OutputStream os = exchange.getResponseBody()) {
        os.write(bytes);
      }
    } else {
      exchange.sendResponseHeaders(404, -1);
    }
    exchange.close();
  }

  /** Forward POST /message:stream to the target OMC and pipe the SSE response back. */
  private void handleStream(HttpExchange exchange) throws IOException {
    long started = System.nanoTime();
    ForwardRequest request = validateRequest(exchange, "/message:stream", "text/event-stream");
    if (request == null) {
      return;
    }
    String targetUrl = request.targetUrl();
    String path = exchange.getRequestURI().getPath();
    String body = request.body();
    Map<String, List<String>> headers = exchange.getRequestHeaders();

    String forwardUrl = targetUrl + path;
    log.info(
        "[MockGateway] FORWARD_START mode=stream, method={}, path={}, target={}, bodyChars={}",
        exchange.getRequestMethod(),
        path,
        forwardUrl,
        body.length());

    HttpURLConnection conn = openConnection(forwardUrl);
    copyRequestHeaders(headers, conn);
    conn.setRequestMethod("POST");
    conn.setDoOutput(true);
    conn.setRequestProperty("Content-Type", "application/json");
    try (OutputStream os = conn.getOutputStream()) {
      os.write(body.getBytes(StandardCharsets.UTF_8));
    }

    int status = conn.getResponseCode();
    String contentType = conn.getContentType();
    log.info(
        "[MockGateway] UPSTREAM_HEADERS mode=stream, target={}, status={}, "
            + "contentType={}, elapsedMs={}",
        forwardUrl,
        status,
        contentType,
        elapsedMillis(started));

    if (status != 200) {
      String errorBody =
          readAll(conn.getErrorStream() != null ? conn.getErrorStream() : conn.getInputStream());
      sendError(exchange, status, errorBody);
      return;
    }

    exchange
        .getResponseHeaders()
        .set("Content-Type", contentType != null ? contentType : "text/event-stream");
    exchange.sendResponseHeaders(200, 0);

    try (InputStream is = conn.getInputStream();
        OutputStream os = exchange.getResponseBody()) {
      byte[] buffer = new byte[4096];
      int len;
      while ((len = is.read(buffer)) != -1) {
        os.write(buffer, 0, len);
        os.flush();
      }
    } catch (IOException e) {
      log.debug("[MockGateway] Stream pipe ended: {}", e.getMessage());
    } finally {
      exchange.close();
      log.info(
          "[MockGateway] FORWARD_DONE mode=stream, target={}, elapsedMs={}",
          forwardUrl,
          elapsedMillis(started));
    }
  }

  /** Forward POST /message:send to the target OMC and return the JSON response. */
  private void handleSend(HttpExchange exchange) throws IOException {
    long started = System.nanoTime();
    ForwardRequest request = validateRequest(exchange, "/message:send", "application/json");
    if (request == null) {
      return;
    }
    String targetUrl = request.targetUrl();
    String path = exchange.getRequestURI().getPath();
    String body = request.body();
    Map<String, List<String>> headers = exchange.getRequestHeaders();

    String forwardUrl = targetUrl + path;
    log.info(
        "[MockGateway] FORWARD_START mode=send, method={}, path={}, target={}, bodyChars={}",
        exchange.getRequestMethod(),
        path,
        forwardUrl,
        body.length());

    HttpURLConnection conn = openConnection(forwardUrl);
    copyRequestHeaders(headers, conn);
    conn.setRequestMethod("POST");
    conn.setDoOutput(true);
    conn.setRequestProperty("Content-Type", "application/json");
    try (OutputStream os = conn.getOutputStream()) {
      os.write(body.getBytes(StandardCharsets.UTF_8));
    }

    int status = conn.getResponseCode();
    String contentType = conn.getContentType();
    log.info(
        "[MockGateway] UPSTREAM_HEADERS mode=send, target={}, status={}, "
            + "contentType={}, elapsedMs={}",
        forwardUrl,
        status,
        contentType,
        elapsedMillis(started));

    InputStream responseStream = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
    String responseBody = readAll(responseStream);

    byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
    exchange
        .getResponseHeaders()
        .set("Content-Type", contentType != null ? contentType : "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream os = exchange.getResponseBody()) {
      os.write(bytes);
    }
    exchange.close();
    log.info(
        "[MockGateway] FORWARD_DONE mode=send, target={}, responseChars={}, elapsedMs={}",
        forwardUrl,
        responseBody.length(),
        elapsedMillis(started));
  }

  private ForwardRequest validateRequest(
      HttpExchange exchange, String expectedPath, String expectedAccept) throws IOException {
    if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
      sendError(exchange, 405, "Only POST is supported");
      return null;
    }
    String actualPath = exchange.getRequestURI().getPath();
    if (!actualPath.endsWith(expectedPath)) {
      sendError(exchange, 404, "Unsupported gateway path: " + actualPath);
      return null;
    }
    String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
    if (contentType == null
        || !contentType.toLowerCase(Locale.ROOT).startsWith("application/json")) {
      sendError(exchange, 415, "Content-Type must be application/json");
      return null;
    }
    String accept = exchange.getRequestHeaders().getFirst("Accept");
    if (accept == null || !accept.toLowerCase(Locale.ROOT).contains(expectedAccept)) {
      sendError(exchange, 406, "Accept must include " + expectedAccept);
      return null;
    }
    String version = exchange.getRequestHeaders().getFirst("A2A-Version");
    if (version == null || version.isBlank()) {
      sendError(exchange, 400, "Missing A2A-Version header");
      return null;
    }
    String targetUrl = exchange.getRequestHeaders().getFirst("X-Target-URL");
    if (!isAllowedTarget(targetUrl)) {
      sendError(exchange, 403, "Target URL is not registered by the mock platform");
      return null;
    }
    String body = readBody(exchange);
    try {
      SendMessageRequest.Builder parsed = SendMessageRequest.newBuilder();
      JsonFormat.parser().merge(body, parsed);
      if (!parsed.hasMessage()) {
        sendError(exchange, 400, "A2A request must contain a message");
        return null;
      }
    } catch (Exception e) {
      sendError(exchange, 400, "Invalid A2A SendMessageRequest JSON: " + e.getMessage());
      return null;
    }
    String normalizedTarget = withoutTrailingSlash(targetUrl);
    log.info(
        "[MockGateway] VALIDATION_PASSED mode={}, target={}, a2aVersion={}, bodyChars={}",
        expectedPath.endsWith("stream") ? "stream" : "send",
        normalizedTarget,
        version,
        body.length());
    return new ForwardRequest(normalizedTarget, body);
  }

  private boolean isAllowedTarget(String targetUrl) {
    if (targetUrl == null || targetUrl.isBlank()) {
      return false;
    }
    URI uri;
    try {
      uri = URI.create(targetUrl);
    } catch (IllegalArgumentException e) {
      return false;
    }
    if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
      return false;
    }
    if (uri.getHost() == null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
      return false;
    }
    if (allowedTargets.isEmpty()) {
      return true;
    }
    String normalized = withoutTrailingSlash(targetUrl);
    return allowedTargets.stream()
        .anyMatch(allowed -> normalized.equals(allowed) || normalized.startsWith(allowed + "/"));
  }

  private HttpURLConnection openConnection(String urlStr) throws IOException {
    URL url = URI.create(urlStr).toURL();
    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
    if (conn instanceof HttpsURLConnection httpsConn) {
      httpsConn.setSSLSocketFactory(sslContext.getSocketFactory());
      httpsConn.setHostnameVerifier((hostname, session) -> true);
    }
    conn.setConnectTimeout(30_000);
    conn.setReadTimeout(600_000);
    return conn;
  }

  private void copyRequestHeaders(Map<String, List<String>> src, HttpURLConnection dst) {
    for (Map.Entry<String, List<String>> entry : src.entrySet()) {
      String key = entry.getKey();
      if (key == null) continue;
      if (HOP_BY_HOP.contains(key.toLowerCase(Locale.ROOT))) continue;
      for (String value : entry.getValue()) {
        dst.setRequestProperty(key, value);
      }
    }
  }

  private record ForwardRequest(String targetUrl, String body) {}
}
