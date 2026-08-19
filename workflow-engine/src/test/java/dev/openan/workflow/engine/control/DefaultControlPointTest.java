/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.control;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.openan.workflow.engine.StubWorkflowEngineClient;
import dev.openan.workflow.engine.model.TaskRequest;

import org.junit.jupiter.api.Test;

class DefaultControlPointTest {

    private static final TaskRequest REQUEST =
            TaskRequest.builder().agentName("agent").stepName("step").message("run").build();

    @Test
    void completedTaskWithEmptyTextIsSuccessful() {
        StubWorkflowEngineClient client =
                new StubWorkflowEngineClient("agent")
                        .withDefaultResponse("")
                        .withDefaultTaskState("TASK_STATE_COMPLETED");
        assertTrue(new DefaultControlPoint().onTask(REQUEST, client).join().isSuccess());
    }

    @Test
    void failedTaskWithErrorTextIsNotSuccessful() {
        StubWorkflowEngineClient client =
                new StubWorkflowEngineClient("agent")
                        .withDefaultResponse("remote error")
                        .withDefaultTaskState("TASK_STATE_FAILED");
        assertFalse(new DefaultControlPoint().onTask(REQUEST, client).join().isSuccess());
    }

    @Test
    void messageOnlyLegacyResponseFallsBackToText() {
        StubWorkflowEngineClient client =
                new StubWorkflowEngineClient("agent")
                        .withDefaultResponse("ok")
                        .withDefaultTaskState("");
        assertTrue(new DefaultControlPoint().onTask(REQUEST, client).join().isSuccess());
    }
}
