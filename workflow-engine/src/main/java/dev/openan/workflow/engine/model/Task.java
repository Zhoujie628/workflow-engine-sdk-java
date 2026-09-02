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

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One dispatchable unit inside a {@link WorkflowStep}: the target agent and its business input.
 * Definitions are shared with the host, but each executor works on its own snapshot, so the
 * {@code status} field on the caller's instance is never mutated by a run.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Task {
  /** Target agent name, resolved against the configured agent cards. */
  private String agent;
  /** Business input forwarded to callbacks through {@link TaskRequest}. */
  private BusinessInput input;
  /** Skill identifier used for events and logs. */
  @Builder.Default private String skill = "";
  /** Human-readable description used for events and logs. */
  @Builder.Default private String description = "";
  /** Runtime status; tracked on the executor's private snapshot, not on the caller's copy. */
  @Builder.Default private TaskStatus status = TaskStatus.PENDING;
}
