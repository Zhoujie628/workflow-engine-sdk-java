/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.client;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.a2aproject.sdk.client.http.JdkA2AHttpClient;
import org.a2aproject.sdk.spec.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RemoteProblemResponseTest {
  static String problem(int status) {
    return "{\"status\":" + status + ",\"detail\":\"该端口下未发现业务信息\","
        + "\"title\":\"Invalid Params\",\"type\":\"\",\"timestamp\":\"2026-08-31T09:07:36Z\"}";
  }

  @Test
  void preservesProblemFieldsWithoutInspectingNestedBusinessData() {
    var error = RemoteProblemException.fromPayload(problem(400));
    assertNotNull(error);
    assertEquals(400, error.getStatus());
    assertEquals("Invalid Params", error.getTitle());
    assertEquals("该端口下未发现业务信息", error.getDetail());
    assertEquals("", error.getType());
    assertEquals("2026-08-31T09:07:36Z", error.getTimestamp());
    assertNull(RemoteProblemException.fromPayload("{\"artifactUpdate\":{\"data\":" + problem(400) + "}}"));
    assertNull(RemoteProblemException.fromPayload("{\"status\":{\"state\":\"TASK_STATE_WORKING\"}}"));
    assertNull(RemoteProblemException.fromPayload("{\"status\":400}"));
    assertNull(RemoteProblemException.fromPayload(problem(200)));
    assertNull(RemoteProblemException.fromPayload("{\"status\":429,"));
  }

  @ParameterizedTest
  @ValueSource(ints = {400, 429, 503})
  void directRuntimeFailsPromptlyWithoutWaitingForSseClosureOrRetrying(int status) throws Exception {
    var release = new CountDownLatch(1);
    var disconnected = new CountDownLatch(1);
    var requests = new AtomicInteger();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", exchange -> {
      requests.incrementAndGet();
      exchange.getRequestBody().readAllBytes();
      exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
      exchange.sendResponseHeaders(200, 0);
      try {
        String payload = status == 503
            ? problem(status).replace("该端口下未发现业务信息", "connection closed locally; request rejected")
            : problem(status);
        byte[] body = ("data: " + payload + "\n\n").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseBody().write(body, 0, 12);
        exchange.getResponseBody().flush();
        exchange.getResponseBody().write(body, 12, body.length - 12);
        exchange.getResponseBody().flush();
        for (int i = 0; i < 80 && !release.await(100, TimeUnit.MILLISECONDS); i++) {
          exchange.getResponseBody().write(": keep-alive\n\n".getBytes(StandardCharsets.UTF_8));
          exchange.getResponseBody().flush();
        }
      } catch (java.io.IOException clientClosed) {
        disconnected.countDown();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      } finally {
        exchange.close();
      }
    });
    server.start();
    var runtime = new DefaultA2AJavaClientRuntime(true, null, 20, "HTTP+JSON");
    try {
      AgentCard card = AgentCard.builder().name("remote-test").description("test").version("1")
          .capabilities(AgentCapabilities.builder().streaming(true).build())
          .defaultInputModes(List.of("text/plain")).defaultOutputModes(List.of("text/plain"))
          .skills(List.of()).supportedInterfaces(List.of(new AgentInterface("HTTP+JSON",
              "http://127.0.0.1:" + server.getAddress().getPort() + "/a2a/json"))).build();
      MessageSendParams params = MessageSendParams.builder().message(Message.builder()
          .role(Message.Role.ROLE_USER).messageId("test").parts(new TextPart("diagnose")).build()).build();
      var events = new AtomicInteger();
      var response = CompletableFuture.supplyAsync(
          () -> runtime.sendMessage(card, params, null, event -> events.incrementAndGet(), null));
      var thrown = assertThrows(java.util.concurrent.ExecutionException.class,
          () -> response.get(3, TimeUnit.SECONDS));
      Throwable cause = thrown;
      while (cause.getCause() != null && !(cause instanceof RemoteProblemException)) cause = cause.getCause();
      var problem = assertInstanceOf(RemoteProblemException.class, cause);
      assertEquals(status, problem.getStatus());
      assertEquals(0, events.get());
      assertEquals(1, requests.get());
      assertTrue(disconnected.await(3, TimeUnit.SECONDS), "failed SSE must close its HTTP connection");
    } finally {
      release.countDown();
      runtime.close();
      server.stop(0);
    }
  }

  @Test
  void causeLookupHandlesNullCyclesAndWrappedProblems() {
    var problem = RemoteProblemException.fromPayload(problem(429));
    assertSame(problem, RemoteProblemException.findIn(new IllegalStateException("wrapper", problem)));
    assertNull(RemoteProblemException.findIn(null));
    var one = new IllegalStateException("one");
    var two = new IllegalStateException("two", one);
    one.initCause(two);
    assertNull(RemoteProblemException.findIn(one));
  }

  @Test
  void nonStreamingResponsesPreserveTheRemoteBusinessFailure() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", exchange -> {
      byte[] body = problem(400).getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(400, body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    });
    server.start();
    try {
      var client = new ProblemDetectingHttpClient(new JdkA2AHttpClient(HttpClient.newHttpClient()));
      var error = assertThrows(RemoteProblemException.class, () -> client.createPost()
          .url("http://127.0.0.1:" + server.getAddress().getPort()).body("{}").post());
      assertEquals(400, error.getStatus());
    } finally {
      server.stop(0);
    }
  }
}
