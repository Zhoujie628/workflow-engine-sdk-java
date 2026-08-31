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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.openan.workflow.engine.model.SendMessageResult;

import org.a2aproject.sdk.spec.Artifact;
import org.a2aproject.sdk.spec.DataPart;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

class ProtocolResultAdapterTest {

    @Test
    void convertsOrderedTextAndStructuredArtifactsToBusinessOutputs() {
        Task task =
                Task.builder()
                        .id("task-1")
                        .contextId("context-1")
                        .status(new TaskStatus(TaskState.TASK_STATE_COMPLETED))
                        .artifacts(
                                List.of(
                                        Artifact.builder()
                                                .artifactId("text-result")
                                                .parts(new TextPart("diagnosis"))
                                                .build(),
                                        Artifact.builder()
                                                .artifactId("data-result")
                                                .parts(new DataPart(Map.of("alarmCount", 1)))
                                                .build()))
                        .history(List.of())
                        .metadata(Map.of("protocol", "must-not-leak"))
                        .build();

        var response =
                ProtocolResultAdapter.toTaskResult(
                        SendMessageResult.builder()
                                .task(task)
                                .receivedMessages(dev.openan.workflow.engine.client.ProtocolResponses.assemble(
                                        List.of(new org.a2aproject.sdk.client.TaskEvent(task))))
                                .text("flattened-value-must-not-win")
                                .taskState("TASK_STATE_COMPLETED")
                                .metadata(Map.of("wire", "must-not-leak"))
                                .build());

        assertTrue(response.isSuccess());
        assertEquals(List.of("diagnosis", Map.of("alarmCount", 1)), response.getOutputs());
    }

    @Test
    void doesNotInventTextFallbackWhenTransportHasNoBusinessContent() {
        var response =
                ProtocolResultAdapter.toTaskResult(
                        SendMessageResult.builder()
                                .text("diagnosis")
                                .taskState("TASK_STATE_COMPLETED")
                                .build());

        assertEquals(List.of(), response.getOutputs());
    }

    @Test
    void keepsFailedProtocolStateOutOfBusinessOutputs() {
        var response =
                ProtocolResultAdapter.toTaskResult(
                        SendMessageResult.builder()
                                .text("remote failure detail")
                                .taskState("TASK_STATE_FAILED")
                                .build());

        assertFalse(response.isSuccess());
        assertEquals(List.of(), response.getOutputs());
        assertEquals("Agent returned state=TASK_STATE_FAILED", response.getError());
    }

    @Test
    void failedStatusIsRetainedAsEvidenceButOnlyArtifactsBecomePartialOutputs() {
        var status = dev.openan.workflow.engine.model.MessageContent.text("error: remote rejected");
        var evidence = new dev.openan.workflow.engine.model.ReceivedMessage(status,
                Map.of("reason", "rejected"), List.of());
        var failed = SendMessageResult.builder().taskState("TASK_STATE_FAILED")
                .receivedMessages(List.of(evidence)).build();
        var result = ProtocolResultAdapter.toTaskResult(failed);
        assertFalse(result.isSuccess());
        assertEquals(List.of(), result.getOutputs());
        assertEquals(status, result.getReceivedMessages().get(0).message());

        var partial = new dev.openan.workflow.engine.model.ReceivedMessage(status, Map.of(),
                List.of(Artifact.builder().artifactId("partial")
                        .parts(new DataPart(Map.of("ports", List.of("a", "b")))).build()));
        failed.setReceivedMessages(List.of(partial));
        assertEquals(List.of(Map.of("ports", List.of("a", "b"))),
                ProtocolResultAdapter.toTaskResult(failed).getOutputs());
    }
}
