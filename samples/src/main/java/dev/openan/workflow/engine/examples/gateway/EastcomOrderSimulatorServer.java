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
import com.eastcom.apollo.orders.internal.shaded.io.rsocket.ConnectionSetupPayload;
import com.eastcom.apollo.orders.internal.shaded.io.rsocket.DuplexConnection;
import com.eastcom.apollo.orders.internal.shaded.io.rsocket.RSocket;
import com.eastcom.apollo.orders.internal.shaded.reactor.core.Disposable;
import com.eastcom.apollo.orders.internal.shaded.reactor.core.publisher.Flux;
import com.eastcom.apollo.orders.internal.shaded.reactor.core.publisher.Mono;
import com.eastcom.apollo.orders.internal.shaded.reactor.core.scheduler.Schedulers;
import com.eastcom.apollo.orders.internal.shaded.v11x.com.eastcom.apollo.order.transport.rpc.RpcServer;
import com.eastcom.apollo.orders.internal.shaded.v11x.com.eastcom.apollo.order.transport.rpc.Transport;
import com.eastcom.apollo.orders.internal.shaded.v11x.com.eastcom.apollo.order.transport.rpc.connection.RSocketConnectionListener;
import com.eastcom.apollo.orders.internal.shaded.v11x.com.eastcom.apollo.orders.commons.metadata.AuthRequest;
import com.eastcom.apollo.orders.internal.shaded.v11x.com.eastcom.apollo.orders.commons.metadata.AuthResponse;
import com.eastcom.apollo.orders.internal.shaded.v11x.com.eastcom.apollo.orders.commons.metadata.Code;
import com.eastcom.apollo.orders.internal.shaded.v11x.com.eastcom.apollo.orders.commons.metadata.DisconnectRequest;
import com.eastcom.apollo.orders.internal.shaded.v11x.com.eastcom.apollo.orders.commons.metadata.HttpServerBindRequest;
import com.eastcom.apollo.orders.internal.shaded.v11x.com.eastcom.apollo.orders.commons.metadata.HttpServerBindResponse;
import com.eastcom.apollo.orders.internal.shaded.v11x.com.eastcom.apollo.orders.commons.metadata.HttpServerUnbindRequest;
import com.eastcom.apollo.orders.internal.shaded.v11x.com.eastcom.apollo.orders.commons.metadata.HttpServerUnbindResponse;
import com.eastcom.apollo.orders.internal.shaded.v11x.com.eastcom.apollo.orders.commons.metadata.OrderHttpRequest;
import com.eastcom.apollo.orders.internal.shaded.v11x.com.eastcom.apollo.orders.commons.metadata.OrderHttpResponse;
import com.eastcom.apollo.orders.internal.shaded.v11x.com.eastcom.apollo.orders.commons.metadata.OrderResourceRequest;
import com.eastcom.apollo.orders.internal.shaded.v11x.com.eastcom.apollo.orders.commons.metadata.OrderResourceResponse;
import dev.openan.workflow.engine.client.SslContextFactory;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Local protocol simulator for Eastcom Order SDK 1.1.18.
 *
 * <p>Unlike the adapter-level HTTP mock, this server speaks the vendor's real RSocket RPC protocol.
 * The {@code HttpClient} API performs login, loadNeResource, execute and logout via the {@code
 * HttpService} RSocket interface. The simulator resolves the requested NE and forwards the embedded
 * A2A HTTP request to a local agent.
 *
 * <p>This class deliberately lives in the samples module: it verifies SDK compatibility but is not
 * a production instruction-platform implementation.
 */
