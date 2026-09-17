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
 *    distributed under the License is distributed on an AS IS BASIS, WITHOUT
 *    WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *    License for the specific language governing permissions and limitations
 *    under the License.
 */

package dev.openan.workflow.engine.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Outcome of one workflow run: overall success, per-step execution history, aggregated step
 * outputs, and a terminal error description when the run failed.
 *
 * <p>Collection fields are copied on write and exposed unmodifiable, so callers cannot mutate the
 * result after the engine has produced it.
 */
@Data
@NoArgsConstructor
@Builder
public class ExecutionResult {
  private boolean success;

  /** Per-step records in execution order. Stored defensively; exposed unmodifiable. */
  private List<Map<String, Object>> history;

  /** Step name to business outputs; nested structure preserved. */
  private Map<String, Map<String, Object>> stepOutputs;

  /** Terminal failure description; raw error text, not redacted. Null on success. */
  private String error;

  /** All-args constructor with defensive copies; Lombok's builder routes through it. */
  public ExecutionResult(
      boolean success,
      List<Map<String, Object>> history,
      Map<String, Map<String, Object>> stepOutputs,
      String error) {
    this.success = success;
    this.history = history == null ? null : new ArrayList<>(history);
    this.stepOutputs = stepOutputs == null ? null : new HashMap<>(stepOutputs);
    this.error = error;
  }

  public List<Map<String, Object>> getHistory() {
    return history == null ? null : Collections.unmodifiableList(history);
  }

  public void setHistory(List<Map<String, Object>> history) {
    this.history = history == null ? null : new ArrayList<>(history);
  }

  public Map<String, Map<String, Object>> getStepOutputs() {
    return stepOutputs == null ? null : Collections.unmodifiableMap(stepOutputs);
  }

  public void setStepOutputs(Map<String, Map<String, Object>> stepOutputs) {
    this.stepOutputs = stepOutputs == null ? null : new HashMap<>(stepOutputs);
  }
}
