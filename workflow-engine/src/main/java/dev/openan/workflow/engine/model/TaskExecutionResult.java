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
import java.util.Objects;

/** Immutable result of one upstream workflow subtask. */
public record TaskExecutionResult(
        String agentName,
        String skill,
        String taskId,
        String taskDescription,
        TaskStatus status,
        List<Object> outputs,
        List<ReceivedMessage> receivedMessages,
        String error,
        String errorCode,
        java.util.Map<String, Object> errorDetails) {

    public TaskExecutionResult {
        if (agentName == null || agentName.isBlank()) {
            throw new IllegalArgumentException("Task result agentName must not be blank");
        }
        skill = skill == null ? "" : skill;
        Objects.requireNonNull(taskId, "Task id");
        taskDescription = taskDescription == null ? "" : taskDescription;
        status = Objects.requireNonNull(status, "Task result status is required");
        receivedMessages = receivedMessages == null ? List.of() : List.copyOf(receivedMessages);
        outputs = receivedMessages.isEmpty() ? BusinessValues.list(outputs)
                : receivedMessages.stream().flatMap(message -> message.outputs().stream()).toList();
        errorDetails = errorDetails == null ? java.util.Map.of() : BusinessValues.map(errorDetails);
    }
}
