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
package dev.openan.workflow.engine.examples.demo;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DemoNegotiationSettingsTest {
  private String enabled;
  private String city;

  @BeforeEach
  void isolateSettings() {
    enabled = System.getProperty("a2at.samples.negotiation");
    city = System.getProperty("a2at.samples.negotiation.city");
    System.clearProperty("a2at.samples.negotiation");
    System.clearProperty("a2at.samples.negotiation.city");
  }

  @AfterEach
  void restoreSettings() {
    if (enabled == null) System.clearProperty("a2at.samples.negotiation");
    else System.setProperty("a2at.samples.negotiation", enabled);
    if (city == null) System.clearProperty("a2at.samples.negotiation.city");
    else System.setProperty("a2at.samples.negotiation.city", city);
  }

  @Test
  void localDefaultSelectsOnlyCity1AndDoesNotMutateGlobalSettings() {
    assertTrue(SpnCasePrompts.demoNegotiationEnabled(true));
    assertTrue(SpnCasePrompts.injectNegotiation("diagnosis_city1", true));
    assertFalse(SpnCasePrompts.injectNegotiation("diagnosis_city2", true));
    assertFalse(SpnCasePrompts.injectNegotiation("merge_analysis", true));
    assertNull(System.getProperty("a2at.samples.negotiation"));
  }

  @Test
  void explicitDisableKeepsCompleteInputs() {
    System.setProperty("a2at.samples.negotiation", "false");
    assertFalse(SpnCasePrompts.demoNegotiationEnabled(true));
    assertFalse(SpnCasePrompts.injectNegotiation("diagnosis_city1", false));
  }

  @Test
  void externalOmcNeverGetsDefaultInjectionAndRejectsExplicitInjection() {
    assertFalse(SpnCasePrompts.demoNegotiationEnabled(false));
    System.setProperty("a2at.samples.negotiation", "true");
    assertThrows(
        IllegalArgumentException.class, () -> SpnCasePrompts.demoNegotiationEnabled(false));
  }

  @Test
  void invalidSwitchOrCityFailsBeforeStartingServers() {
    System.setProperty("a2at.samples.negotiation", "typo");
    assertThrows(IllegalArgumentException.class, () -> SpnCasePrompts.demoNegotiationEnabled(true));
    System.clearProperty("a2at.samples.negotiation");
    System.setProperty("a2at.samples.negotiation.city", "unknown");
    assertThrows(IllegalArgumentException.class, () -> SpnCasePrompts.demoNegotiationEnabled(true));
  }
}
