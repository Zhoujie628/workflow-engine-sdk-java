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
package dev.openan.workflow.engine.examples.workbench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.openan.workflow.engine.client.A2ATExtension;
import dev.openan.workflow.engine.model.ExecutionResult;
import dev.openan.workflow.engine.model.StepType;
import dev.openan.workflow.engine.model.Workflow;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import net.openan.a2at.sdk.core.model.FilledParamData;
import net.openan.a2at.sdk.core.model.MetadataContent;
import net.openan.a2at.sdk.core.model.StandardTemplates;
import org.junit.jupiter.api.Test;

class WorkbenchTaskFlowTest {
  @Test
  void failureSummaryPreservesEachCityReasonWithoutPassingPartialOutputsAsDiagnosis() {
    var result =
        ExecutionResult.builder()
            .success(false)
            .error("Step execution failed")
            .history(
                List.of(
                    Map.of(
                        "step",
                        "diagnosis_city1",
                        "agent",
                        "city1",
                        "status",
                        "failed",
                        "errorCode",
                        "a2a.active_task_limit_exceeded",
                        "error",
                        "OMC capacity reached",
                        "errorDetails",
                        Map.of(
                            "httpStatus",
                            429,
                            "status",
                            "RESOURCE_EXHAUSTED",
                            "reason",
                            "ACTIVE_TASK_LIMIT_EXCEEDED",
                            "accessSession",
                            "private-token")),
                    Map.of(
                        "step",
                        "diagnosis_city2",
                        "agent",
                        "city2",
                        "status",
                        "success",
                        "outputs",
                        List.of("partial diagnosis must not masquerade as summary"))))
            .build();
    String summary = WorkbenchOrchestrator.buildResultText(result);
    assertTrue(summary.contains("city1"));
    assertTrue(summary.contains("a2a.active_task_limit_exceeded"));
    assertTrue(summary.contains("RESOURCE_EXHAUSTED"));
    assertTrue(summary.contains("city2"));
    org.junit.jupiter.api.Assertions.assertFalse(summary.contains("private-token"));
    org.junit.jupiter.api.Assertions.assertFalse(summary.contains("partial diagnosis"));
  }

  @Test
  void localFallbackKeepsBothCityDiagnosesParallelBeforeSelfLoopMerge() {
    Workflow workflow = WorkbenchOrchestrator.fallbackWorkflow();

    assertEquals(3, workflow.getSteps().size());
    assertEquals(0, workflow.getSteps().get(0).getLayer());
    assertEquals(0, workflow.getSteps().get(1).getLayer());
    assertEquals(
        "SPN Domain Agent City1", workflow.getSteps().get(0).getSubtasks().get(0).getAgent());
    assertEquals(
        "SPN Domain Agent City2", workflow.getSteps().get(1).getSubtasks().get(0).getAgent());
    assertEquals(StepType.SELF_LOOP, workflow.getSteps().get(2).getStepType());
    assertEquals(
        List.of("diagnosis_city1", "diagnosis_city2"), workflow.getSteps().get(2).getContextFrom());
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
                  Map.of("任务对象", "接入端口名称：P781-city1-port", "任务上下文", "OSS侧事件流水号：event-123"));
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
            () -> parser.parse("task", Map.of(A2ATExtension.TASK_T.uri(), "rendered prompt")));

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
                        "outputs", List.of("city1")),
                    Map.of(
                        "step", "diagnosis_city2",
                        "outputs", List.of("city2")),
                    Map.of(
                        "step", "merge_analysis",
                        "outputs", List.of("merged result"))))
            .stepOutputs(
                Map.of(
                    "diagnosis_city1", Map.of("diagnose", "city1"),
                    "diagnosis_city2", Map.of("diagnose", "city2"),
                    "merge_analysis", Map.of("merge", "merged result")))
            .build();

    assertEquals("merged result", WorkbenchOrchestrator.buildResultText(result));
  }

  @Test
  void arbitraryTerminalNamesAndMultipleOutputsArePreserved() {
    var workflow = Workflow.builder().name("custom").steps(List.of(
        dev.openan.workflow.engine.model.WorkflowStep.builder().name("custom-final").build())).build();
    var result = ExecutionResult.builder().success(true).history(List.of())
        .stepOutputs(Map.of("custom-final", Map.of("business", List.of("text", Map.of("count", 2), List.of("nested")))))
        .build();
    assertEquals("text\n\n{\"count\":2}\n\n[\"nested\"]", WorkbenchOrchestrator.buildResultText(result, workflow));
  }

  @Test
  void cancellationIsRequestLocalAndSurvivesLateBinding() {
    var first = new ExecutionCancellation();
    var second = new ExecutionCancellation();
    first.cancel();
    var future = new java.util.concurrent.CompletableFuture<Void>();
    first.bind(future);
    assertTrue(future.isCancelled());
    assertThrows(java.util.concurrent.CancellationException.class, first::check);
    second.check();
  }

  @Test
  void secondRuntimeCreationFailureClosesFirstRuntime() {
    var creates = new java.util.concurrent.atomic.AtomicInteger();
    var closes = new java.util.concurrent.atomic.AtomicInteger();
    var runtime = (dev.openan.workflow.engine.client.A2AJavaClientRuntime)
        java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[] {dev.openan.workflow.engine.client.A2AJavaClientRuntime.class},
            (proxy, method, args) -> {
              if (method.getName().equals("close")) closes.incrementAndGet();
              return null;
            });
    try (var lifecycle = new WorkbenchExtensionLifecycle(null, true, null, () -> {
      if (creates.incrementAndGet() == 2) throw new IllegalStateException("second initialization failed");
      return runtime;
    }, null)) {
      assertThrows(IllegalStateException.class, lifecycle::start);
      assertEquals(1, closes.get());
    }
    assertEquals(1, closes.get());
  }
}
