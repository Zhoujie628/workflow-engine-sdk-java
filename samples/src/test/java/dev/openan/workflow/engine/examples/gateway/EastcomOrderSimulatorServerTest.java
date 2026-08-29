/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.examples.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.eastcom.apollo.orders.internal.shaded.com.google.protobuf.ByteString;
import com.eastcom.apollo.orders.internal.shaded.v11x.com.eastcom.apollo.orders.client.core.common.ServerInfo;
import com.eastcom.apollo.orders.internal.shaded.v11x.com.eastcom.apollo.orders.client.http.HttpClient;
import com.eastcom.apollo.orders.internal.shaded.v11x.com.eastcom.apollo.orders.client.http.HttpRequestConfig;
import dev.openan.workflow.engine.examples.config.OrderGatewayProperties;
import java.net.ServerSocket;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EastcomOrderSimulatorServerTest {

  @Test
  void requestDecoderSupportsUtf8AndTheVendorWindowsLegacyEncoding() {
    String request = "## 授权策略的操作类型\n新增授权策略";
    assertEquals(
        request,
        EastcomOrderSimulatorServer.decodeRequestData(
            ByteString.copyFrom(request, StandardCharsets.UTF_8)));
    assertEquals(
        request,
        EastcomOrderSimulatorServer.decodeRequestData(
            ByteString.copyFrom(request, Charset.forName("GB18030"))));
  }

  @Test
  void documentedHttpClientCreateFlowCanLoadNeResource() throws Exception {
    int port = availablePort();
    try (EastcomOrderSimulatorServer server =
        new EastcomOrderSimulatorServer(
            "127.0.0.1",
            port,
            "sim-user",
            "sim-password",
            "sim-client",
            "sim-secret",
            Map.of("sim-city1", "https://127.0.0.1:26335"))) {
      server.start();
      ServerInfo serverInfo =
          ServerInfo.builder()
              .host("127.0.0.1")
              .port(port)
              .username("sim-user")
              .password("sim-password")
              .clientId("sim-client")
              .clientSecret("sim-secret")
              .build();
      HttpRequestConfig config = HttpRequestConfig.builder().deviceName("sim-city1").build();
      // v1.8 documents create(serverInfo, config) for the HTTP forwarding path.
      HttpClient client = HttpClient.create(serverInfo, config);
      assertTrue(client != null, "HttpClient.create should return a client");
    }
  }

  @Test
  void simulatorCanRestartOnSameAddressAfterClosing() throws Exception {
    int port = availablePort();
    EastcomOrderSimulatorServer first = simulator(port);
    first.start();
    first.close();

    try (EastcomOrderSimulatorServer restarted = simulator(port)) {
      restarted.start();
    }
  }

  @Test
  void tokenServiceObtainsBearerHeaderThroughTheVendorHttpClient() throws Exception {
    int platformPort = availablePort();
    int omcPort = availablePort();
    com.sun.net.httpserver.HttpServer omc =
        com.sun.net.httpserver.HttpServer.create(
            new java.net.InetSocketAddress("127.0.0.1", omcPort), 0);
    omc.createContext(
        "/rest/plat/smapp/v1/oauth/token",
        exchange -> {
          String body =
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          assertTrue(body.contains("omc-user"));
          assertTrue(body.contains("city1-password"));
          assertFalse(body.contains("sim-password"));
          exchange.getResponseHeaders().set("bearToken", "omc-token-through-eastcom");
          byte[] response = "{}".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, response.length);
          exchange.getResponseBody().write(response);
          exchange.close();
        });
    omc.start();
    try (EastcomOrderSimulatorServer server =
        new EastcomOrderSimulatorServer(
            "127.0.0.1",
            platformPort,
            "sim-user",
            "sim-password",
            "sim-client",
            "sim-secret",
            Map.of("sim-city1", "http://127.0.0.1:" + omcPort))) {
      server.start();
      OrderGatewayProperties properties = new OrderGatewayProperties();
      properties.setHost("127.0.0.1");
      properties.setPort(platformPort);
      properties.setUsername("sim-user");
      properties.setPassword("sim-password");
      properties.setClientId("sim-client");
      properties.setClientSecret("sim-secret");
      properties.setCity1Ne("sim-city1");

      assertEquals(
          "omc-token-through-eastcom",
          new EastcomTokenService(properties, "classpath:test-order-credentials.json")
              .getOrRefresh("SPN Domain Agent City1"));
    } finally {
      omc.stop(0);
    }
  }

  @Test
  void tokenServiceFallsBackToConfiguredBodyField() throws Exception {
    int platformPort = availablePort();
    int omcPort = availablePort();
    com.sun.net.httpserver.HttpServer omc =
        com.sun.net.httpserver.HttpServer.create(
            new java.net.InetSocketAddress("127.0.0.1", omcPort), 0);
    omc.createContext(
        "/rest/plat/smapp/v1/oauth/token",
        exchange -> {
          byte[] response =
              "{\"accessSession\":\"body-token-through-eastcom\"}".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, response.length);
          exchange.getResponseBody().write(response);
          exchange.close();
        });
    omc.start();
    try (EastcomOrderSimulatorServer server =
        new EastcomOrderSimulatorServer(
            "127.0.0.1",
            platformPort,
            "sim-user",
            "sim-password",
            "sim-client",
            "sim-secret",
            Map.of("sim-city1", "http://127.0.0.1:" + omcPort))) {
      server.start();
      OrderGatewayProperties properties = new OrderGatewayProperties();
      properties.setHost("127.0.0.1");
      properties.setPort(platformPort);
      properties.setUsername("sim-user");
      properties.setPassword("sim-password");
      properties.setClientId("sim-client");
      properties.setClientSecret("sim-secret");
      properties.setCity1Ne("sim-city1");

      assertEquals(
          "body-token-through-eastcom",
          new EastcomTokenService(properties, "classpath:test-order-credentials.json")
              .getOrRefresh("SPN Domain Agent City1"));
    } finally {
      omc.stop(0);
    }
  }

  private static EastcomOrderSimulatorServer simulator(int port) {
    return new EastcomOrderSimulatorServer(
        "127.0.0.1",
        port,
        "sim-user",
        "sim-password",
        "sim-client",
        "sim-secret",
        Map.of("sim-city1", "https://127.0.0.1:26335"));
  }

  private static int availablePort() throws Exception {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }
}
