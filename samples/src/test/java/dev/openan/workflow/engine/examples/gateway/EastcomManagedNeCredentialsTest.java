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

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.openan.workflow.engine.examples.config.OrderGatewayProperties;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EastcomManagedNeCredentialsTest {
  @Test
  void supportsEncryptedSimulatorPasswords() {
    String previous = System.getProperty("A2AT_CRED_KEY");
    System.setProperty("A2AT_CRED_KEY", "10".repeat(32));
    try {
      String encrypted = dev.openan.workflow.engine.client.CredentialCrypto.encrypt("test-secret", null);
      var credentials = new EastcomOrderSimulatorServer.NeCredentials("test-user", encrypted);
      assertEquals("test-secret", credentials.password());
      assertFalse(credentials.toString().contains("test-secret"));
    } finally {
      if (previous == null) System.clearProperty("A2AT_CRED_KEY");
      else System.setProperty("A2AT_CRED_KEY", previous);
    }
  }

  @Test
  void yamlResolvesSimulatorCredentialsFromEnvironmentPlaceholders() throws Exception {
    var environment = new org.springframework.core.env.StandardEnvironment();
    var sources = environment.getPropertySources();
    sources.remove(org.springframework.core.env.StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
    sources.remove(org.springframework.core.env.StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
    sources.addFirst(new org.springframework.core.env.MapPropertySource("test", Map.of(
        "EASTCOM_ORDER_CITY1_NE", "ne-one",
        "EASTCOM_ORDER_CITY2_NE", "ne-two",
        "EASTCOM_ORDER_SIMULATOR_CITY1_USERNAME", "configured-one",
        "EASTCOM_ORDER_SIMULATOR_CITY1_PASSWORD", "configured-one-secret",
        "EASTCOM_ORDER_SIMULATOR_CITY2_USERNAME", "configured-two",
        "EASTCOM_ORDER_SIMULATOR_CITY2_PASSWORD", "configured-two-secret")));
    new org.springframework.boot.env.YamlPropertySourceLoader()
        .load("sample", new org.springframework.core.io.ClassPathResource("application.yml"))
        .forEach(sources::addLast);
    var properties = org.springframework.boot.context.properties.bind.Binder.get(environment)
        .bind("a2a.order", OrderGatewayProperties.class).get();
    var credentials = EastcomOrderSimulatorConfiguration.simulatorCredentials(properties);
    assertEquals("configured-one", credentials.get(properties.getCity1Ne()).username());
    assertEquals("configured-two-secret", credentials.get(properties.getCity2Ne()).password());
  }

  @Test
  void substitutesJsonSafelyAndDoesNotOverrideExplicitCredentials() throws Exception {
    var credentials = new EastcomOrderSimulatorServer.NeCredentials("user\"one", "secret\\\"one");
    var mapper = new ObjectMapper();
    String input = mapper.writeValueAsString(Map.of(
        "userName", "${ne:username}", "value", "${ne:password}", "grantType", "${ne:grantType}"));
    var output = mapper.readTree(EastcomOrderSimulatorServer.substituteCredentials(input, credentials));
    assertEquals(credentials.username(), output.path("userName").asText());
    assertEquals(credentials.password(), output.path("value").asText());
    assertEquals("password", output.path("grantType").asText());
    String explicit = "{\"userName\":\"explicit\",\"value\":\"explicit-secret\"}";
    assertEquals(explicit, EastcomOrderSimulatorServer.substituteCredentials(explicit, credentials));
    assertFalse(credentials.toString().contains("secret"));
    assertFalse(credentials.toString().contains("user\"one"));
    var error = assertThrows(IllegalArgumentException.class,
        () -> EastcomOrderSimulatorServer.substituteCredentials("secret ${ne:password}", credentials));
    assertFalse(error.toString().contains("secret"));
    assertNull(error.getCause());
  }

  @Test
  void concurrentNesSharingOneTargetUseTheirOwnManagedCredentials(@TempDir Path temp) throws Exception {
    var mapper = new ObjectMapper();
    Map<String, String> passwords = Map.of("city-one", "one-secret", "city-two", "two-secret");
    HttpServer omc = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    omc.createContext("/custom/login", exchange -> {
      var body = mapper.readTree(exchange.getRequestBody());
      String username = body.path("userName").asText();
      boolean accepted = passwords.containsKey(username)
          && passwords.get(username).equals(body.path("value").asText());
      byte[] response = mapper.writeValueAsBytes(accepted
          ? Map.of("accessSession", username + "-token") : Map.of("error", "invalid credentials"));
      exchange.sendResponseHeaders(accepted ? 200 : 401, response.length);
      exchange.getResponseBody().write(response);
      exchange.close();
    });
    omc.start();
    int platformPort;
    try (ServerSocket socket = new ServerSocket(0)) {
      platformPort = socket.getLocalPort();
    }
    String target = "http://127.0.0.1:" + omc.getAddress().getPort();
    var properties = new OrderGatewayProperties();
    properties.setHost("127.0.0.1");
    properties.setPort(platformPort);
    properties.setUsername("platform-user");
    properties.setPassword("platform-secret");
    properties.setClientId("platform-client");
    properties.setCity1Ne("ne-one");
    properties.setCity2Ne("ne-two");
    properties.setSimulatorCity1Username("city-one");
    properties.setSimulatorCity1Password("one-secret");
    properties.setSimulatorCity2Username("city-two");
    properties.setSimulatorCity2Password("two-secret");
    var scheme = Map.of("bearerAuth", Map.of("login_url", target + "/custom/login", "method", "PUT",
        "token_field", "accessSession"));
    Path path = temp.resolve("credentials.json");
    mapper.writeValue(path.toFile(), Map.of(
        "SPN Domain Agent City1", scheme, "SPN Domain Agent City2", scheme));
    try (var simulator = new EastcomOrderSimulatorServer(
        "127.0.0.1", platformPort, "platform-user", "platform-secret", "platform-client", null,
        Map.of("ne-one", target, "ne-two", target), 3000, 3000,
        EastcomOrderSimulatorConfiguration.simulatorCredentials(properties))) {
      simulator.start();
      var tokens = new EastcomTokenService(properties, path.toString());
      var one = CompletableFuture.supplyAsync(() -> tokens.getOrRefresh("SPN Domain Agent City1"));
      var two = CompletableFuture.supplyAsync(() -> tokens.getOrRefresh("SPN Domain Agent City2"));
      assertEquals("city-one-token", one.get(10, TimeUnit.SECONDS));
      assertEquals("city-two-token", two.get(10, TimeUnit.SECONDS));
    } finally {
      omc.stop(0);
    }
  }
}
