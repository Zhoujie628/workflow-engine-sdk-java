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
 *    distributed under the License is distributed on an AS IS BASIS, WITHOUT
 *    WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *    License for the specific language governing permissions and limitations
 *    under the License.
 */
package dev.openan.workflow.engine.examples.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import dev.openan.workflow.engine.examples.testsupport.CapturedLogs;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.a2aproject.sdk.client.TaskEvent;
import org.a2aproject.sdk.spec.AgentCapabilities;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentInterface;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.MessageSendParams;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.Test;

/**
 * Field logs against the real instruction platform show the forwarded SSE staying open after the
 * A2A terminal event: the client received the final frame, then blocked inside the vendor SDK for
 * about 60 seconds until a downstream layer closed the stream. This test reproduces the observed
 * hold-open behavior (an OMC stub that never closes its stream, plus a simulator with
 * close-on-terminal disabled) and verifies the adapter cancels the vendor stream itself once the
 * terminal event is parsed.
 */
class OrderSseTerminalCancelTest {

  @Test
  void terminalEventCancelsTheVendorSseWhileThePlatformHoldsTheStreamOpen() throws Exception {
    var release = new CountDownLatch(1);
    var disconnected = new CountDownLatch(1);
    var requests = new AtomicInteger();
    String terminalFrame =
        "data: "
            + GatewayA2AResponseParserTest.taskJson("task-terminal", "ctx-terminal")
            + "\n\n";
    HttpServer omc = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    omc.createContext(
        "/",
        exchange -> {
          requests.incrementAndGet();
          exchange.getRequestBody().readAllBytes();
          exchange.getResponseHeaders().set("Content-Type", "text/event-stream;charset=UTF-8");
          exchange.sendResponseHeaders(200, 0);
          try {
            exchange.getResponseBody().write(terminalFrame.getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().flush();
            // Hold the stream open like the real platform: keep-alives, no terminal close.
            for (int i = 0; i < 300 && !release.await(100, TimeUnit.MILLISECONDS); i++) {
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
    omc.start();
    int port;
    try (ServerSocket socket = new ServerSocket(0)) {
      port = socket.getLocalPort();
    }
    String target = "http://127.0.0.1:" + omc.getAddress().getPort();
    var config =
        OrderGatewayClientRuntime.OrderConfig.builder()
            .host("127.0.0.1")
            .port(port)
            .username("sim-user")
            .password("sim-password")
            .clientId("sim-client")
            .agentNeRoutes(Map.of("omc", "test-ne"))
            .timeoutSeconds(30)
            .build();
    var runtime = new OrderGatewayClientRuntime(config);
    try (var captured = new CapturedLogs();
        var platform =
            new EastcomOrderSimulatorServer(
                "127.0.0.1",
                port,
                "sim-user",
                "sim-password",
                "sim-client",
                null,
                Map.of("test-ne", target))) {
      platform.setCloseOnTerminalEvent(false);
      platform.start();
      AgentCard card =
          AgentCard.builder()
              .name("omc")
              .description("test")
              .version("1")
              .capabilities(AgentCapabilities.builder().streaming(true).build())
              .defaultInputModes(List.of("text/plain"))
              .defaultOutputModes(List.of("text/plain"))
              .skills(List.of())
              .supportedInterfaces(
                  List.of(new AgentInterface("HTTP+JSON", target + "/a2a/json")))
              .build();
      MessageSendParams params =
          MessageSendParams.builder()
              .message(
                  Message.builder()
                      .role(Message.Role.ROLE_USER)
                      .messageId("test")
                      .parts(new TextPart("diagnose"))
                      .build())
              .build();
      var events = new AtomicInteger();
      var response =
          CompletableFuture.supplyAsync(
              () -> runtime.sendMessage(card, params, null, event -> events.incrementAndGet(), null));
      // The stub holds the stream for ~30s; without terminal cancellation this would time out.
      var result = response.get(5, TimeUnit.SECONDS);
      assertEquals(1, requests.get());
      assertEquals(1, events.get(), "terminal event must be delivered once to the event sink");
      TaskEvent taskEvent = assertInstanceOf(TaskEvent.class, result.iterator().next());
      assertEquals("task-terminal", taskEvent.getTask().id());
      assertEquals(TaskState.TASK_STATE_COMPLETED, taskEvent.getTask().status().state());
      assertTrue(
          disconnected.await(5, TimeUnit.SECONDS),
          "terminal cancellation must release the platform-side forward");
      String evidence = captured.since(0);
      var marker =
          java.util.regex.Pattern.compile("EXPECTED_A2A_TERMINAL_CANCEL requestId=([0-9a-f-]+)")
              .matcher(evidence);
      assertTrue(marker.find(), "vendor warning must carry the expected-cancellation marker");
      assertTrue(
          evidence.contains("SSE_TERMINAL_CANCELLED requestId=" + marker.group(1)),
          "adapter completion log must carry the same request id as the vendor warning");
      release.countDown();
    } finally {
      release.countDown();
      runtime.close();
      omc.stop(0);
    }
  }
}
