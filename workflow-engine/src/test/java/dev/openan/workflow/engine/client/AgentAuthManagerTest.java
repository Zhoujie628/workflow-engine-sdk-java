/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.openan.workflow.engine.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URL;
import org.junit.jupiter.api.Test;

class AgentAuthManagerTest {

  @Test
  void loadsCredentialsFromClasspathLocation() throws Exception {
    URL resource = getClass().getClassLoader().getResource("test-agent-credentials.json");
    assertNotNull(resource, "test resource must exist on classpath");
    AgentAuthManager manager = new AgentAuthManager(resource.getPath());

    var config = manager.getConfig("Test Agent");

    assertNotNull(config);
    assertEquals("https://auth.example.test/token", config.get("bearerAuth").get("login_url"));
  }

  @Test
  void loadsCredentialsFromClasspathPrefix() {
    AgentAuthManager manager = new AgentAuthManager("classpath:test-agent-credentials.json");

    var config = manager.getConfig("Test Agent");

    assertNotNull(config);
    assertEquals("https://auth.example.test/token", config.get("bearerAuth").get("login_url"));
  }

  @Test
  void resolvesSharedProfilesAndPerAgentOverrides() {
    AgentAuthManager manager = new AgentAuthManager("classpath:test-profile-credentials.json");

    var city1 = manager.getConfig("City 1").get("bearerAuth");
    var city2 = manager.getConfig("City 2").get("bearerAuth");

    assertEquals("https://city1.example.test/token", city1.get("login_url"));
    assertEquals("https://shared.example.test/token", city2.get("login_url"));
    assertEquals(
        "shared-user", ((java.util.Map<?, ?>) city1.get("request_fields")).get("userName"));
    assertEquals(
        "city1-password", ((java.util.Map<?, ?>) city1.get("request_fields")).get("value"));
    assertEquals(
        "shared-password", ((java.util.Map<?, ?>) city2.get("request_fields")).get("value"));
  }

  @Test
  void missingFileFailsClosed() {
    assertThrows(
        IllegalStateException.class,
        () -> new AgentAuthManager("/nonexistent/missing-agent-credentials.json"));
  }

  @Test
  void missingClasspathResourceFailsClosed() {
    assertThrows(
        IllegalStateException.class,
        () -> new AgentAuthManager("classpath:nonexistent-credentials.json"));
  }
}
