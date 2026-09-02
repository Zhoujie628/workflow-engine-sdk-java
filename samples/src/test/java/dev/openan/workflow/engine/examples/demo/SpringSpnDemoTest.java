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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.openan.workflow.engine.model.SendMessageResult;
import org.junit.jupiter.api.Test;

class SpringSpnDemoTest {

  @Test
  void demoOnlyReportsSuccessForACompletedWorkbenchTask() {
    assertDoesNotThrow(
        () ->
            SpringSpnDemo.requireCompleted(
                SendMessageResult.builder().taskState("TASK_STATE_COMPLETED").text("ok").build()));
    assertThrows(
        IllegalStateException.class,
        () ->
            SpringSpnDemo.requireCompleted(
                SendMessageResult.builder().taskState("TASK_STATE_FAILED").text("failed").build()));
  }

  @Test
  void liveOrderCanRunWithoutBindingEmbeddedOmcServers() {
    assertFalse(
        SpringSpnDemo.resolveEmbeddedOmcEnabled(
            new String[] {"--a2a.embedded-omc-enabled=false"}, "order"));
  }

  @Test
  void simulatorDefaultsToEmbeddedOmcButAllowsAnExternalTarget() {
    assertTrue(
        SpringSpnDemo.resolveEmbeddedOmcEnabled(
            new String[] {"--a2a.order.simulator-enabled=true"}, "order"));
    assertFalse(
        SpringSpnDemo.resolveEmbeddedOmcEnabled(
            new String[] {"--a2a.order.simulator-enabled=true", "--a2a.embedded-omc-enabled=false"},
            "order"));
  }
}
