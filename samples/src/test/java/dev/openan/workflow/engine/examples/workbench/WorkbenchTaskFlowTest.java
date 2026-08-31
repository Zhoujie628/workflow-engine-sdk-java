/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.examples.workbench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.openan.workflow.engine.client.A2ATExtension;
import dev.openan.workflow.engine.model.ExecutionResult;
import dev.openan.workflow.engine.model.StepType;
import dev.openan.workflow.engine.model.Workflow;

import net.openan.a2at.sdk.core.model.FilledParamData;
import net.openan.a2at.sdk.core.model.MetadataContent;
import net.openan.a2at.sdk.core.model.StandardTemplates;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

class WorkbenchTaskFlowTest {

    @Test
    void localFallbackKeepsBothCityDiagnosesParallelBeforeSelfLoopMerge() {
        Workflow workflow = WorkbenchOrchestrator.fallbackWorkflow();

        assertEquals(3, workflow.getSteps().size());
        assertEquals(0, workflow.getSteps().get(0).getLayer());
        assertEquals(0, workflow.getSteps().get(1).getLayer());
        assertEquals("SPN Domain Agent City1", workflow.getSteps().get(0).getSubtasks().get(0).getAgent());
        assertEquals("SPN Domain Agent City2", workflow.getSteps().get(1).getSubtasks().get(0).getAgent());
        assertEquals(StepType.SELF_LOOP, workflow.getSteps().get(2).getStepType());
        assertEquals(
                List.of("diagnosis_city1", "diagnosis_city2"),
                workflow.getSteps().get(2).getContextFrom());
    }

    @Test
    void validatedTaskTParametersDriveRuntimeIntent() {
        AtomicReference<String> validatedPrompt = new AtomicReference<>();
        AtomicReference<String> validatedTemplate = new AtomicReference<>();
        WorkbenchTaskInputParser parser =
                new WorkbenchTaskInputParser(
                        (prompt, schema, templateUri) -> {
                            validatedPrompt.set(prompt);
                            validatedTemplate.set(templateUri.uri());
                            return new FilledParamData(
                                    Map.of(
                                            "任务对象",
                                            "接入端口名称：P781-city1-port",
                                            "任务上下文",
                                            "OSS侧事件流水号：event-123"));
                        });

        WorkbenchTaskInputParser.ParsedTask parsed =
                parser.parse(
                        "创建专线业务投诉诊断任务",
                        Map.of(
                                A2ATExtension.TASK_T.uri(),
                                "rendered Task-T prompt",
                                MetadataContent.TEMPLATE_URI_METADATA_KEY,
                                StandardTemplates.PRIVATE_LINE_COMPLAINT.uri()));

        assertEquals("rendered Task-T prompt", validatedPrompt.get());
        assertEquals(StandardTemplates.PRIVATE_LINE_COMPLAINT.uri(), validatedTemplate.get());
        assertTrue(parsed.runtimeIntent().contains("P781-city1-port"));
        assertTrue(parsed.runtimeIntent().contains("event-123"));
    }

    @Test
    void missingOrBlankTaskTBusinessFieldsAreRejected() {
        WorkbenchTaskInputParser parser =
                new WorkbenchTaskInputParser(
                        (prompt, schema, templateUri) ->
                                new FilledParamData(Map.of("任务对象", "", "任务上下文", "context")));

        IllegalArgumentException error =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                parser.parse(
                                        "task",
                                        Map.of(
                                                A2ATExtension.TASK_T.uri(),
                                                "rendered prompt",
                                                MetadataContent.TEMPLATE_URI_METADATA_KEY,
                                                StandardTemplates.PRIVATE_LINE_COMPLAINT.uri())));

        assertTrue(error.getMessage().contains("任务对象"));
    }

    @Test
    void missingTaskTMetadataIsRejectedBeforeWorkflowSelection() {
        WorkbenchTaskInputParser parser =
                new WorkbenchTaskInputParser(
                        (prompt, schema, templateUri) -> new FilledParamData(Map.of()));

        assertThrows(IllegalArgumentException.class, () -> parser.parse("plain text", Map.of()));
    }

    @Test
    void missingTemplateUriIsRejectedInsteadOfDefaultingTheScenario() {
        WorkbenchTaskInputParser parser =
                new WorkbenchTaskInputParser(
                        (prompt, schema, templateUri) -> new FilledParamData(Map.of()));

        IllegalArgumentException error =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                parser.parse(
                                        "task",
                                        Map.of(
                                                A2ATExtension.TASK_T.uri(),
                                                "rendered prompt")));

        assertTrue(error.getMessage().contains("templateUri"));
    }

    @Test
    void successfulWorkflowReturnsMergedBusinessOutput() {
        ExecutionResult result =
                ExecutionResult.builder()
                        .success(true)
                        .history(
                                List.of(
                                        Map.of(
                                                "step", "diagnosis_city1",
                                                "output", "city1"),
                                        Map.of(
                                                "step", "diagnosis_city2",
                                                "output", "city2"),
                                        Map.of(
                                                "step", "merge_analysis",
                                                "output", "merged result")))
                        .stepOutputs(
                                Map.of(
                                        "diagnosis_city1", Map.of("diagnose", "city1"),
                                        "diagnosis_city2", Map.of("diagnose", "city2"),
                                        "merge_analysis", Map.of("merge", "merged result")))
                        .build();

        assertEquals("merged result", WorkbenchOrchestrator.buildResultText(result));
    }
}
