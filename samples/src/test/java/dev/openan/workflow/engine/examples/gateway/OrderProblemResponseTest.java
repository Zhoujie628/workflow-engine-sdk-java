/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.examples.gateway;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import dev.openan.workflow.engine.client.RemoteProblemException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.a2aproject.sdk.spec.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OrderProblemResponseTest {
  @ParameterizedTest
  @ValueSource(ints = {400, 429})
  void vendorSseProblemStopsTheCallerBeforeTheOmcClosesItsStream(int status) throws Exception {
    var release = new CountDownLatch(1);
    var disconnected = new CountDownLatch(1);
    var requests = new AtomicInteger();
    HttpServer omc = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    omc.createContext("/", exchange -> {
      requests.incrementAndGet();
      exchange.getRequestBody().readAllBytes();
      exchange.getResponseHeaders().set("Content-Type", "text/event-stream;charset=UTF-8");
      exchange.sendResponseHeaders(200, 0);
      try {
        byte[] data = ("data: {\"status\":" + status
            + ",\"detail\":\"OMC拒绝本次请求\",\"type\":\"\"}\n\n").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseBody().write(data);
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
    omc.start();
    int port;
    try (ServerSocket socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
    String target = "http://127.0.0.1:" + omc.getAddress().getPort();
    var config = OrderGatewayClientRuntime.OrderConfig.builder()
        .host("127.0.0.1").port(port).username("sim-user").password("sim-password")
        .clientId("sim-client").agentNeRoutes(Map.of("omc", "test-ne")).timeoutSeconds(10).build();
    var runtime = new OrderGatewayClientRuntime(config);
    try (var platform = new EastcomOrderSimulatorServer(
        "127.0.0.1", port, "sim-user", "sim-password", "sim-client", null,
        Map.of("test-ne", target))) {
      platform.start();
      AgentCard card = AgentCard.builder().name("omc").description("test").version("1")
          .capabilities(AgentCapabilities.builder().streaming(true).build())
          .defaultInputModes(List.of("text/plain")).defaultOutputModes(List.of("text/plain"))
          .skills(List.of()).supportedInterfaces(List.of(new AgentInterface("HTTP+JSON", target + "/a2a/json")))
          .build();
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
      assertEquals("OMC拒绝本次请求", problem.getDetail());
      assertEquals(0, events.get());
      assertEquals(1, requests.get());
      assertTrue(disconnected.await(3, TimeUnit.SECONDS), "failed forwarding must close its OMC connection");
      release.countDown();
    } finally {
      release.countDown();
      runtime.close();
      omc.stop(0);
    }
  }
}
