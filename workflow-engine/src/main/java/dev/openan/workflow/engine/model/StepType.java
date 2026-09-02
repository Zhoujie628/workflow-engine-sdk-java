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

/** Completion policy for a step's subtasks. */
public enum StepType {
  /** Advance only after every subtask succeeds. */
  ALL_SUCCESS("AllSuccess"),
  /** Advance on the first successful subtask; the remaining subtasks are cancelled. */
  ANY_SUCCESS("AnySuccess"),
  /** Run locally through {@code onSelfTask} with no agent network calls. */
  SELF_LOOP("SelfLoop");

  private final String value;

  StepType(String value) {
    this.value = value;
  }

  public static StepType fromValue(String v) {
    if (v == null) {
      return ALL_SUCCESS;
    }
    for (StepType t : values()) {
      if (t.value.equalsIgnoreCase(v) || t.name().equalsIgnoreCase(v)) {
        return t;
      }
    }
    throw new IllegalArgumentException("Unknown workflow step type: " + v);
  }

  public String getValue() {
    return value;
  }
}
