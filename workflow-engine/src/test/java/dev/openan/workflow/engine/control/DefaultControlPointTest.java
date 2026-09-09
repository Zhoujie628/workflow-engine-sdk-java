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
package dev.openan.workflow.engine.control;

import dev.openan.workflow.engine.model.RouteRequest;
import dev.openan.workflow.engine.model.TaskRequest;
import dev.openan.workflow.engine.model.TaskResult;
import dev.openan.workflow.engine.model.WorkflowInput;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DefaultControlPointTest {

  private final TaskRequest request =
      TaskRequest.builder().agentName("agent").stepName("step").instruction("run").build();

  @Test
  void preparesTextWithoutSending() {
    assertThrows(
        java.util.concurrent.CompletionException.class,
        () -> new DefaultControlPoint().onTask(request).join());
  }

  @Test
  void localExecutionRequiresBusinessHandler() {
    assertThrows(
        java.util.concurrent.CompletionException.class,
        () -> new DefaultControlPoint().onSelfTask(request).join());
  }

  @Test
  void conditionalRoutingRequiresBusinessHandler() {
    assertThrows(
        java.util.concurrent.CompletionException.class,
        () ->
            new DefaultControlPoint()
                .onRoute(
                        new RouteRequest(
                                "run", "step", "next", "condition", WorkflowInput.empty(), List.of()))
                .join());
  }

  @Test
  void handlersCanBeRegisteredIndependently() {
    var handler =
        ControlPoint.builder()
            .onSelfTask(
                q ->
                    java.util.concurrent.CompletableFuture.completedFuture(
                        TaskResult.builder().success(true).outputs(List.of("done")).build()))
            .build();
    assertEquals(List.of("done"), handler.onSelfTask(request).join().getOutputs());
    assertThrows(
        java.util.concurrent.CompletionException.class, () -> handler.onTask(request).join());
  }
}
