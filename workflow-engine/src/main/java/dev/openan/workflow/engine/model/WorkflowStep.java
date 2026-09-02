/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 *    Licensed under the Apache License, Version 2.0 (the License); you may
 *    not use this file except in compliance with the License. You may obtain
 *    a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an AS IS BASIS, WITHOUT
 *    WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *    License for the specific language governing permissions and limitations
 *    under the License.
 */

package dev.openan.workflow.engine.model;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One node of a {@link Workflow}: the subtasks to dispatch, the outgoing jumps, and the upstream
 * window its callbacks receive.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkflowStep {
  /** Unique step name; the terminal routes {@code end}, {@code retry}, {@code endNode} are reserved. */
  private String name;
  /** Subtasks dispatched in parallel; completion policy is governed by {@code stepType}. */
  @Builder.Default private List<Task> subtasks = List.of();
  /** Outgoing edges, evaluated after the step completes. */
  @Builder.Default private List<JumpCondition> next = List.of();
  /** Layout hint retained for PSOP compatibility; not used by scheduling. */
  @Builder.Default private int layer = 0;
  /**
   * Selects which ancestor results the callbacks for this step receive. {@code null} selects the
   * direct predecessors, an empty list selects none, {@code "*"} selects all ancestors, and named
   * steps must be real ancestors of this step. Mixing {@code "*"} with named steps, or naming a
   * non-ancestor, fails validation before execution starts.
   */
  private List<String> contextFrom;
  /**
   * Completion policy: {@code ALL_SUCCESS} advances only after every subtask succeeds, {@code
   * ANY_SUCCESS} advances on the first success and cancels the rest, {@code SELF_LOOP} runs
   * locally through {@code onSelfTask} with no agent network calls.
   */
  @Builder.Default private StepType stepType = StepType.ALL_SUCCESS;
}
