/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.examples.gateway;

import dev.openan.workflow.engine.examples.config.OrderGatewayProperties;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Spring-managed lifecycle for the sample-only Eastcom protocol simulator. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression(
    "'${a2a.transport-mode:order}'.equalsIgnoreCase('order')"
        + " && '${a2a.order.simulator-enabled:false}'.equalsIgnoreCase('true')")
public class EastcomOrderSimulatorConfiguration {

  static Map<String, String> simulatorTargets(OrderGatewayProperties properties) {
    Map<String, String> targets = new LinkedHashMap<>();
    putTarget(targets, properties.getCity1Ne(), properties.getSimulatorCity1TargetUrl(), "city1");
    putTarget(targets, properties.getCity2Ne(), properties.getSimulatorCity2TargetUrl(), "city2");
    return targets;
  }

  private static void putTarget(
      Map<String, String> targets, String ne, String targetUrl, String city) {
    if (ne == null || ne.isBlank()) {
      throw new IllegalArgumentException("a2a.order." + city + "-ne is required");
    }
    String normalizedTarget = normalizeTarget(targetUrl, city);
    String previous = targets.putIfAbsent(ne.trim(), normalizedTarget);
    if (previous != null && !previous.equals(normalizedTarget)) {
      throw new IllegalArgumentException(
          "The same simulator NE cannot map to two target URLs: " + ne);
    }
  }

  private static String normalizeTarget(String targetUrl, String city) {
    if (targetUrl == null || targetUrl.isBlank()) {
      throw new IllegalArgumentException("a2a.order.simulator-" + city + "-target-url is required");
    }
    URI uri;
    try {
      uri = URI.create(targetUrl.trim());
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "a2a.order.simulator-" + city + "-target-url is invalid", e);
    }
    if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
        || uri.getHost() == null) {
      throw new IllegalArgumentException(
          "a2a.order.simulator-" + city + "-target-url must be an absolute HTTP(S) URL");
    }
    String normalized = uri.toString();
    return normalized.endsWith("/") ? normalized.substring(0, normalized.length() - 1) : normalized;
  }

  @Bean(destroyMethod = "close")
  EastcomOrderSimulatorServer eastcomOrderSimulatorServer(OrderGatewayProperties properties) {
    EastcomOrderSimulatorServer server =
        new EastcomOrderSimulatorServer(
            properties.getHost(),
            properties.getPort(),
            properties.getUsername(),
            properties.getPassword(),
            properties.getClientId(),
            properties.getClientSecret(),
            simulatorTargets(properties),
            Math.multiplyExact(properties.getSimulatorConnectTimeoutSeconds(), 1_000),
            Math.multiplyExact(properties.getSimulatorReadTimeoutSeconds(), 1_000));
    server.start();
    return server;
  }
}
