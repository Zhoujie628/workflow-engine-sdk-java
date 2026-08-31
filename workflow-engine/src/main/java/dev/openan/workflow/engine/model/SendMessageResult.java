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
import lombok.Data;
import lombok.NoArgsConstructor;

import org.a2aproject.sdk.spec.Task;

import java.util.Map;
import java.util.List;

@Data
@NoArgsConstructor
public class SendMessageResult {
    private String text = "";
    private Task task;
    private Map<String, Object> metadata;
    private String taskState = "";
    /** Local interaction outcome, separate from the actual remote task state. */
    private String failureCode;
    private String failureMessage;

    /** Lossless source of all convenience outputs. */
    private List<ReceivedMessage> receivedMessages = List.of();

    @Builder
    public SendMessageResult(String text, Task task, Map<String, Object> metadata, String taskState,
            String failureCode, String failureMessage, List<ReceivedMessage> receivedMessages) {
        this.text = text == null ? "" : text;
        this.task = task;
        this.metadata = metadata == null ? Map.of() : BusinessValues.map(metadata);
        this.taskState = taskState == null ? "" : taskState;
        this.failureCode = failureCode;
        this.failureMessage = failureMessage;
        this.receivedMessages = receivedMessages == null ? List.of() : List.copyOf(receivedMessages);
    }

    /** Projects text/data values from the retained response, without fallback or semantic parsing. */
    public List<Object> getOutputs() {
        boolean includeMessage = failureCode == null
                && (task == null || "TASK_STATE_COMPLETED".equals(taskState));
        return receivedMessages.stream().flatMap(message -> message.outputs(includeMessage).stream()).toList();
    }

    /** Snapshots the list supplied by transport adapters. */
    public void setReceivedMessages(List<ReceivedMessage> messages) {
        this.receivedMessages = List.copyOf(messages);
    }
}
