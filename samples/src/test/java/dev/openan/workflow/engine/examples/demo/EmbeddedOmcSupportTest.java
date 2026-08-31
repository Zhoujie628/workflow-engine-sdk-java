/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.examples.demo;

import static org.junit.jupiter.api.Assertions.*;

import dev.openan.workflow.engine.client.AgentCardJacksonModule;
import java.util.List;
import org.a2aproject.sdk.spec.AgentCard;
import org.junit.jupiter.api.Test;

class EmbeddedOmcSupportTest {
  @Test
  void explicitFlagControlsStartupAndRejectsTypos() {
    assertFalse(EmbeddedOmcSupport.enabled(new String[] {"--a2a.embedded-omc-enabled=false"}, true));
    assertTrue(EmbeddedOmcSupport.enabled(new String[] {"--a2a.embedded-omc-enabled=true"}, false));
    assertThrows(IllegalArgumentException.class,
        () -> EmbeddedOmcSupport.enabled(new String[] {"--a2a.embedded-omc-enabled=treu"}, true));
  }

  @Test
  void externalModeDoesNotLoadBundledOrExternalCardsForBinding() {
    String previous = System.getProperty("A2A_AGENT_CARD_LOCATIONS");
    System.setProperty("A2A_AGENT_CARD_LOCATIONS", "file:/missing-card-must-not-be-read.json");
    try {
      assertEquals(List.of(), EmbeddedOmcSupport.prepare(false));
    } finally {
      if (previous == null) System.clearProperty("A2A_AGENT_CARD_LOCATIONS");
      else System.setProperty("A2A_AGENT_CARD_LOCATIONS", previous);
    }
  }

  @Test
  void validatesBothCardsBeforeStartingEitherServer() throws Exception {
    var city1 = card("city1", "127.0.0.1:26335");
    var city2 = card("city2", "192.0.2.17:26336");
    assertThrows(IllegalArgumentException.class,
        () -> EmbeddedOmcSupport.validateTargets(List.of(city1, city2)));
    assertThrows(IllegalArgumentException.class,
        () -> EmbeddedOmcSupport.validateTargets(List.of(city1)));
  }

  @Test
  void usesConfiguredLocalCardsInsteadOfBundledDefaultPorts() throws Exception {
    var city1 = card("city1", "localhost:27335");
    var city2 = card("city2", "127.0.0.1:27336");
    assertEquals(List.of(city1, city2), EmbeddedOmcSupport.validateTargets(List.of(city2, city1)));
  }

  @Test
  void launchesTypedCardWithoutLosingSecurityScheme() throws Exception {
    var card = card("city1", "127.0.0.1:0");
    try (var launcher = new dev.openan.workflow.engine.examples.server.OmcAgentLauncher()) {
      var server = launcher.startFromCard(card,
          new org.a2aproject.sdk.server.agentexecution.AgentExecutor() {
            @Override public void execute(
                org.a2aproject.sdk.server.agentexecution.RequestContext context,
                org.a2aproject.sdk.server.tasks.AgentEmitter emitter) {
              throw new AssertionError("Binding must not dispatch a task");
            }
            @Override public void cancel(
                org.a2aproject.sdk.server.agentexecution.RequestContext context,
                org.a2aproject.sdk.server.tasks.AgentEmitter emitter) {}
          });
      assertEquals(card.name(), server.getAgentName());
      var schemes = (java.util.Map<?, ?>) server.getAgentCard().get("securitySchemes");
      var bearer = (java.util.Map<?, ?>) schemes.values().iterator().next();
      assertTrue(bearer.containsKey("httpAuthSecurityScheme"));
    }
  }

  private AgentCard card(String city, String address) throws Exception {
    try (var input = getClass().getResourceAsStream("/agentcard/spn_domain_agent_" + city + ".json")) {
      String json = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
      json = json.replace(city.equals("city1") ? "127.0.0.1:26335" : "127.0.0.1:26336", address);
      return new com.fasterxml.jackson.databind.ObjectMapper()
          .registerModule(new AgentCardJacksonModule()).readValue(json, AgentCard.class);
    }
  }
}
