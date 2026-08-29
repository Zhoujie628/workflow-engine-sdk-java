/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.examples.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.openan.workflow.engine.examples.config.OrderGatewayProperties;
import org.junit.jupiter.api.Test;

class EastcomOrderSimulatorConfigurationTest {

  @Test
  void mapsEachNeToItsConfiguredTarget() {
    OrderGatewayProperties properties = new OrderGatewayProperties();
    properties.setCity1Ne("city-one");
    properties.setCity2Ne("city-two");
    properties.setSimulatorCity1TargetUrl("https://omc.example.test:26335");
    properties.setSimulatorCity2TargetUrl("https://omc.example.test:26335");

    var targets = EastcomOrderSimulatorConfiguration.simulatorTargets(properties);

    assertEquals("https://omc.example.test:26335", targets.get("city-one"));
    assertEquals("https://omc.example.test:26335", targets.get("city-two"));
  }

  @Test
  void rejectsOneNeMappedToDifferentTargets() {
    OrderGatewayProperties properties = new OrderGatewayProperties();
    properties.setCity1Ne("shared-ne");
    properties.setCity2Ne("shared-ne");
    properties.setSimulatorCity1TargetUrl("https://omc.example.test:26335");
    properties.setSimulatorCity2TargetUrl("https://omc.example.test:26336");

    assertThrows(
        IllegalArgumentException.class,
        () -> EastcomOrderSimulatorConfiguration.simulatorTargets(properties));
  }

  @Test
  void rejectsInvalidRemoteTargetBeforeStartingTheSimulator() {
    OrderGatewayProperties properties = new OrderGatewayProperties();
    properties.setSimulatorCity1TargetUrl("remote-omc:26335");

    assertThrows(
        IllegalArgumentException.class,
        () -> EastcomOrderSimulatorConfiguration.simulatorTargets(properties));
  }
}
