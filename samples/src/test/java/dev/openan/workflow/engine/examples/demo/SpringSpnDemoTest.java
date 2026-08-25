/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.examples.demo;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.openan.workflow.engine.model.SendMessageResult;
import org.junit.jupiter.api.Test;

class SpringSpnDemoTest {

    @Test
    void demoOnlyReportsSuccessForACompletedWorkbenchTask() {
        assertDoesNotThrow(() -> SpringSpnDemo.requireCompleted(
                SendMessageResult.builder().taskState("TASK_STATE_COMPLETED").text("ok").build()));
        assertThrows(
                IllegalStateException.class,
                () -> SpringSpnDemo.requireCompleted(
                        SendMessageResult.builder().taskState("TASK_STATE_FAILED").text("failed").build()));
    }
}
