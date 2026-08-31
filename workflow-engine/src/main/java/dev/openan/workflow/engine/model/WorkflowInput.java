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

package dev.openan.workflow.engine.model;

import java.util.List;

/**
 * Protocol-neutral input assembled by the engine for one workflow node.
 *
 * <p>The workflow definition decides which upstream steps participate through {@code contextFrom}.
 * Agent Task-T/A2A objects are decoded inside the engine before values enter this model, so
 * business callbacks only see ordered business outputs and their workflow provenance.
 */
public record WorkflowInput(String runtimeIntent, List<UpstreamStepResult> upstreamResults) {

  public WorkflowInput {
    runtimeIntent = runtimeIntent == null ? "" : runtimeIntent;
    upstreamResults = upstreamResults == null ? List.of() : List.copyOf(upstreamResults);
  }

  public static WorkflowInput empty() {
    return new WorkflowInput("", List.of());
  }
}
