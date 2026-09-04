/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 *    Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package dev.openan.workflow.engine.client;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.a2aproject.sdk.client.http.JdkA2AHttpClient;
import org.a2aproject.sdk.spec.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RemoteA2AErrorResponseTest {
  static String error(int code) {
    String status = code == 429 ? "RESOURCE_EXHAUSTED" : "INVALID_ARGUMENT";
    String reason = code == 429 ? "ACTIVE_TASK_LIMIT_EXCEEDED" : "INVALID_PARAMS";
    String domain = code == 429 ? "fixture.invalid" : "a2a-protocol.org";
    return "{\"error\":{\"code\":" + code + ",\"status\":\"" + status
        + "\",\"message\":\"该端口下未发现业务信息\",\"details\":["
        + "{\"@type\":\"type.googleapis.com/google.rpc.BadRequest\","
        + "\"fieldViolations\":[{\"field\":\"port\",\"description\":\"required\"}]},"
        + "{\"@type\":\"type.googleapis.com/google.rpc.ErrorInfo\",\"reason\":\""
        + reason + "\",\"domain\":\"" + domain + "\","
        + "\"metadata\":{\"taskId\":\"task-123\"}}]}}";
  }

  @Test
  void preservesStandardFieldsAndFindsErrorInfoAmongTypedDetails() {
    var error = RemoteA2AErrorException.fromResponse(
        400, error(400), Map.of("Retry-After", List.of("5")));
    assertNotNull(error);
    assertEquals(400, error.getHttpStatus());
    assertEquals(400, error.getCode());
    assertEquals("INVALID_ARGUMENT", error.getStatus());
    assertEquals("INVALID_PARAMS", error.getReason());
    assertEquals("a2a-protocol.org", error.getDomain());
    assertEquals("该端口下未发现业务信息", error.getMessage());
    assertEquals(2, error.getDetails().size());
    assertEquals("5", error.getRetryAfter());
    assertEquals("a2a.invalid_params", error.workflowErrorCode());
  }

  @Test
  void ignoresOldProblemShapeAndNestedBusinessErrors() {
    assertNull(RemoteA2AErrorException.fromPayload(
        "{\"status\":400,\"title\":\"Invalid Params\",\"detail\":\"missing\"}"));
    assertNull(RemoteA2AErrorException.fromPayload(
        "{\"artifactUpdate\":{\"artifact\":{\"parts\":[{\"data\":" + error(400) + "}]}}}"));
    assertNull(RemoteA2AErrorException.fromPayload("{\"error\":{\"code\":200,\"message\":\"ok\"}}"));
    assertNull(RemoteA2AErrorException.fromPayload("{\"error\":{\"code\":400}}"));
    assertNull(RemoteA2AErrorException.fromPayload("{\"error\":{"));
  }

  @Test
  void redactsTypedDetailsAndResponseHeaders() {
    String payload = error(400).replace("\"taskId\":\"task-123\"",
        "\"accessSession\":\"secret-value\"");
    var error = RemoteA2AErrorException.fromResponse(
        400, payload, Map.of("Authorization", List.of("Bearer secret")));
    assertEquals("***", ((Map<?, ?>) error.getDetails().get(1).get("metadata")).get("accessSession"));
    assertEquals(List.of("***"), error.getResponseHeaders().get("Authorization"));
  }

  @Test
  void normalizesPeerSuppliedReasonBeforeUsingItAsAWorkflowCode() {
    String payload =
        error(400).replace("INVALID_PARAMS", "Invalid Params / Port#1");

    var error = RemoteA2AErrorException.fromPayload(payload);

    assertNotNull(error);
    assertEquals("a2a.invalid_params_port_1", error.workflowErrorCode());
  }

  @ParameterizedTest
  @ValueSource(ints = {400, 429, 503})
  void directRuntimeFailsPromptlyWhenSdkExposesAnErrorBodyAsSseData(int status) throws Exception {
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
        byte[] body = ("data: " + error(status) + "\n\n").getBytes(StandardCharsets.UTF_8);
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
      var remoteError = RemoteA2AErrorException.findIn(thrown);
      assertNotNull(remoteError);
      assertEquals(status, remoteError.getHttpStatus());
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
  void projectsTypedSdkErrorsAndHandlesCauseCycles() {
    var projected = RemoteA2AErrorException.findIn(
        new IllegalStateException("wrapper", new InvalidParamsError("missing port")));
    assertNotNull(projected);
    assertEquals("INVALID_PARAMS", projected.getReason());
    assertEquals("a2a.invalid_params", projected.workflowErrorCode());
    assertNull(RemoteA2AErrorException.findIn(null));
    var one = new IllegalStateException("one");
    var two = new IllegalStateException("two", one);
    one.initCause(two);
    assertNull(RemoteA2AErrorException.findIn(one));
  }

  @Test
  void nonStreamingResponsesPreserveTheStandardA2AError() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", exchange -> {
      byte[] body = error(400).getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/a2a+json");
      exchange.sendResponseHeaders(400, body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    });
    server.start();
    try {
      var client = new A2AErrorDetectingHttpClient(new JdkA2AHttpClient(HttpClient.newHttpClient()));
      var error = assertThrows(RemoteA2AErrorException.class, () -> client.createPost()
          .url("http://127.0.0.1:" + server.getAddress().getPort()).body("{}").post());
      assertEquals(400, error.getHttpStatus());
      assertEquals("INVALID_PARAMS", error.getReason());
    } finally {
      server.stop(0);
    }
  }
}
