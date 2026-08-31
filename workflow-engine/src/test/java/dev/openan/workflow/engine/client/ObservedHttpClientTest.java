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

package dev.openan.workflow.engine.client;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ObservedHttpClientTest {
  @AfterEach
  void clearSettings() {
    System.clearProperty("WORKFLOW_ENGINE_PROTOCOL_INCLUDE_BODY");
    System.clearProperty("WORKFLOW_ENGINE_PROTOCOL_MAX_BODY_CHARS");
  }

  @Test
  void logsActualRequestAndResponseWithoutReencodingOrExposingCredentials() throws Exception {
    String body = "{ \"message\": \"任务\", \"password\": \"login-secret\" }";
    String response = "{\"ok\":true,\"accessSession\":\"omc-secret\"}";
    List<WireLog.Entry> records = new CopyOnWriteArrayList<>();
    List<String> capturedBody = new CopyOnWriteArrayList<>();
    List<List<String>> capturedVersion = new CopyOnWriteArrayList<>();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/message",
        exchange -> {
          capturedBody.add(
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          capturedVersion.add(exchange.getRequestHeaders().get("A2A-Version"));
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.getResponseHeaders().add("X-Multi", "one");
          exchange.getResponseHeaders().add("X-Multi", "two");
          exchange.getResponseHeaders().add("bearToken", "header-secret");
          byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.start();
    try {
      HttpRequest request =
          HttpRequest.newBuilder(
                  URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/message"))
              .header("A2A-Version", "1.0")
              .header("Authorization", "Bearer request-secret")
              .header("X-Multi", "a")
              .header("X-Multi", "b")
              .POST(HttpRequest.BodyPublishers.ofString(body))
              .build();
      HttpResponse<String> result =
          new ObservedHttpClient(HttpClient.newHttpClient(), records::add)
              .send(request, HttpResponse.BodyHandlers.ofString());
      assertEquals(response, result.body());
      assertEquals(List.of(body), capturedBody);
      WireLog.Entry sent =
          records.stream()
              .filter(e -> e.direction().equals("REQUEST_BODY"))
              .findFirst()
              .orElseThrow();
      assertEquals(body.replace("login-secret", "***"), sent.body());
      WireLog.Entry header =
          records.stream()
              .filter(e -> e.direction().equals("REQUEST_HEADERS"))
              .findFirst()
              .orElseThrow();
      assertEquals(capturedVersion.get(0), header.headers().get("A2A-Version"));
      assertEquals(List.of("a", "b"), header.headers().get("X-Multi"));
      WireLog.Entry received =
          records.stream()
              .filter(e -> e.direction().equals("RESPONSE_HEADERS"))
              .findFirst()
              .orElseThrow();
      assertEquals(List.of("one", "two"), received.headers().get("x-multi"));
      assertEquals(List.of("***"), received.headers().get("beartoken"));
      assertEquals(1, records.stream().map(WireLog.Entry::requestId).distinct().count());
      String logs = records.toString();
      assertFalse(logs.contains("login-secret"));
      assertFalse(logs.contains("omc-secret"));
      assertFalse(logs.contains("header-secret"));
      assertFalse(logs.contains("request-secret"));
    } finally {
      server.stop(0);
    }
  }

  @Test
  void streamingFramesKeepUtf8ControlLinesAndArriveBeforeCompletion() {
    List<String[]> frames = new ArrayList<>();
    WireLog.Body collector = new WireLog.Body(true, true, frames::add);
    String first = ": heartbeat\r\nevent: result\r\nid: 7\r\nretry: 100\r\ndata: 中文\r\n\r\n";
    for (byte value : first.getBytes(StandardCharsets.UTF_8))
      collector.accept(ByteBuffer.wrap(new byte[] {value}));
    assertEquals(1, frames.size());
    assertEquals(first, frames.get(0)[0]);
    String second = "data: 第二帧\n\n";
    collector.accept(ByteBuffer.wrap(second.getBytes(StandardCharsets.UTF_8)));
    assertEquals(second, frames.get(1)[0]);
    collector.end(false);
    assertEquals(2, frames.size());
  }

  @Test
  void oversizedFramesAreExplicitlyDroppedWithoutLeakingPartialSecrets() {
    System.setProperty("WORKFLOW_ENGINE_PROTOCOL_MAX_BODY_CHARS", "256");
    List<String[]> frames = new ArrayList<>();
    WireLog.Body collector = new WireLog.Body(true, true, frames::add);
    collector.accept(
        ByteBuffer.wrap(
            ("data: {\"password\":\"" + "secret".repeat(100) + "\"}\n\n" + "data: ok\n\n")
                .getBytes(StandardCharsets.UTF_8)));
    assertTrue(frames.get(0)[1].contains("dropped-capacity"));
    assertFalse(frames.get(0)[0].contains("secret"));
    assertEquals("data: ok\n\n", frames.get(1)[0]);
  }

  @Test
  void loggingFailureCannotChangeBodyDeliveryAndBodySwitchIsExplicit() throws Exception {
    List<WireLog.Entry> entries = new ArrayList<>();
    System.setProperty("WORKFLOW_ENGINE_PROTOCOL_INCLUDE_BODY", "false");
    WireLog.emit(
        entries::add,
        "DIRECT_HTTP",
        "REQUEST",
        "1",
        "/test",
        "POST",
        null,
        Map.of(),
        "serialized-utf8",
        "private",
        "",
        Map.of());
    assertTrue(entries.get(0).visibility().contains("disabled"));
    assertFalse(entries.get(0).body().contains("private"));
    assertDoesNotThrow(
        () ->
            WireLog.emit(
                e -> {
                  throw new IllegalStateException("observer");
                },
                "DIRECT_HTTP",
                "REQUEST",
                "1",
                "/test",
                "POST",
                null,
                Map.of(),
                "serialized-utf8",
                "",
                "",
                Map.of()));
  }
}