public final class EastcomOrderSimulatorServer implements AutoCloseable {
  private static final Logger log = LoggerFactory.getLogger(EastcomOrderSimulatorServer.class);
  private static final Pattern A2A_TERMINAL_STATE =
      Pattern.compile(
          "\\\"state\\\"\\s*:\\s*\\\"TASK_STATE_(?:COMPLETED|FAILED|CANCELED|REJECTED|INPUT_REQUIRED|AUTH_REQUIRED)\\\"");
  private static final Pattern A2A_FINAL_EVENT = Pattern.compile("\\\"isFinal\\\"\\s*:\\s*true");
  private static final Pattern RECOVERY_RESULT_ARTIFACT =
      Pattern.compile("\\\"(?:name|artifactId)\\\"\\s*:\\s*\\\"recovery-result\\\"");
  private static final AtomicBoolean legacyRequestEncodingLogged = new AtomicBoolean();

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
    this(host, port, username, password, clientId, clientSecret, neTargets, 30_000, 30_000);
  }

  public EastcomOrderSimulatorServer(
      String host,
      int port,
      String username,
      String password,
      String clientId,
      String clientSecret,
      Map<String, String> neTargets,
      int connectTimeoutMillis,
      int readTimeoutMillis) {
    this(
        host,
        port,
        username,
        password,
        clientId,
        clientSecret,
        neTargets,
        connectTimeoutMillis,
        readTimeoutMillis,
        Map.of());
  }

  /**
   * Configures simulator-managed OMC credentials by NE, separately from platform authentication.
   */
  public EastcomOrderSimulatorServer(
      String host,
      int port,
      String username,
      String password,
      String clientId,
      String clientSecret,
      Map<String, String> neTargets,
      int connectTimeoutMillis,
      int readTimeoutMillis,
      Map<String, NeCredentials> neCredentials) {
    dev.openan.workflow.engine.examples.server.LocalServerAddress.requireLocalHost(
        host, "a2a.order.host (simulator bind address)");
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
            normalizeTargets(neTargets),
            resolveCredentials(neTargets, neCredentials),
            positiveTimeout(connectTimeoutMillis, "connectTimeoutMillis"),
            positiveTimeout(readTimeoutMillis, "readTimeoutMillis"));
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

  private static Map<String, NeCredentials> resolveCredentials(
      Map<String, String> targets, Map<String, NeCredentials> configured) {
    if (!targets.keySet().containsAll(configured.keySet())) {
      throw new IllegalArgumentException("Simulator credentials reference an unconfigured NE");
    }
    Map<String, NeCredentials> result = new LinkedHashMap<>();
    targets
        .keySet()
        .forEach(
            ne ->
                result.put(
                    ne, configured.getOrDefault(ne, new NeCredentials("admin", "Admin@123"))));
    return Map.copyOf(result);
  }

  static String substituteCredentials(String body, NeCredentials credentials) {
    if (!body.contains("${ne:")) return body;
    try {
      var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
      var json = mapper.readTree(body);
      return mapper.writeValueAsString(substituteNode(json, credentials));
    } catch (Exception e) {
      // Never attach the parser exception: its source excerpt may contain a login password.
      throw new IllegalArgumentException("Simulator NE placeholders require a valid JSON body");
    }
  }

  private static String withoutTrailingSlash(String value) {
    return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
  }

  private static String emptyToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  private static int positiveTimeout(int value, String name) {
    if (value <= 0) {
      throw new IllegalArgumentException(name + " must be positive");
    }
    return value;
  }

  static String decodeRequestData(ByteString data) {
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(data.asReadOnlyByteBuffer())
          .toString();
    } catch (CharacterCodingException invalidUtf8) {
      // Eastcom SDK 1.1.18 encodes String request bodies with the JVM default charset.
      // On Windows/JDK 17 that is commonly GBK. The wire model carries no charset, so
      // the local simulator must recover this legacy encoding.
      if (legacyRequestEncodingLogged.compareAndSet(false, true)) {
        log.warn(
            "[EastcomSimulator] REQUEST_ENCODING_FALLBACK charset=GB18030, reason=invalid_utf8");
      }
      return new String(data.toByteArray(), Charset.forName("GB18030"));
    }
  }

  private static com.fasterxml.jackson.databind.JsonNode substituteNode(
      com.fasterxml.jackson.databind.JsonNode node, NeCredentials credentials) {
    if (node.isTextual()) {
      return com.fasterxml.jackson.databind.node.TextNode.valueOf(
          node.textValue()
              .replace("${ne:grantType}", "password")
              .replace("${ne:username}", credentials.username())
              .replace("${ne:password}", credentials.password()));
    }
    if (node instanceof com.fasterxml.jackson.databind.node.ObjectNode object) {
      object
          .properties()
          .forEach(
              entry -> object.set(entry.getKey(), substituteNode(entry.getValue(), credentials)));
    } else if (node instanceof com.fasterxml.jackson.databind.node.ArrayNode array) {
      for (int i = 0; i < array.size(); i++)
        array.set(i, substituteNode(array.get(i), credentials));
    }
    return node;
  }

  /** Simulator-only managed credentials. Passwords may use the existing enc: credential format. */
  public record NeCredentials(String username, String password) {
    public NeCredentials {
      if (username == null || username.isBlank() || password == null || password.isBlank()) {
        throw new IllegalArgumentException(
            "Simulator NE username and password must both be non-blank");
      }
      password = dev.openan.workflow.engine.client.CredentialCrypto.decryptIfNeeded(password);
      if (password.isBlank()) {
        throw new IllegalArgumentException("Simulator NE password must not be blank");
      }
    }

    @Override
    public String toString() {
      return "NeCredentials[username=***, password=***]";
    }
  }

  private static long elapsedMillis(long startedNanos) {
    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
  }

  /**
   * Whether the exception reports the target closing the chunked response mid-stream. JDK
   * HttpURLConnection surfaces a server-side close of a chunked body as {@code IOException:
   * Premature EOF}; for a streaming forward this is a stream termination, not a protocol failure.
   */
  private static boolean isTargetClosedMidStream(Exception error) {
    if (!(error instanceof java.io.IOException)) return false;
    String message = error.getMessage();
    return message != null && message.contains("Premature EOF");
  }

  public synchronized void start() {
    if (server != null) {
      return;
    }
    service.prepareStart();
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
    service.beginShutdown();
    service.awaitForwardShutdown();
    connectionLogger.closeAll();
    server.stop();
    server = null;
    service.clear();
    log.info("[EastcomSimulator] STOPPED host={}:{}", host, port);
  }

  private static final class SimulatorConnectionLogger implements RSocketConnectionListener {
    private final java.util.Set<RSocket> connections = ConcurrentHashMap.newKeySet();

    @Override
    public void connectionCreated(
        RSocket socket, ConnectionSetupPayload setup, DuplexConnection connection) {
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
        RSocket socket, ConnectionSetupPayload setup, DuplexConnection connection) {
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
    private final Map<String, NeCredentials> neCredentials;
    private final AtomicBoolean shuttingDown = new AtomicBoolean();
    private final java.util.Set<HttpURLConnection> activeForwardConnections =
        ConcurrentHashMap.newKeySet();
    private final Object forwardShutdownMonitor = new Object();
    private final Map<String, SessionState> sessions = new ConcurrentHashMap<>();
    private final SSLContext sslContext = SslContextFactory.createTrustAll();
    private final int connectTimeoutMillis;
    private final int readTimeoutMillis;

    private SimulatorService(
        String username,
        String password,
        String clientId,
        String clientSecret,
        Map<String, String> neTargets,
        Map<String, NeCredentials> neCredentials,
        int connectTimeoutMillis,
        int readTimeoutMillis) {
      this.username = Objects.requireNonNull(username, "username");
      this.password = Objects.requireNonNull(password, "password");
      this.clientId = clientId;
      this.clientSecret = clientSecret;
      this.neTargets = neTargets;
      this.neCredentials = neCredentials;
      this.connectTimeoutMillis = connectTimeoutMillis;
      this.readTimeoutMillis = readTimeoutMillis;
    }

    private static void copyHeaders(Map<String, String> headers, HttpURLConnection connection) {
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

    private static OrderHttpResponse responseWithHeader(String body) {
      return responseWithHeader(200, Map.of(), body);
    }

    private static OrderHttpResponse responseWithHeader(
        int status, Map<String, List<String>> headers, String body) {
      // The SDK feeds OrderHttpResponse.data through Netty's HttpResponseDecoder.
      // The first chunk must include HTTP status line + headers.
      // Use UTF-8 byte length for chunk size, not String char length.
      byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
      StringBuilder withHeader =
          new StringBuilder("HTTP/1.1 ")
              .append(status)
              .append(status == 200 ? " OK\r\n" : " Response\r\n");
      boolean hasContentType = false;
      for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
        String name = entry.getKey();
        if (name == null) {
          continue;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        if (List.of("content-length", "transfer-encoding", "connection").contains(lower)) {
          continue;
        }
        hasContentType |= "content-type".equals(lower);
        for (String value : entry.getValue()) {
          withHeader.append(name).append(": ").append(value).append("\r\n");
        }
      }
      if (!hasContentType) {
        withHeader.append("Content-Type: application/json\r\n");
      }
      withHeader
          .append("Transfer-Encoding: chunked\r\n")
          .append("\r\n")
          .append(Integer.toHexString(bodyBytes.length))
          .append("\r\n")
          .append(body)
          .append("\r\n");
      return OrderHttpResponse.newBuilder()
          .setData(ByteString.copyFromUtf8(withHeader.toString()))
          .build();
    }

    private String substituteManagedNeCredentials(String ne, String body) {
      return substituteCredentials(body, neCredentials.get(ne));
    }

    private static OrderHttpResponse responseChunk(String body) {
      // Subsequent chunks: use UTF-8 byte length for chunk size
      byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
      String chunk = Integer.toHexString(bodyBytes.length) + "\r\n" + body + "\r\n";
      return OrderHttpResponse.newBuilder().setData(ByteString.copyFromUtf8(chunk)).build();
    }

    private static OrderHttpResponse responseEnd() {
      // Final chunk: zero-length chunk to signal end
      return OrderHttpResponse.newBuilder().setData(ByteString.copyFromUtf8("0\r\n\r\n")).build();
    }

    private static OrderHttpResponse response(String body) {
      return OrderHttpResponse.newBuilder().setData(ByteString.copyFromUtf8(body)).build();
    }

    private static boolean containsForwardingCompletionEvent(CharSequence payload) {
      String normalized = payload.toString().replace("\r\n", "\n");
      int frameStart = 0;
      int frameEnd;
      while ((frameEnd = normalized.indexOf("\n\n", frameStart)) >= 0) {
        String completeFrame = normalized.substring(frameStart, frameEnd);
        if (A2A_TERMINAL_STATE.matcher(completeFrame).find()
            || A2A_FINAL_EVENT.matcher(completeFrame).find()
            || RECOVERY_RESULT_ARTIFACT.matcher(completeFrame).find()) {
          return true;
        }
        frameStart = frameEnd + 2;
      }
      return false;
    }

    private static boolean matchesOptional(String expected, String actual) {
      return expected == null || expected.equals(actual);
    }

    private java.util.Set<String> targetNames() {
      return neTargets.keySet();
    }

    private void prepareStart() {
      shuttingDown.set(false);
    }

    private void beginShutdown() {
      shuttingDown.set(true);
      // Stop the forwarded HTTP exchanges before disposing the RSocket server. Otherwise a
      // Notification-T reader can still try to complete its response after the Netty channel
      // has gone away, which surfaces as a misleading ClosedChannelException on clean exit.
      activeForwardConnections.forEach(
          connection -> Schedulers.boundedElastic().schedule(connection::disconnect));
    }

    private void awaitForwardShutdown() {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
      synchronized (forwardShutdownMonitor) {
        while (!activeForwardConnections.isEmpty()) {
          long remaining = deadline - System.nanoTime();
          if (remaining <= 0) {
            log.warn(
                "[EastcomSimulator] FORWARD_SHUTDOWN_TIMEOUT active={}, timeoutSeconds=2",
                activeForwardConnections.size());
            return;
          }
          try {
            TimeUnit.NANOSECONDS.timedWait(forwardShutdownMonitor, remaining);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn(
                "[EastcomSimulator] FORWARD_SHUTDOWN_INTERRUPTED active={}",
                activeForwardConnections.size());
            return;
          }
        }
      }
      log.info("[EastcomSimulator] FORWARD_SHUTDOWN_DONE active=0");
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
      NeCredentials credentials = neCredentials.get(neName);
      log.info("[EastcomSimulator] LOAD_NE_ACCEPTED ne={}, target={}", neName, targetUrl);
      return Mono.just(
          OrderResourceResponse.newBuilder()
              .setNeUrl(targetUrl)
              .putNeParams("grantType", "password")
              .putNeParams("username", credentials.username())
              .putNeParams("password", credentials.password())
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
      return Flux.create(
          sink -> {
            AtomicBoolean headersParsed = new AtomicBoolean(false);
            AtomicBoolean forwarded = new AtomicBoolean(false);
            AtomicReference<Disposable> requestSubscription = new AtomicReference<>();
            AtomicReference<Disposable> forwardSubscription = new AtomicReference<>();
            Disposable cancelChildren =
                () -> {
                  Disposable forward = forwardSubscription.getAndSet(null);
                  if (forward != null) {
                    forward.dispose();
                  }
                  Disposable request = requestSubscription.getAndSet(null);
                  if (request != null) {
                    request.dispose();
                  }
                };
            sink.onCancel(cancelChildren);
            sink.onDispose(cancelChildren);
            StringBuilder bodyBuf = new StringBuilder();
            String[] parsedMethod = {"POST"};
            String[] parsedPath = {"/"};
            Map<String, String> parsedHeaders = new LinkedHashMap<>();
            String[] requestNe = {""};

            Disposable requestDisposable =
                requests.subscribe(
                    req -> {
                      if (req.hasInfo()) {
                        requestNe[0] = req.getInfo().getParamsOrDefault("deviceName", "");
                      }
                      String data = decodeRequestData(req.getData());
                      if (!headersParsed.get()
                          && (data.startsWith("POST")
                              || data.startsWith("GET")
                              || data.startsWith("PUT")
                              || data.startsWith("DELETE"))) {
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
                            parsedHeaders.put(
                                headerLines[i].substring(0, colon).trim(),
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
                        // SDK 1.1.18 carries deviceName in the first RPC packet. Resolve both
                        // target and credentials per request, even when two NEs share a URL.
                        String targetBase = neTargets.get(requestNe[0]);
                        if (targetBase == null) {
                          sink.error(new IllegalArgumentException("Unknown or missing request NE"));
                          return;
                        }
                        String body =
                            substituteManagedNeCredentials(requestNe[0], bodyBuf.toString());
                        String forwardUrl =
                            targetBase + (httpPath.startsWith("/") ? httpPath : "/" + httpPath);
                        boolean streaming = forwardUrl.endsWith("/message:stream");
                        log.info(
                            "[EastcomSimulator] FORWARD_START method={}, mode={}, target={}, bodyChars={}",
                            httpMethod,
                            streaming ? "stream" : "send",
                            forwardUrl,
                            body.length());
                        Disposable forwardDisposable =
                            forwardParsed(
                                    httpMethod,
                                    httpPath,
                                    parsedHeaders,
                                    body,
                                    forwardUrl,
                                    streaming)
                                .subscribeOn(Schedulers.boundedElastic())
                                .subscribe(
                                    sink::next,
                                    sink::error,
                                    () -> {
                                      // Response Flux completed - complete the
                                      // sink to close the RSocket channel.
                                      sink.complete();
                                    });
                        forwardSubscription.set(forwardDisposable);
                        if (sink.isCancelled()) {
                          forwardDisposable.dispose();
                        }
                      }
                    },
                    sink::error,
                    () -> {
                      // Request Flux completed - if not forwarded yet, this is an error
                      if (!forwarded.get()) {
                        sink.error(new IllegalStateException("request completed without body"));
                      }
                      // If already forwarded, the forwardParsed completion will call
                      // sink.complete()
                    });
            requestSubscription.set(requestDisposable);
            if (sink.isCancelled()) {
              requestDisposable.dispose();
            }
          });
    }

    private Flux<OrderHttpResponse> forwardParsed(
        String httpMethod,
        String httpPath,
        Map<String, String> httpHeaders,
        String body,
        String forwardUrl,
        boolean streaming) {
      long started = System.nanoTime();
      final String fMethod = httpMethod;
      final Map<String, String> fHeaders = httpHeaders;
      final String fBody = body;
      final String fForwardUrl = forwardUrl;
      log.info(
          "[EastcomSimulator] FORWARD_PARSED method={}, bodyChars={}, target={}",
          fMethod,
          fBody.length(),
          fForwardUrl);
      return Flux.create(
          sink -> {
            HttpURLConnection connection = null;
            int chunks = 0;
            boolean terminalRound = false;
            try {
              connection = openConnection(fForwardUrl);
              activeForwardConnections.add(connection);
              HttpURLConnection activeConnection = connection;
              if (shuttingDown.get()) {
                activeConnection.disconnect();
                sink.complete();
                return;
              }
              AtomicBoolean disconnectScheduled = new AtomicBoolean();
              Runnable disconnect =
                  () -> {
                    if (disconnectScheduled.compareAndSet(false, true)) {
                      Schedulers.boundedElastic().schedule(activeConnection::disconnect);
                    }
                  };
              sink.onCancel(disconnect::run);
              sink.onDispose(disconnect::run);
              connection.setRequestMethod(fMethod.isBlank() ? "POST" : fMethod);
              connection.setDoOutput(true);
              connection.setConnectTimeout(connectTimeoutMillis);
              // Cover connection establishment and the first response headers from a
              // remote, LLM-backed OMC. Once a streaming response starts, switch to a
              // short cancellation poll so idle Notification-T subscriptions can be
              // released promptly during shutdown.
              connection.setReadTimeout(
                  streaming ? readTimeoutMillis : Math.max(readTimeoutMillis, 65_000));
              copyHeaders(fHeaders, connection);
              try (OutputStream output = connection.getOutputStream()) {
                output.write(fBody.getBytes(StandardCharsets.UTF_8));
              }
              int status = connection.getResponseCode();
              InputStream input =
                  status >= 400 ? connection.getErrorStream() : connection.getInputStream();
              if (input == null) {
                sink.next(responseWithHeader(""));
                sink.next(responseEnd());
              } else if (streaming && status < 400) {
                connection.setReadTimeout(Math.min(readTimeoutMillis, 1_000));
                InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8);
                char[] buffer = new char[4096];
                StringBuilder terminalScan = new StringBuilder();
                boolean terminal = false;
                while (!sink.isCancelled() && !shuttingDown.get()) {
                  int length;
                  try {
                    length = reader.read(buffer);
                  } catch (java.net.SocketTimeoutException idlePoll) {
                    continue;
                  }
                  if (length == -1) {
                    break;
                  }
                  chunks++;
                  String chunk = new String(buffer, 0, length);
                  log.debug(
                      "[EastcomSimulator] FORWARD_CHUNK index={}, chars={}, elapsedMs={}",
                      chunks,
                      chunk.length(),
                      elapsedMillis(started));
                  sink.next(
                      chunks == 1
                          ? responseWithHeader(status, connection.getHeaderFields(), chunk)
                          : responseChunk(chunk));
                  terminalScan.append(chunk);
                  if (containsForwardingCompletionEvent(terminalScan)) {
                    terminal = true;
                    terminalRound = true;
                    log.info(
                        "[EastcomSimulator] FORWARD_COMPLETION index={}, action=close-forward",
                        chunks);
                    // Close immediately; do not drain the OMC stream
                    // (it may stay open and cause read timeout)
                    sink.next(responseEnd());
                    sink.complete();
                    log.info(
                        "[EastcomSimulator] FORWARD_DONE mode=stream-terminal, status={}, chunks={}, elapsedMs={}",
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
                if (sink.isCancelled() || shuttingDown.get()) {
                  log.info(
                      "[EastcomSimulator] FORWARD_CANCELLED chunks={}, elapsedMs={}, reason={}",
                      chunks,
                      elapsedMillis(started),
                      shuttingDown.get() ? "simulator_shutdown" : "downstream_cancelled");
                  sink.complete();
                  return;
                }
                if (!terminal && !sink.isCancelled()) {
                  input.close();
                }
                sink.next(responseEnd());
                sink.complete();
                log.info(
                    "[EastcomSimulator] FORWARD_DONE mode=stream, status={}, chunks={}, elapsedMs={}",
                    status,
                    chunks,
                    elapsedMillis(started));
              } else {
                try (input) {
                  sink.next(
                      responseWithHeader(
                          status,
                          connection.getHeaderFields(),
                          new String(input.readAllBytes(), StandardCharsets.UTF_8)));
                }
                sink.next(responseEnd());
                sink.complete();
                log.info(
                    "[EastcomSimulator] FORWARD_DONE mode=send, status={}, chunks={}, elapsedMs={}",
                    status,
                    chunks,
                    elapsedMillis(started));
              }
            } catch (Exception e) {
              if ((sink.isCancelled() || shuttingDown.get()) && streaming) {
                // Closing a long-lived Notification-T channel interrupts the
                // forwarded chunked response. HttpURLConnection reports that
                // expected cancellation as "Premature EOF".
                log.info(
                    "[EastcomSimulator] FORWARD_CANCELLED chunks={}, elapsedMs={}, reason={}",
                    chunks,
                    elapsedMillis(started),
                    shuttingDown.get() ? "simulator_shutdown" : "downstream_cancelled");
                sink.complete();
              } else if (streaming && isTargetClosedMidStream(e)) {
                // The target OMC closed the chunked response mid-stream (its A2A server
                // typically stops before this simulator's shutdown flag is set). Complete the
                // forwarded response instead of surfacing a protocol failure.
                if (chunks > 0) {
                  sink.next(responseEnd());
                }
                sink.complete();
                log.info(
                    "[EastcomSimulator] FORWARD_TARGET_CLOSED chunks={}, elapsedMs={}, reason=target_closed_mid_stream",
                    chunks,
                    elapsedMillis(started));
              } else if (e instanceof java.net.SocketTimeoutException && !terminalRound) {
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
                log.info(
                    "[EastcomSimulator] FORWARD_IDLE_TIMEOUT chunks={}, elapsedMs={}, action=close-idle",
                    chunks,
                    elapsedMillis(started));
              } else {
                log.error(
                    "[EastcomSimulator] FORWARD_FAILED elapsedMs={}, message={}",
                    elapsedMillis(started),
                    e.getMessage(),
                    e);
                sink.error(e);
              }
            } finally {
              if (connection != null) {
                activeForwardConnections.remove(connection);
                synchronized (forwardShutdownMonitor) {
                  forwardShutdownMonitor.notifyAll();
                }
                if (!terminalRound) {
                  HttpURLConnection connectionToClose = connection;
                  Schedulers.boundedElastic().schedule(connectionToClose::disconnect);
                }
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

    private void clear() {
      sessions.clear();
    }

    private record SessionState(String ne, String schema) {}
  }
}
