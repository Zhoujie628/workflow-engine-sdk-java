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

package dev.openan.workflow.engine.examples.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.util.JsonFormat;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import dev.openan.workflow.engine.client.AgentCardJacksonModule;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Flow;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import org.a2aproject.sdk.grpc.SendMessageRequest;
import org.a2aproject.sdk.grpc.StreamResponse;
import org.a2aproject.sdk.grpc.utils.ProtoUtils;
import org.a2aproject.sdk.server.AgentCardCacheMetadata;
import org.a2aproject.sdk.server.ServerCallContext;
import org.a2aproject.sdk.server.auth.TaskOperation;
import org.a2aproject.sdk.server.agentexecution.AgentExecutor;
import org.a2aproject.sdk.server.events.InMemoryQueueManager;
import org.a2aproject.sdk.server.events.MainEventBus;
import org.a2aproject.sdk.server.events.MainEventBusProcessor;
import org.a2aproject.sdk.server.extensions.A2AExtensions;
import org.a2aproject.sdk.server.requesthandlers.DefaultRequestHandler;
import org.a2aproject.sdk.server.requesthandlers.RequestHandler;
import org.a2aproject.sdk.server.tasks.BasePushNotificationSender;
import org.a2aproject.sdk.server.tasks.InMemoryPushNotificationConfigStore;
import org.a2aproject.sdk.server.tasks.InMemoryTaskStore;
import org.a2aproject.sdk.server.tasks.PushNotificationConfigStore;
import org.a2aproject.sdk.server.tasks.PushNotificationSender;
import org.a2aproject.sdk.server.version.A2AVersionValidator;
import org.a2aproject.sdk.spec.A2AError;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.InternalError;
import org.a2aproject.sdk.spec.InvalidRequestError;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.StreamingEventKind;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskArtifactUpdateEvent;
import org.a2aproject.sdk.spec.TaskIdParams;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;
import org.a2aproject.sdk.spec.UnsupportedOperationError;
import org.a2aproject.sdk.transport.rest.handler.RestHandler;
import org.a2aproject.sdk.transport.rest.handler.RestHandler.HTTPRestResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JdkHttpA2AServer implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(JdkHttpA2AServer.class);

  private static final ObjectMapper mapper = new ObjectMapper();

  private static final ObjectMapper cardMapper =
      new ObjectMapper().registerModule(new AgentCardJacksonModule());

  private static final int THREAD_COUNT = 8;

  private static final String DEFAULT_KEYSTORE_RESOURCE = "a2a-server-keystore.p12";

  private static final String DEFAULT_KEYSTORE_PASSWORD = "changeit";

  private final String keystoreResource;

  private final String keystorePassword;

  private final HttpsServer server;

  private final ExecutorService executorService;

  private final ExecutorService agentExecutorService;

  private final Thread eventBusThread;

  private final Map<String, Object> agentCardMap;

  private final String agentName;

  private final String pathPrefix;

  @SuppressWarnings("unchecked")
  public JdkHttpA2AServer(
      String host, int port, Map<String, Object> agentCard, AgentExecutor agentExecutor)
      throws IOException {
    this(
        host, port, agentCard, agentExecutor, DEFAULT_KEYSTORE_RESOURCE, DEFAULT_KEYSTORE_PASSWORD);
  }

  public JdkHttpA2AServer(
      String host,
      int port,
      Map<String, Object> agentCard,
      AgentExecutor agentExecutor,
      String keystoreResource,
      String keystorePassword)
      throws IOException {
    this.keystoreResource = keystoreResource;
    this.keystorePassword = keystorePassword;
    this.agentCardMap = agentCard;
    this.agentName = String.valueOf(agentCard.getOrDefault("name", "unknown"));
    this.pathPrefix = extractPathPrefix(agentCard);
    AgentCard typedCard = toTypedAgentCard(agentCard);
    this.agentExecutorService =
        new ThreadPoolExecutor(
            THREAD_COUNT,
            THREAD_COUNT,
            0L,
            TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(),
            r -> {
              Thread t = new Thread(r, "a2a-server-" + agentName);
              t.setDaemon(true);
              return t;
            });
    ServerComponents components = initServerComponents(agentExecutor, typedCard);
    this.eventBusThread = components.eventBusThread();
    this.executorService =
        new ThreadPoolExecutor(
            THREAD_COUNT,
            THREAD_COUNT,
            0L,
            TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(),
            r -> {
              Thread t = new Thread(r, "a2a-http-" + agentName);
              t.setDaemon(true);
              return t;
            });
    SSLContext sslContext = createSslContext();
    this.server = HttpsServer.create(new InetSocketAddress(host, port), 0);
    this.server.setHttpsConfigurator(
        new HttpsConfigurator(sslContext) {
          @Override
          public void configure(HttpsParameters params) {
            SSLParameters sslParams = sslContext.getDefaultSSLParameters();
            params.setSSLParameters(sslParams);
          }
        });
    this.server.setExecutor(executorService);
    this.server.createContext(
        pathPrefix.isEmpty() ? "/" : pathPrefix,
        exchange -> handleExchange(exchange, components, typedCard));
    if (hasSecuritySchemes(agentCard)) {
      this.server.createContext("/rest/plat/smapp/v1/oauth/token", this::handleLogin);
      log.info("[{}] Auth login endpoint enabled", agentName);
    }

    log.info("[{}] A2A server configured on https://{}:{}/", agentName, host, port);
  }

  private static String formatSse(long seq, String payload) {
    String compact =
        payload == null
            ? ""
            : payload
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .lines()
                .map(String::trim)
                .reduce("", String::concat);
    return String.format(Locale.ROOT, "id:%d%n", seq) + "data:" + compact + "\n\n";
  }

  private static StreamResponse toStreamResponse(StreamingEventKind event) {
    StreamResponse.Builder b = StreamResponse.newBuilder();
    if (event instanceof Message m) {
      b.setMessage(ProtoUtils.ToProto.message(m));
      return b.build();
    }
    if (event instanceof Task t) {
      b.setTask(ProtoUtils.ToProto.task(t));
      return b.build();
    }
    if (event instanceof TaskStatusUpdateEvent e) {
      b.setStatusUpdate(ProtoUtils.ToProto.taskStatusUpdateEvent(e));
      return b.build();
    }
    if (event instanceof TaskArtifactUpdateEvent e) {
      b.setArtifactUpdate(ProtoUtils.ToProto.taskArtifactUpdateEvent(e));
      return b.build();
    }
    throw new IllegalArgumentException("Unsupported event: " + event);
  }

  private static ServerCallContext buildCallContext(HttpExchange exchange) {
    Map<String, String> headers = new LinkedHashMap<>();
    exchange
        .getRequestHeaders()
        .forEach((k, v) -> headers.put(k.toLowerCase(Locale.ROOT), String.join(",", v)));
    String ext = firstHeader(headers, "A2A-Extensions", "X-A2A-Extensions");
    Set<String> exts;
    if (ext.isBlank()) {
      exts = Set.of();
    } else {
      java.util.LinkedHashSet<String> parsed = new java.util.LinkedHashSet<>();
      for (String value : ext.split(",")) {
        if (!value.isBlank()) parsed.add(value.strip());
      }
      exts = java.util.Collections.unmodifiableSet(parsed);
    }
    return new ServerCallContext(
        null,
        Map.of("headers", headers),
        exts,
        firstNullableHeader(headers, "A2A-Version", "A2A-Protocol-Version"));
  }

  private static String firstHeader(Map<String, String> h, String a, String b) {
    String v = firstNullableHeader(h, a, b);
    return v == null ? "" : v;
  }

  private static String firstNullableHeader(Map<String, String> h, String a, String b) {
    String v = h.get(a.toLowerCase(Locale.ROOT));
    return v != null ? v : h.get(b.toLowerCase(Locale.ROOT));
  }

  private static String readBody(HttpExchange exchange) throws IOException {
    return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
  }

  private static void sendJson(HttpExchange exchange, int status, Object data) throws IOException {
    String json = data instanceof String ? (String) data : mapper.writeValueAsString(data);
    byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream os = exchange.getResponseBody()) {
      os.write(bytes);
    }
  }

  private static void sendRestResponse(HttpExchange exchange, HTTPRestResponse response)
      throws IOException {
    byte[] bytes = response.getBody().getBytes(StandardCharsets.UTF_8);
    exchange
        .getResponseHeaders()
        .set("Content-Type", "application/a2a+json");
    exchange.sendResponseHeaders(response.getStatusCode(), bytes.length);
    try (OutputStream os = exchange.getResponseBody()) {
      os.write(bytes);
    }
  }

  private static void sendA2AError(
      HttpExchange exchange, RestHandler restHandler, A2AError error) throws IOException {
    var response = restHandler.createErrorResponse(error);
    byte[] bytes = response.getBody().getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/a2a+json");
    exchange.sendResponseHeaders(response.getStatusCode(), bytes.length);
    try (OutputStream os = exchange.getResponseBody()) {
      os.write(bytes);
    }
  }

  private static AgentCard toTypedAgentCard(Map<String, Object> card) {
    return cardMapper.convertValue(card, AgentCard.class);
  }

  private static boolean hasSecuritySchemes(Map<String, Object> card) {
    return card.containsKey("securitySchemes")
        && card.get("securitySchemes") instanceof Map
        && !((Map<?, ?>) card.get("securitySchemes")).isEmpty()
        && card.containsKey("securityRequirements");
  }

  @SuppressWarnings("unchecked")
  private static String extractPathPrefix(Map<String, Object> agentCard) {
    List<Map<String, Object>> interfaces =
        (List<Map<String, Object>>) agentCard.getOrDefault("supportedInterfaces", List.of());
    for (Map<String, Object> iface : interfaces) {
      if ("HTTP+JSON".equalsIgnoreCase(String.valueOf(iface.get("protocolBinding")))) {
        String url = String.valueOf(iface.getOrDefault("url", ""));
        try {
          String path = java.net.URI.create(url).getPath();
          if (path != null && !path.isEmpty() && !"/".equals(path)) {
            return path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
          }
        } catch (Exception ignored) {
        }
        break;
      }
    }
    return "";
  }

  /** Parses exactly one encoded task-id path segment and rejects ambiguous task routes. */
  static String taskIdFromPath(String path, String operationSuffix) {
    String prefix = "/tasks/";
    if (path == null || !path.startsWith(prefix)) {
      return null;
    }
    String encoded = path.substring(prefix.length());
    if (operationSuffix != null) {
      if (!encoded.endsWith(operationSuffix)) {
        return null;
      }
      encoded = encoded.substring(0, encoded.length() - operationSuffix.length());
    } else if (encoded.contains(":")) {
      return null;
    }
    if (encoded.isEmpty() || encoded.contains("/")) {
      return null;
    }
    try {
      // URLDecoder follows form semantics where '+' means space. In a URI path segment an
      // unescaped '+' is literal, so protect it before decoding percent escapes.
      return URLDecoder.decode(encoded.replace("+", "%2B"), StandardCharsets.UTF_8);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Invalid encoded task id", e);
    }
  }

  private SSLContext createSslContext() throws IOException {
    try (InputStream is =
        JdkHttpA2AServer.class.getClassLoader().getResourceAsStream(keystoreResource)) {
      if (is == null) {
        throw new IOException("Keystore not found on classpath: " + keystoreResource);
      }
      KeyStore ks = KeyStore.getInstance("PKCS12");
      ks.load(is, keystorePassword.toCharArray());
      KeyManagerFactory kmf =
          KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
      kmf.init(ks, keystorePassword.toCharArray());
      SSLContext ctx = SSLContext.getInstance("TLS");
      ctx.init(kmf.getKeyManagers(), null, null);
      return ctx;
    } catch (Exception e) {
      throw new IOException("Failed to init SSL context: " + e.getMessage(), e);
    }
  }

  public void start() {
    eventBusThread.start();
    try {
      server.start();
      log.info(
          "[{}] A2A server started on https://{}:{}/",
          agentName,
          server.getAddress().getHostString(),
          server.getAddress().getPort());
    } catch (RuntimeException error) {
      eventBusThread.interrupt();
      throw error;
    }
  }

  @Override
  public void close() {
    server.stop(0);
    eventBusThread.interrupt();
    agentExecutorService.shutdownNow();
    executorService.shutdownNow();
    try {
      eventBusThread.join(1_000);
      if (eventBusThread.isAlive()) {
        log.warn("[{}] Event bus processor did not stop within 1 second", agentName);
      }
      agentExecutorService.awaitTermination(1, TimeUnit.SECONDS);
      executorService.awaitTermination(1, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    log.info("[{}] A2A server stopped", agentName);
  }

  public Map<String, Object> getAgentCard() {
    return agentCardMap;
  }

  public String getAgentName() {
    return agentName;
  }

  boolean isEventBusProcessorAlive() {
    return eventBusThread.isAlive();
  }

  private ServerComponents initServerComponents(AgentExecutor agentExecutor, AgentCard typedCard) {
    InMemoryTaskStore taskStore = new InMemoryTaskStore();
    MainEventBus mainEventBus = new MainEventBus();
    InMemoryQueueManager queueManager = new InMemoryQueueManager(taskStore, mainEventBus);
    PushNotificationConfigStore pushStore = new InMemoryPushNotificationConfigStore();
    PushNotificationSender pushSender = new BasePushNotificationSender(pushStore);
    MainEventBusProcessor eventBusProc =
        new MainEventBusProcessor(mainEventBus, taskStore, pushSender, queueManager);
    // Manual wiring: no CDI/Spring container invokes the @PostConstruct start(), and
    // ensureStarted() is intentionally empty in the SDK (it only forces CDI proxy resolution).
    // Without this thread the event bus never drains and inbound tasks hang forever.
    Thread eventBusThread = new Thread(eventBusProc, "MainEventBusProcessor");
    eventBusThread.setDaemon(true);
    RequestHandler requestHandler =
        DefaultRequestHandler.builder()
            .agentExecutor(agentExecutor)
            .taskStore(taskStore)
            .queueManager(queueManager)
            .pushConfigStore(pushStore)
            .mainEventBusProcessor(eventBusProc)
            .executor(agentExecutorService)
            .eventConsumerExecutor(agentExecutorService)
            .build();
    RestHandler restHandler =
        new RestHandler(
            typedCard, new AgentCardCacheMetadata(typedCard, null), requestHandler, Runnable::run);
    return new ServerComponents(restHandler, requestHandler, eventBusThread);
  }

  private record ServerComponents(
      RestHandler restHandler, RequestHandler requestHandler, Thread eventBusThread) {}

  private void handleExchange(
      HttpExchange exchange, ServerComponents components, AgentCard typedCard) throws IOException {
    RestHandler restHandler = components.restHandler();
    String fullPath = exchange.getRequestURI().getRawPath();
    String method = exchange.getRequestMethod();
    String path =
        pathPrefix.isEmpty() || !fullPath.startsWith(pathPrefix)
            ? fullPath
            : fullPath.substring(pathPrefix.length());
    if (path.isEmpty()) path = "/";
    try {
      if ("GET".equalsIgnoreCase(method) && "/".equals(path)) {
        sendJson(exchange, 200, restHandler.getAgentCard().getBody());
        return;
      }

      if ("POST".equalsIgnoreCase(method) && "/message:send".equals(path)) {
        var resp = restHandler.sendMessage(buildCallContext(exchange), "", readBody(exchange));
        sendRestResponse(exchange, resp);
        return;
      }

      if ("POST".equalsIgnoreCase(method) && "/message:stream".equals(path)) {

        handleStream(
            exchange,
            restHandler,
            components.requestHandler(),
            typedCard,
            readBody(exchange));
        return;
      }
      if ("GET".equalsIgnoreCase(method) && "/tasks".equals(path)) {
        Map<String, String> query = queryParams(exchange);
        var resp =
            restHandler.listTasks(
                buildCallContext(exchange),
                "",
                query.get("contextId"),
                query.get("status"),
                integerParam(query, "pageSize"),
                query.get("pageToken"),
                integerParam(query, "historyLength"),
                query.get("statusTimestampAfter"),
                booleanParam(query, "includeArtifacts"));
        sendRestResponse(exchange, resp);
        return;
      }
      // Task routes (?? A2A: ??/??/??)
      String taskId = taskIdFromPath(path, null);
      if ("GET".equalsIgnoreCase(method) && taskId != null) {
        var resp = restHandler.getTask(buildCallContext(exchange), "", taskId, null);
        sendRestResponse(exchange, resp);
        return;
      }
      taskId = taskIdFromPath(path, ":cancel");
      if ("POST".equalsIgnoreCase(method) && taskId != null) {
        var resp =
            restHandler.cancelTask(buildCallContext(exchange), "", readBody(exchange), taskId);
        sendRestResponse(exchange, resp);
        return;
      }
      taskId = taskIdFromPath(path, ":subscribe");
      if ("POST".equalsIgnoreCase(method) && taskId != null) {
        handleSubscribe(exchange, restHandler, components.requestHandler(), typedCard, taskId);
        return;
      }
      exchange.sendResponseHeaders(404, -1);
    } catch (A2AError e) {
      sendA2AError(exchange, restHandler, e);
    } catch (IllegalArgumentException e) {
      sendA2AError(exchange, restHandler, new InvalidRequestError("Invalid A2A request"));
    } catch (Exception e) {
      log.error("[{}] Handler error: {}", agentName, e.getClass().getSimpleName(), e);
      sendA2AError(exchange, restHandler, new InternalError("A2A request handling failed"));
    } finally {
      exchange.close();
    }
  }

  private static Map<String, String> queryParams(HttpExchange exchange) {
    String rawQuery = exchange.getRequestURI().getRawQuery();
    if (rawQuery == null || rawQuery.isBlank()) return Map.of();
    Map<String, String> values = new LinkedHashMap<>();
    for (String pair : rawQuery.split("&")) {
      String[] fields = pair.split("=", 2);
      String key = URLDecoder.decode(fields[0], StandardCharsets.UTF_8);
      String value =
          fields.length == 2 ? URLDecoder.decode(fields[1], StandardCharsets.UTF_8) : "";
      values.put(key, value);
    }
    return values;
  }

  private static Integer integerParam(Map<String, String> query, String name) {
    String value = query.get(name);
    return value == null || value.isBlank() ? null : Integer.valueOf(value);
  }

  private static Boolean booleanParam(Map<String, String> query, String name) {
    String value = query.get(name);
    if (value == null || value.isBlank()) return null;
    if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
      throw new IllegalArgumentException(name + " must be true or false");
    }
    return Boolean.valueOf(value);
  }

  @SuppressWarnings("unchecked")
  private void handleLogin(HttpExchange exchange) throws IOException {
    if (!"PUT".equalsIgnoreCase(exchange.getRequestMethod())
        && !"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
      sendJson(exchange, 405, Map.of("error", "Method not allowed"));
      return;
    }

    String body = readBody(exchange);
    String userName = "";
    String password = "";
    try {

      Map<String, Object> data = mapper.readValue(body, Map.class);
      userName = String.valueOf(data.getOrDefault("userName", data.getOrDefault("username", "")));
      password = String.valueOf(data.getOrDefault("value", data.getOrDefault("password", "")));
    } catch (Exception e) {
      sendJson(exchange, 400, Map.of("error", "Invalid request body"));
      return;
    }
    if ("admin".equals(userName) && "Admin@123".equals(password)) {
      log.info("[{}] Login succeeded, token issued", agentName);
      sendJson(exchange, 200, Map.of("accessSession", UUID.randomUUID().toString()));
    } else {
      log.warn("[{}] Login failed: bad credentials", agentName);
      sendJson(exchange, 401, Map.of("error", "Invalid credentials"));
    }
  }

  @SuppressWarnings("unchecked")
  private void handleSubscribe(
      HttpExchange exchange,
      RestHandler restHandler,
      RequestHandler requestHandler,
      AgentCard typedCard,
      String taskId)
      throws IOException {
    try {
      requireStreamingSupport(typedCard);
      ServerCallContext context = buildCallContext(exchange);
      requestHandler.authorizeTaskAccess(taskId, context, TaskOperation.SUBSCRIBE_TO_TASK);
      Flow.Publisher<StreamingEventKind> publisher =
          requestHandler.onSubscribeToTask(
              TaskIdParams.builder().id(taskId).tenant("").build(), context);
      writeStream(exchange, publisher, context);
    } catch (A2AError e) {
      sendA2AError(exchange, restHandler, e);
    }
  }

  @SuppressWarnings("unchecked")
  private void handleStream(
      HttpExchange exchange,
      RestHandler restHandler,
      RequestHandler requestHandler,
      AgentCard typedCard,
      String requestBody)
      throws IOException {
    try {
      requireStreamingSupport(typedCard);
      ServerCallContext context = buildCallContext(exchange);
      A2AVersionValidator.validateProtocolVersion(typedCard, context);
      A2AExtensions.validateRequiredExtensions(typedCard, context);
      SendMessageRequest.Builder builder = SendMessageRequest.newBuilder();
      JsonFormat.parser().merge(requestBody, builder);
      var request = ProtoUtils.FromProto.messageSendParams(builder.build());
      requestHandler.authorizeTaskAccess(
          request.message().taskId(), context, TaskOperation.MESSAGE_SEND_STREAM);
      Flow.Publisher<StreamingEventKind> publisher =
          requestHandler.onMessageSendStream(request, context);
      writeStream(exchange, publisher, context);
    } catch (A2AError e) {
      sendA2AError(exchange, restHandler, e);
    } catch (com.google.protobuf.InvalidProtocolBufferException e) {
      sendA2AError(exchange, restHandler, new InvalidRequestError("Invalid A2A request body"));
    }
  }

  private static void requireStreamingSupport(AgentCard agentCard)
      throws UnsupportedOperationError {
    if (!agentCard.capabilities().streaming()) {
      throw new UnsupportedOperationError(null, "Streaming is not supported by the agent", null);
    }
  }

  private static void writeStream(
      HttpExchange exchange,
      Flow.Publisher<StreamingEventKind> publisher,
      ServerCallContext context)
      throws IOException {
    exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
    exchange.sendResponseHeaders(200, 0);
    AtomicLong seq = new AtomicLong(0);
    CountDownLatch done = new CountDownLatch(1);
    OutputStream output = exchange.getResponseBody();
    StreamCancellation cancellation = new StreamCancellation(context);
    publisher.subscribe(
        new Flow.Subscriber<>() {
          @Override
          public void onSubscribe(Flow.Subscription subscription) {
            cancellation.register(subscription);
          }

          @Override
          public void onNext(StreamingEventKind item) {
            try {
              String payload = JsonFormat.printer().print(toStreamResponse(item));
              output.write(
                  formatSse(seq.incrementAndGet(), payload).getBytes(StandardCharsets.UTF_8));
              output.flush();
              cancellation.requestNext();
            } catch (IOException error) {
              cancellation.release();
              done.countDown();
            }
          }

          @Override
          public void onError(Throwable error) {
            cancellation.release();
            done.countDown();
          }

          @Override
          public void onComplete() {
            cancellation.release();
            done.countDown();
          }
        });
    try {
      done.await();
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
    } finally {
      cancellation.release();
      output.close();
    }
  }

  private static final class StreamCancellation {
    private final ServerCallContext context;
    private final AtomicReference<Flow.Subscription> subscription = new AtomicReference<>();
    private final AtomicBoolean released = new AtomicBoolean();

    private StreamCancellation(ServerCallContext context) {
      this.context = context;
    }

    private void register(Flow.Subscription candidate) {
      if (!subscription.compareAndSet(null, candidate) || released.get()) {
        candidate.cancel();
      } else {
        candidate.request(1);
      }
    }

    private void requestNext() {
      Flow.Subscription active = subscription.get();
      if (active != null && !released.get()) {
        active.request(1);
      }
    }

    private void release() {
      if (!released.compareAndSet(false, true)) {
        return;
      }
      Flow.Subscription active = subscription.get();
      if (active != null) {
        try {
          active.cancel();
        } catch (RuntimeException error) {
          log.warn("[SSE] Failed to cancel publisher subscription: {}", error.getMessage());
        }
      }
      try {
        context.invokeEventConsumerCancelCallback();
      } catch (RuntimeException error) {
        log.warn("[SSE] Failed to notify SDK event consumer cancellation: {}", error.getMessage());
      }
    }
  }
}
