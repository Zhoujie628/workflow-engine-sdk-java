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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.openan.workflow.engine.examples.config.OrderGatewayProperties;
import org.junit.jupiter.api.Test;

class EastcomOrderSimulatorConfigurationTest {

  @Test
  void mapsCredentialsByNeAndRejectsConflictingSharedNeCredentials() {
    OrderGatewayProperties properties = new OrderGatewayProperties();
    properties.setSimulatorCity1Username("omc-one");
    properties.setSimulatorCity1Password("one-secret");
    properties.setSimulatorCity2Username("omc-two");
    properties.setSimulatorCity2Password("two-secret");
    var credentials = EastcomOrderSimulatorConfiguration.simulatorCredentials(properties);
    assertEquals("omc-one", credentials.get(properties.getCity1Ne()).username());
    assertEquals("two-secret", credentials.get(properties.getCity2Ne()).password());
    properties.setCity2Ne(properties.getCity1Ne());
    assertThrows(
        IllegalArgumentException.class,
        () -> EastcomOrderSimulatorConfiguration.simulatorCredentials(properties));
  }

  @Test
  void rejectsPartialCredentialsAndAllowsIdenticalSharedNeCredentials() {
    OrderGatewayProperties properties = new OrderGatewayProperties();
    properties.setCity2Ne(properties.getCity1Ne());
    assertEquals(1, EastcomOrderSimulatorConfiguration.simulatorCredentials(properties).size());
    properties.setSimulatorCity2Password("");
    assertThrows(
        IllegalArgumentException.class,
        () -> EastcomOrderSimulatorConfiguration.simulatorCredentials(properties));
  }

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
