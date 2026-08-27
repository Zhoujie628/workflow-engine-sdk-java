/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.openan.workflow.engine.StubWorkflowEngineClient;
import dev.openan.workflow.engine.client.A2ATExtension;

import net.openan.a2at.sdk.core.model.StandardTemplates;

import org.junit.jupiter.api.Test;

import java.util.Map;

class TaskSubmissionTest {

    @Test
    void naturalLanguageSubmissionUsesPlainSendPath() {
        StubWorkflowEngineClient client = new StubWorkflowEngineClient("agent");

        client.dispatch(TaskSubmission.fromText("agent", "diagnose link")).join();

        var sent = client.getSentMessages().get(0);
        assertEquals("diagnose link", sent.message);
        assertEquals(Map.of(), sent.metadata);
    }

    @Test
    void structuredSubmissionAddsOnlyEngineOwnedTaskInputMetadata() {
        StubWorkflowEngineClient client = new StubWorkflowEngineClient("agent");
        TaskSubmission submission =
                TaskSubmission.fromData(
                        "agent",
                        "create diagnosis",
                        Map.of("port", "P1"),
                        Map.of("type", "object"),
                        StandardTemplates.PRIVATE_LINE_COMPLAINT);

        client.dispatch(submission).join();

        var metadata = client.getSentMessages().get(0).metadata;
        assertEquals(Map.of("port", "P1"), metadata.get(A2ATExtension.TASK_DATA_META_KEY));
        assertEquals(
                StandardTemplates.PRIVATE_LINE_COMPLAINT.uri(),
                metadata.get(A2ATExtension.TASK_TEMPLATE_META_KEY));
        assertInstanceOf(TaskSubmission.StructuredData.class, submission.input());
    }

    @Test
    void invalidSubmissionFailsAtBusinessBoundary() {
        assertThrows(IllegalArgumentException.class, () -> TaskSubmission.fromText("", "task"));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        TaskSubmission.fromData(
                                "agent",
                                "task",
                                Map.of(),
                                Map.of(),
                                StandardTemplates.PRIVATE_LINE_COMPLAINT));
    }
}
