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

package dev.openan.workflow.engine.core;

import static org.junit.jupiter.api.Assertions.*;

import dev.openan.workflow.engine.StubWorkflowEngineClient;
import dev.openan.workflow.engine.control.*;
import dev.openan.workflow.engine.model.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class BusinessBoundaryTest {
  private WorkflowStep step(String name, String next, List<String> context) {
    return WorkflowStep.builder()
        .name(name)
        .stepType(StepType.SELF_LOOP)
        .contextFrom(context)
        .subtasks(List.of(Task.builder().agent("host").description(name).build()))
        .next(next == null ? List.of() : List.of(new JumpCondition(next, "")))
        .build();
  }

  @Test
  void identifiersAndWorkflowStateAreIsolatedAcrossExecutions() {
    var workflow =
        Workflow.builder().name("shared").steps(List.of(step("one", null, List.of()))).build();
    List<TaskRequest> seen = new CopyOnWriteArrayList<>();
    var callbacks =
        ControlPoint.builder()
            .onSelfTask(
                q -> {
                  seen.add(q);
                  return CompletableFuture.completedFuture(
                      TaskResult.builder().success(true).outputs(List.of("ok")).build());
                })
            .build();
    var client = new StubWorkflowEngineClient();
    var one = new WorkflowExecutor(workflow, callbacks, client, null, "first", "zh");
    var two = new WorkflowExecutor(workflow, callbacks, client, null, "second", "zh");
    CompletableFuture.allOf(one.run(), two.run()).join();
    assertEquals(2, seen.size());
    assertNotEquals(seen.get(0).getTaskId(), seen.get(1).getTaskId());
    assertNotEquals(seen.get(0).getExecutionId(), seen.get(1).getExecutionId());
    assertEquals(TaskStatus.PENDING, workflow.getSteps().get(0).getSubtasks().get(0).getStatus());
    assertThrows(CompletionException.class, () -> one.run().join());
  }

  @Test
  void routingUsesSelectedAncestorsAndCurrentTypedOutputsSeparately() {
    for (List<String> selection :
        Arrays.asList(null, List.<String>of(), List.of("*"), List.of("first"))) {
      var first = step("first", "second", List.of());
      var second = step("second", "route", null);
      var route = step("route", null, selection);
      route.setNext(List.of(new JumpCondition("endNode", "all done")));
      var workflow =
          Workflow.builder().name("route-context").steps(List.of(first, second, route)).build();
      List<RouteRequest> seen = new ArrayList<>();
      var callbacks =
          ControlPoint.builder()
              .onSelfTask(
                  q ->
                      CompletableFuture.completedFuture(
                          TaskResult.builder()
                              .success(true)
                              .outputs(List.of(q.getStepName()))
                              .build()))
              .onRoute(
                  q -> {
                    seen.add(q);
                    return CompletableFuture.completedFuture(new RouteDecision("endNode", "done"));
                  })
              .build();
      assertTrue(
          new WorkflowExecutor(
                  workflow, callbacks, new StubWorkflowEngineClient(), null, "intent", "zh")
              .run()
              .join()
              .isSuccess());
      var q = seen.get(0);
      assertEquals("intent", q.workflowInput().runtimeIntent());
      assertEquals(List.of("route"), q.currentResults().get(0).outputs());
      int expected =
          selection == null ? 1 : selection.isEmpty() ? 0 : selection.contains("*") ? 2 : 1;
      assertEquals(expected, q.workflowInput().upstreamResults().size());
    }
  }
}
