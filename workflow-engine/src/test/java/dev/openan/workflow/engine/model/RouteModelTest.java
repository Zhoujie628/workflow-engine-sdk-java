/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 *    Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package dev.openan.workflow.engine.model;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RouteModelTest {

  @Test
  void decisionFactoriesExposeAllowedStateAndReason() {
    assertEquals(new RouteDecision(true, ""), RouteDecision.allow());
    assertEquals(new RouteDecision(true, "matched"), RouteDecision.allow("matched"));
    assertEquals(new RouteDecision(false, ""), RouteDecision.deny());
    assertEquals(new RouteDecision(false, "blocked"), RouteDecision.deny("blocked"));
    assertEquals("", new RouteDecision(true, null).reason());
  }

  @Test
  void requestRepresentsOneConditionalEdgeAndCopiesResults() {
    List<TaskExecutionResult> results = new ArrayList<>();
    RouteRequest request =
        new RouteRequest(
            "execution", "source", "target", "alarm exists", WorkflowInput.empty(), results);
    results.add(null);

    assertEquals("target", request.nextStep());
    assertEquals("alarm exists", request.condition());
    assertEquals(List.of(), request.currentResults());
  }

  @Test
  void requestRejectsBlankConditionalEdgeFields() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RouteRequest(
                "execution", "source", "target", "  ", WorkflowInput.empty(), List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RouteRequest(
                "execution", "source", " ", "condition", WorkflowInput.empty(), List.of()));
  }
}
