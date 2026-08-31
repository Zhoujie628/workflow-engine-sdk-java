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

import lombok.Builder;
import lombok.Value;

/** Immutable business input for one logical workflow task; protocol identifiers stay internal. */
@Value
@Builder
public class TaskRequest {
    /** Unique execution identity, separate from remote A2A context IDs. */
    private String executionId;

    /** Logical task identity, stable across self-loop re-entry within this execution. */
    private String taskId;

    /** Business text or structured data, independent of upstream evidence. */
    private BusinessInput input;

    private String agentName;
    private String skill;

    /** Current task instruction only; never contains rendered upstream results. */
    private String instruction;
    @Builder.Default private String language = "zh";
    private String stepName;

    /** Engine-assembled, protocol-neutral input selected by the workflow definition. */
    @Builder.Default private WorkflowInput workflowInput = WorkflowInput.empty();
}
