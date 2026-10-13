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
import static org.junit.jupiter.api.Assertions.assertFalse;
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

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
  @org.junit.jupiter.api.Timeout(30)
  void notificationTerminalResultsDoNotCancelEitherForwardingLayer(boolean simulatorClosesTasks)
      throws Exception {
    String extension = dev.openan.workflow.engine.client.A2ATExtension.NOTIFICATION_T.uri();
    var release = new CountDownLatch(1);
    var disconnected = new CountDownLatch(1);
    var received = new CountDownLatch(4);
    var activities = new CountDownLatch(15);
    var events = new java.util.concurrent.CopyOnWriteArrayList<org.a2aproject.sdk.client.ClientEvent>();
    var recovery = org.a2aproject.sdk.spec.Task.builder().id("notice-0").contextId("subscription")
        .status(new org.a2aproject.sdk.spec.TaskStatus(TaskState.TASK_STATE_WORKING))
        .artifacts(List.of(org.a2aproject.sdk.spec.Artifact.builder().artifactId("recovery-result")
            .name("recovery-result").parts(List.of(new TextPart("synthetic recovery result"))).build()))
        .build();
    String[] frames = {
      "data: " + com.google.protobuf.util.JsonFormat.printer().print(
          org.a2aproject.sdk.grpc.StreamResponse.newBuilder()
              .setTask(org.a2aproject.sdk.grpc.utils.ProtoUtils.ToProto.task(recovery)).build()) + "\n\n",
      "data: " + GatewayA2AResponseParserTest.taskJson("notice-1", "subscription") + "\n\n",
      "data: " + GatewayA2AResponseParserTest.taskJson("notice-2", "subscription", TaskState.TASK_STATE_FAILED) + "\n\n",
      "data: " + com.google.protobuf.util.JsonFormat.printer().print(
          org.a2aproject.sdk.grpc.StreamResponse.newBuilder().setStatusUpdate(
              org.a2aproject.sdk.grpc.utils.ProtoUtils.ToProto.taskStatusUpdateEvent(
                  new org.a2aproject.sdk.spec.TaskStatusUpdateEvent("notice-3",
                      new org.a2aproject.sdk.spec.TaskStatus(TaskState.TASK_STATE_CANCELED),
                      "subscription", Map.of()))).build()) + "\n\n"
    };
    HttpServer omc = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var observedExtension = new java.util.concurrent.atomic.AtomicReference<String>();
    omc.createContext("/", exchange -> {
      observedExtension.set(exchange.getRequestHeaders().getFirst("A2A-Extensions"));
      exchange.getRequestBody().readAllBytes();
      exchange.getResponseHeaders().set("Content-Type", "text/event-stream;charset=UTF-8");
      exchange.sendResponseHeaders(200, 0);
      try {
        for (int i = 0; i < 200 && !release.await(100, TimeUnit.MILLISECONDS); i++) {
          String frame = i == 0 ? frames[0] : i == 3 ? frames[1] : i == 6 ? frames[2]
              : i == 9 ? frames[3] : ": heartbeat\n\n";
          exchange.getResponseBody().write(frame.getBytes(StandardCharsets.UTF_8));
          exchange.getResponseBody().flush();
        }
      } catch (java.io.IOException closed) {
        disconnected.countDown();
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      } finally {
        exchange.close();
      }
    });
    omc.start();
    int port;
    try (ServerSocket socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
    String target = "http://127.0.0.1:" + omc.getAddress().getPort();
    var runtime = new OrderGatewayClientRuntime(OrderGatewayClientRuntime.OrderConfig.builder()
        .host("127.0.0.1").port(port).username("sim-user").password("sim-password")
        .clientId("sim-client").agentNeRoutes(Map.of("omc", "test-ne")).timeoutSeconds(1).build());
    var worker = java.util.concurrent.Executors.newSingleThreadExecutor();
    java.util.concurrent.Future<?> response = null;
    try (var platform = new EastcomOrderSimulatorServer("127.0.0.1", port, "sim-user",
        "sim-password", "sim-client", null, Map.of("test-ne", target))) {
      platform.setCloseOnTerminalEvent(simulatorClosesTasks);
      platform.start();
      AgentCard card = AgentCard.builder().name("omc").description("test").version("1")
          .capabilities(AgentCapabilities.builder().streaming(true).build())
          .defaultInputModes(List.of("text/plain")).defaultOutputModes(List.of("text/plain"))
          .skills(List.of()).supportedInterfaces(List.of(new AgentInterface("HTTP+JSON", target + "/a2a/json"))).build();
      MessageSendParams params = MessageSendParams.builder().message(Message.builder()
          .role(Message.Role.ROLE_USER).messageId("subscribe").contextId("subscription")
          .metadata(Map.of(extension, "subscribe")).parts(new TextPart("subscribe")).build()).build();
      var context = new org.a2aproject.sdk.client.transport.spi.interceptors.ClientCallContext(
          new java.util.HashMap<>(), new java.util.HashMap<>());
      context.getHeaders().put("A2A-Extensions", extension);
      context.getState().put(dev.openan.workflow.engine.client.A2AJavaClientRuntime.TRANSPORT_ACTIVITY_STATE_KEY,
          (Runnable) activities::countDown);
      long streamStarted = System.nanoTime();
      response = worker.submit(() -> runtime.sendMessage(card, params, context, event -> {
        events.add(event);
        received.countDown();
      }, null));
      assertTrue(received.await(5, TimeUnit.SECONDS), "all independent notification results must arrive on one stream");
      assertTrue(activities.await(5, TimeUnit.SECONDS), "heartbeats must continue after terminal results");
      assertTrue(System.nanoTime() - streamStarted > TimeUnit.SECONDS.toNanos(1),
          "the active stream must outlive the configured ordinary response timeout");
      assertFalse(response.isDone(), "Notification-T must stay open after terminal states and isFinal");
      assertEquals(extension, observedExtension.get());
      assertEquals(4, events.size(), "heartbeats are not business events");
      assertEquals("notice-3", ((org.a2aproject.sdk.client.TaskUpdateEvent) events.get(3)).getTask().id(),
          "a new notification must not inherit the previous task identity");
      assertTrue(((org.a2aproject.sdk.spec.TaskStatusUpdateEvent)
          ((org.a2aproject.sdk.client.TaskUpdateEvent) events.get(3)).getUpdateEvent()).isFinal());
      response.cancel(true); // The subscription owner explicitly unsubscribes by interrupting its worker.
      assertTrue(disconnected.await(5, TimeUnit.SECONDS), "explicit cancellation must release the upstream HTTP stream");
    } finally {
      if (response != null) response.cancel(true);
      release.countDown();
      worker.shutdownNow();
      runtime.close();
      omc.stop(0);
    }
  }

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
    // Production intentionally silences this vendor logger. Enable it only while observing
    // the cancellation marker, then restore the original policy (do not weaken assertions).
    String vendorLogger = "com.eastcom.apollo.orders.internal.shaded.v11x.com.eastcom.apollo.orders.client.http.internal.RequestBodyUriSpec";
    var logging = (org.apache.logging.log4j.core.LoggerContext)
        org.apache.logging.log4j.LogManager.getContext(false);
    var previousLevel = logging.getConfiguration().getLoggerConfig(vendorLogger).getLevel();
    org.apache.logging.log4j.core.config.Configurator.setLevel(vendorLogger, org.apache.logging.log4j.Level.WARN);
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
      org.apache.logging.log4j.core.config.Configurator.setLevel(vendorLogger, previousLevel);
      release.countDown();
      runtime.close();
      omc.stop(0);
    }
  }
}
