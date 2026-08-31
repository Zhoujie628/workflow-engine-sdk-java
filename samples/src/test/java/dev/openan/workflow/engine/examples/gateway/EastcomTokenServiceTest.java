/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.examples.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class EastcomTokenServiceTest {

  @Test
  void defaultTokenHeaderMatchesPackagedConfigurationAndProfiles() throws Exception {
    assertEquals(
        "accessSession",
        new dev.openan.workflow.engine.examples.config.OrderGatewayProperties()
            .getOmcTokenResponseHeader());
    var sources = new org.springframework.core.env.MutablePropertySources();
    new org.springframework.boot.env.YamlPropertySourceLoader()
        .load("sample", new org.springframework.core.io.ClassPathResource("application.yml"))
        .forEach(sources::addLast);
    assertEquals(
        "accessSession",
        new org.springframework.core.env.PropertySourcesPropertyResolver(sources)
            .getProperty("a2a.order.omc-token-response-header"));
    var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
    for (String path :
        new String[] {"/spn_agent_credentials.json", "/test-order-credentials.json"}) {
      try (var input = getClass().getResourceAsStream(path)) {
        var headers = mapper.readTree(input).findValues("order_token_header");
        assertEquals(1, headers.size(), path);
        assertEquals("accessSession", headers.get(0).asText(), path);
      }
    }
  }

  @Test
  void resolvesAgentRouteAndCachesTokenUntilInvalidated() {
    AtomicInteger fetches = new AtomicInteger();
    EastcomTokenService service =
        new EastcomTokenService(
            Map.of("city1", "ne-1"),
            null,
            Duration.ofHours(1),
            Clock.fixed(Instant.parse("2026-08-26T00:00:00Z"), ZoneOffset.UTC),
            (agent, ne) -> ne + "-token-" + fetches.incrementAndGet());

    assertEquals("ne-1-token-1", service.getOrRefresh("city1"));
    assertEquals("ne-1-token-1", service.getOrRefresh("city1"));
    service.invalidate("city1");
    assertEquals("ne-1-token-2", service.getOrRefresh("city1"));
    assertEquals(2, fetches.get());
  }

  @Test
  void rejectsAgentWithoutAnNeRoute() {
    EastcomTokenService service =
        new EastcomTokenService(
            Map.of(), null, Duration.ofHours(1), Clock.systemUTC(), (agent, ne) -> "unused");

    assertThrows(IllegalArgumentException.class, () -> service.getOrRefresh("unknown"));
  }
}
