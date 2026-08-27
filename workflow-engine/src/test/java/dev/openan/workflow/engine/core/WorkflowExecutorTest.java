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

package dev.openan.workflow.engine.core;

import static org.junit.jupiter.api.Assertions.*;

import dev.openan.workflow.engine.StubWorkflowEngineClient;
import dev.openan.workflow.engine.control.ControlPoint;
import dev.openan.workflow.engine.control.TaskDispatcher;
import dev.openan.workflow.engine.control.EventCallback;
import dev.openan.workflow.engine.control.EventType;

import dev.openan.workflow.engine.model.*;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tests for WorkflowExecutor: DAG traversal, parallel subtasks, ANY_SUCCESS, conditional routing,
 * failure propagation, events.
 */
class WorkflowExecutorTest {

    private final List<String> events = Collections.synchronizedList(new ArrayList<>());

    private EventCallback recordingCallback() {
        events.clear();
        return new EventCallback() {
            @Override
            public void onEvent(String type, Map<String, Object> data) {
                events.add(type);
            }
        };
    }

    private Task task(String agent, String desc) {
        return Task.builder().agent(agent).description(desc).build();
    }

    private JumpCondition jump(String step, String cond) {
        return JumpCondition.builder().step(step).condition(cond).build();
    }

    /** ControlPoint that auto-sends to the stub client and picks the first branch. */
    private ControlPoint autoCp() {
        return new ControlPoint() {
            @Override
            public CompletableFuture<TaskResponse> onTask(
                    TaskRequest request, TaskDispatcher taskDispatcher) {
                return taskDispatcher
                        .dispatch(dev.openan.workflow.engine.model.TaskSubmission.fromText(
                                request.getAgentName(), request.getMessage()))
                        .thenApply(
                                r ->
                                        TaskResponse.builder()
                                                .success(true)
                                                .output(r.getText())
                                                .build());
            }

            @Override
            public CompletableFuture<RouteDecision> onRoute(
                    String stepName, Map<String, Object> results, List<JumpCondition> conditions) {
                return CompletableFuture.completedFuture(
                        RouteDecision.builder()
                                .nextStep(conditions.get(0).getStep())
                                .reason("first")
                                .build());
            }
        };
    }

    @Test
    void linearWorkflowTwoSteps() {
        WorkflowStep s1 =
                WorkflowStep.builder()
                        .name("s1")
                        .layer(0)
                        .subtasks(List.of(task("A", "do A")))
                        .next(List.of(jump("s2", "")))
                        .build();
        WorkflowStep s2 =
                WorkflowStep.builder()
                        .name("s2")
                        .layer(1)
                        .subtasks(List.of(task("B", "do B")))
                        .build();
        Workflow wf = Workflow.builder().name("linear").steps(List.of(s1, s2)).build();
        StubWorkflowEngineClient stub = new StubWorkflowEngineClient("A", "B");
        WorkflowExecutor exec =
                new WorkflowExecutor(wf, autoCp(), stub, recordingCallback(), "intent", "zh");
        ExecutionResult result = exec.run().join();
        assertTrue(result.isSuccess());
        assertEquals(2, exec.getHistory().size());
        assertEquals(2, stub.getSentCount());
        assertTrue(events.contains(EventType.STEP_START));
        assertTrue(events.contains(EventType.STEP_COMPLETE));
        assertTrue(events.contains(EventType.WORKFLOW_COMPLETE));
        assertFalse(events.contains(EventType.START));
        assertFalse(events.contains(EventType.COMPLETE));
    }

    @Test
    void parallelFanOutAllUnconditionalNext() {
        // s1 -> s2 (unconditional) and s3 (unconditional)
        WorkflowStep s1 =
                WorkflowStep.builder()
                        .name("s1")
                        .layer(0)
                        .subtasks(List.of(task("A", "do A")))
                        .next(List.of(jump("s2", ""), jump("s3", "")))
                        .build();
        WorkflowStep s2 =
                WorkflowStep.builder()
                        .name("s2")
                        .layer(1)
                        .subtasks(List.of(task("B", "do B")))
                        .build();
        WorkflowStep s3 =
                WorkflowStep.builder()
                        .name("s3")
                        .layer(1)
                        .subtasks(List.of(task("C", "do C")))
                        .build();
        Workflow wf = Workflow.builder().name("fanout").steps(List.of(s1, s2, s3)).build();
        StubWorkflowEngineClient stub = new StubWorkflowEngineClient("A", "B", "C");
        WorkflowExecutor exec =
                new WorkflowExecutor(wf, autoCp(), stub, recordingCallback(), "", "zh");
        ExecutionResult result = exec.run().join();
        assertTrue(result.isSuccess());
        assertEquals(3, exec.getHistory().size());
        assertEquals(3, stub.getSentCount());
        Set<String> agents = new HashSet<>();
        for (var sm : stub.getSentMessages()) {
            agents.add(sm.agentName);
        }
        assertEquals(Set.of("A", "B", "C"), agents);
    }

    @Test
    void parallelBranchesScheduleSharedMergeExactlyOnce() {
        WorkflowStep city1 =
                WorkflowStep.builder()
                        .name("diagnosis_city1")
                        .layer(0)
                        .subtasks(List.of(task("OMC-1", "diagnose city 1")))
                        .next(List.of(jump("merge", "")))
                        .build();
        WorkflowStep city2 =
                WorkflowStep.builder()
                        .name("diagnosis_city2")
                        .layer(0)
                        .subtasks(List.of(task("OMC-2", "diagnose city 2")))
                        .next(List.of(jump("merge", "")))
                        .build();
        WorkflowStep merge =
                WorkflowStep.builder()
                        .name("merge")
                        .layer(1)
                        .stepType(StepType.SELF_LOOP)
                        .contextFrom(List.of("diagnosis_city1", "diagnosis_city2"))
                        .subtasks(List.of(task("Workbench", "merge diagnoses")))
                        .build();
        Workflow workflow =
                Workflow.builder()
                        .name("parallel-join")
                        .steps(List.of(city1, city2, merge))
                        .build();

        ExecutorService taskExecutor = Executors.newFixedThreadPool(2);
        CyclicBarrier diagnosisBarrier = new CyclicBarrier(2);
        CyclicBarrier schedulingBarrier = new CyclicBarrier(2);
        AtomicInteger mergeCalls = new AtomicInteger();
        ControlPoint controlPoint =
                new ControlPoint() {
                    @Override
                    public CompletableFuture<TaskResponse> onTask(
                            TaskRequest request, TaskDispatcher taskDispatcher) {
                        return CompletableFuture.supplyAsync(
                                () -> {
                                    awaitBarrier(diagnosisBarrier);
                                    return TaskResponse.builder()
                                            .success(true)
                                            .output(request.getAgentName() + " diagnosis")
                                            .build();
                                },
                                taskExecutor);
                    }

                    @Override
                    public CompletableFuture<TaskResponse> onSelfTask(TaskRequest request) {
                        mergeCalls.incrementAndGet();
                        return CompletableFuture.completedFuture(
                                TaskResponse.builder().success(true).output("merged").build());
                    }

                    @Override
                    public CompletableFuture<RouteDecision> onRoute(
                            String stepName,
                            Map<String, Object> results,
                            List<JumpCondition> conditions) {
                        return CompletableFuture.completedFuture(
                                RouteDecision.builder()
                                        .nextStep(conditions.get(0).getStep())
                                        .reason("first")
                                        .build());
                    }

                };
        EventCallback callback =
                new EventCallback() {
                    @Override
                    public void onEvent(String type, Map<String, Object> data) {
                        if (EventType.STEP_COMPLETE.equals(type)
                                && String.valueOf(data.get("step")).startsWith("diagnosis_")) {
                            awaitBarrier(schedulingBarrier);
                        }
                    }
                };

        try {
            ExecutionResult result =
                    new WorkflowExecutor(
                                    workflow,
                                    controlPoint,
                                    new StubWorkflowEngineClient("OMC-1", "OMC-2"),
                                    callback,
                                    "cross-city complaint",
                                    "zh")
                            .run()
                            .join();

            assertTrue(result.isSuccess());
            assertEquals(1, mergeCalls.get(), "a converging step must execute exactly once");
            assertEquals(3, result.getHistory().size());
            assertEquals(
                    Set.of("diagnosis_city1", "diagnosis_city2", "merge"),
                    result.getStepOutputs().keySet());
        } finally {
            taskExecutor.shutdownNow();
        }
    }

    private static void awaitBarrier(CyclicBarrier barrier) {
        try {
            barrier.await(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("Timed out synchronizing parallel workflow test", e);
        }
    }

    @Test
    void conditionalRouteOnRouteCalled() {
        WorkflowStep s1 =
                WorkflowStep.builder()
                        .name("s1")
                        .layer(0)
                        .subtasks(List.of(task("A", "do A")))
                        .next(List.of(jump("s2", "A ok"), jump("s3", "A fail")))
                        .build();
        WorkflowStep s2 =
                WorkflowStep.builder()
                        .name("s2")
                        .layer(1)
                        .subtasks(List.of(task("B", "do B")))
                        .build();
        WorkflowStep s3 =
                WorkflowStep.builder()
                        .name("s3")
                        .layer(1)
                        .subtasks(List.of(task("C", "do C")))
                        .build();
        Workflow wf = Workflow.builder().name("cond").steps(List.of(s1, s2, s3)).build();
        StubWorkflowEngineClient stub = new StubWorkflowEngineClient("A", "B", "C");
        // Route to s3 (the second branch)
        ControlPoint cp =
                new ControlPoint() {
                    @Override
                    public CompletableFuture<TaskResponse> onTask(
                            TaskRequest req, TaskDispatcher dispatcher) {
                        return dispatcher.dispatch(
                                        dev.openan.workflow.engine.model.TaskSubmission.fromText(
                                                req.getAgentName(), req.getMessage()))
                                .thenApply(
                                        r ->
                                                TaskResponse.builder()
                                                        .success(true)
                                                        .output(r.getText())
                                                        .build());
                    }

                    @Override
                    public CompletableFuture<RouteDecision> onRoute(
                            String stepName,
                            Map<String, Object> results,
                            List<JumpCondition> conditions) {
                        return CompletableFuture.completedFuture(
                                RouteDecision.builder().nextStep("s3").reason("chose s3").build());
                    }
                };
        WorkflowExecutor exec = new WorkflowExecutor(wf, cp, stub, recordingCallback(), "", "zh");
        ExecutionResult result = exec.run().join();
        assertTrue(result.isSuccess());
        // s1 -> s3 only (s2 skipped)
        assertEquals(2, stub.getSentCount());
        assertTrue(events.contains(EventType.ROUTE_DECISION));
        assertEquals("C", stub.getSentMessages().get(1).agentName);
    }

    @Test
    void onRouteInvalidStepFailsWorkflow() {
        WorkflowStep s1 =
                WorkflowStep.builder()
                        .name("s1")
                        .layer(0)
                        .subtasks(List.of(task("A", "do A")))
                        .next(List.of(jump("s2", "cond")))
                        .build();
        WorkflowStep s2 =
                WorkflowStep.builder()
                        .name("s2")
                        .layer(1)
                        .subtasks(List.of(task("B", "do B")))
                        .build();
        Workflow wf = Workflow.builder().name("invalid").steps(List.of(s1, s2)).build();
        StubWorkflowEngineClient stub = new StubWorkflowEngineClient("A", "B");
        ControlPoint cp =
                new ControlPoint() {
                    @Override
                    public CompletableFuture<TaskResponse> onTask(
                            TaskRequest req, TaskDispatcher dispatcher) {
                        return dispatcher.dispatch(
                                        dev.openan.workflow.engine.model.TaskSubmission.fromText(
                                                req.getAgentName(), req.getMessage()))
                                .thenApply(
                                        r ->
                                                TaskResponse.builder()
                                                        .success(true)
                                                        .output(r.getText())
                                                        .build());
                    }

                    @Override
                    public CompletableFuture<RouteDecision> onRoute(
                            String stepName,
                            Map<String, Object> results,
                            List<JumpCondition> conditions) {
                        return CompletableFuture.completedFuture(
                                RouteDecision.builder()
                                        .nextStep("nonexistent")
                                        .reason("bad")
                                        .build());
                    }
                };
        WorkflowExecutor exec = new WorkflowExecutor(wf, cp, stub, recordingCallback(), "", "zh");
        ExecutionResult result = exec.run().join();

        // s1 executes, then an invalid route is reported as a workflow error (s2 never runs).
        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("allowed next steps"));
        assertEquals(1, stub.getSentCount());
    }

    @Test
    void taskFailurePropagatesAndStopsWorkflow() {
        WorkflowStep s1 =
                WorkflowStep.builder()
                        .name("s1")
                        .layer(0)
                        .subtasks(List.of(task("A", "do A")))
                        .next(List.of(jump("s2", "")))
                        .build();
        WorkflowStep s2 =
                WorkflowStep.builder()
                        .name("s2")
                        .layer(1)
                        .subtasks(List.of(task("B", "do B")))
                        .build();
        Workflow wf = Workflow.builder().name("fail").steps(List.of(s1, s2)).build();
        StubWorkflowEngineClient stub = new StubWorkflowEngineClient("A", "B");
        ControlPoint cp =
                new ControlPoint() {
                    @Override
                    public CompletableFuture<TaskResponse> onTask(
                            TaskRequest req, TaskDispatcher dispatcher) {
                        if (req.getAgentName().equals("A")) {
                            return CompletableFuture.completedFuture(
                                    TaskResponse.builder()
                                            .success(false)
                                            .error("A failed")
                                            .build());
                        }
                        return dispatcher.dispatch(
                                        dev.openan.workflow.engine.model.TaskSubmission.fromText(
                                                req.getAgentName(), req.getMessage()))
                                .thenApply(
                                        r ->
                                                TaskResponse.builder()
                                                        .success(true)
                                                        .output(r.getText())
                                                        .build());
                    }

                    @Override
                    public CompletableFuture<RouteDecision> onRoute(
                            String stepName,
                            Map<String, Object> results,
                            List<JumpCondition> conditions) {
                        return CompletableFuture.completedFuture(
                                RouteDecision.builder()
                                        .nextStep(conditions.get(0).getStep())
                                        .build());
                    }
                };
        WorkflowExecutor exec = new WorkflowExecutor(wf, cp, stub, recordingCallback(), "", "zh");
        ExecutionResult result = exec.run().join();
        assertFalse(result.isSuccess());
        assertNotNull(result.getError());
        // Agent A fails directly (no send), agent B must never be reached.
        boolean bSent = stub.getSentMessages().stream().anyMatch(m -> m.agentName.equals("B"));
        assertFalse(bSent, "Agent B must not be reached after A fails");
        assertTrue(events.contains(EventType.ERROR));
    }

    @Test
    void anySuccessReturnsOnFirstSuccess() {
        // s1 has 3 subtasks, ANY_SUCCESS: first success cancels the rest.
        Task t1 = task("A", "do A");
        Task t2 = Task.builder().agent("B").description("do B").build();
        Task t3 = Task.builder().agent("C").description("do C").build();
        WorkflowStep s1 =
                WorkflowStep.builder()
                        .name("s1")
                        .layer(0)
                        .stepType(StepType.ANY_SUCCESS)
                        .subtasks(List.of(t1, t2, t3))
                        .next(List.of())
                        .build();
        Workflow wf = Workflow.builder().name("any").steps(List.of(s1)).build();
        StubWorkflowEngineClient stub = new StubWorkflowEngineClient("A", "B", "C");
        // All succeed; the step should complete as soon as the first returns.
        WorkflowExecutor exec =
                new WorkflowExecutor(wf, autoCp(), stub, recordingCallback(), "", "zh");
        ExecutionResult result = exec.run().join();
        assertTrue(result.isSuccess());
        // At least one task was sent (could be all 3 racing, but >= 1)
        assertTrue(stub.getSentCount() >= 1);
    }

    @Test
    void anySuccessAllFailReturnsFailure() {
        Task t1 = task("A", "do A");
        Task t2 = task("B", "do B");
        WorkflowStep s1 =
                WorkflowStep.builder()
                        .name("s1")
                        .layer(0)
                        .stepType(StepType.ANY_SUCCESS)
                        .subtasks(List.of(t1, t2))
                        .next(List.of())
                        .build();
        Workflow wf = Workflow.builder().name("any-fail").steps(List.of(s1)).build();
        StubWorkflowEngineClient stub = new StubWorkflowEngineClient("A", "B");
        ControlPoint cp =
                new ControlPoint() {
                    @Override
                    public CompletableFuture<TaskResponse> onTask(
                            TaskRequest req, TaskDispatcher dispatcher) {
                        return CompletableFuture.completedFuture(
                                TaskResponse.builder().success(false).error("failed").build());
                    }

                    @Override
                    public CompletableFuture<RouteDecision> onRoute(
                            String stepName,
                            Map<String, Object> results,
                            List<JumpCondition> conditions) {
                        return CompletableFuture.completedFuture(
                                RouteDecision.builder()
                                        .nextStep(conditions.get(0).getStep())
                                        .build());
                    }
                };
        WorkflowExecutor exec = new WorkflowExecutor(wf, cp, stub, recordingCallback(), "", "zh");
        ExecutionResult result = exec.run().join();
        assertFalse(result.isSuccess());
    }

    @Test
    void eventSequenceForLinearWorkflow() {
        WorkflowStep s1 =
                WorkflowStep.builder()
                        .name("s1")
                        .layer(0)
                        .subtasks(List.of(task("A", "do A")))
                        .next(List.of())
                        .build();
        Workflow wf = Workflow.builder().name("seq").steps(List.of(s1)).build();
        StubWorkflowEngineClient stub = new StubWorkflowEngineClient("A");
        WorkflowExecutor exec =
                new WorkflowExecutor(wf, autoCp(), stub, recordingCallback(), "", "zh");
        exec.run().join();

        // Executor emits: step_start, task_request, task_status_changed,
        // task_response, step_complete, workflow_complete (NO start/complete/close)
        int startIdx = events.indexOf(EventType.STEP_START);
        int taskReqIdx = events.indexOf(EventType.TASK_REQUEST);
        int taskStatusIdx = events.indexOf(EventType.TASK_STATUS_CHANGED);
        int taskRespIdx = events.indexOf(EventType.TASK_RESPONSE);
        int stepCompleteIdx = events.indexOf(EventType.STEP_COMPLETE);
        int wfCompleteIdx = events.indexOf(EventType.WORKFLOW_COMPLETE);
        assertNotEquals(-1, startIdx);
        assertTrue(startIdx < taskReqIdx);
        assertTrue(taskReqIdx < taskStatusIdx);
        assertTrue(taskStatusIdx < taskRespIdx);
        assertTrue(taskRespIdx < stepCompleteIdx);
        assertTrue(stepCompleteIdx < wfCompleteIdx);
        assertFalse(
                events.contains(EventType.START), "Executor must not emit START (runner's job)");
    }

    @Test
    void runtimeIntentPassedToContext() {
        WorkflowStep s1 =
                WorkflowStep.builder()
                        .name("s1")
                        .layer(0)
                        .subtasks(List.of(task("A", "do A")))
                        .next(List.of())
                        .build();
        Workflow wf = Workflow.builder().name("intent").steps(List.of(s1)).build();
        StubWorkflowEngineClient stub = new StubWorkflowEngineClient("A");
        List<String> messages = Collections.synchronizedList(new ArrayList<>());
        ControlPoint cp =
                new ControlPoint() {
                    @Override
                    public CompletableFuture<TaskResponse> onTask(
                            TaskRequest req, TaskDispatcher dispatcher) {
                        messages.add(req.getMessage());
                        return CompletableFuture.completedFuture(
                                TaskResponse.builder().success(true).output("ok").build());
                    }

                    @Override
                    public CompletableFuture<RouteDecision> onRoute(
                            String stepName,
                            Map<String, Object> results,
                            List<JumpCondition> conditions) {
                        return CompletableFuture.completedFuture(
                                RouteDecision.builder()
                                        .nextStep(conditions.get(0).getStep())
                                        .build());
                    }
                };
        WorkflowExecutor exec =
                new WorkflowExecutor(wf, cp, stub, new EventCallback(), "my intent", "zh");
        exec.run().join();
        assertFalse(messages.isEmpty());
        assertTrue(messages.get(0).contains("my intent"));
    }

    @Test
    void noSubtasksStepSucceeds() {
        WorkflowStep s1 =
                WorkflowStep.builder()
                        .name("s1")
                        .layer(0)
                        .subtasks(List.of())
                        .next(List.of())
                        .build();
        Workflow wf = Workflow.builder().name("empty-step").steps(List.of(s1)).build();
        StubWorkflowEngineClient stub = new StubWorkflowEngineClient();
        WorkflowExecutor exec =
                new WorkflowExecutor(wf, autoCp(), stub, recordingCallback(), "", "zh");
        ExecutionResult result = exec.run().join();
        assertTrue(result.isSuccess());
        assertEquals(0, stub.getSentCount());
    }

    @Test
    void missingStaticRouteTargetFailsBeforeExecution() {
        WorkflowStep step =
                WorkflowStep.builder()
                        .name("start")
                        .layer(0)
                        .subtasks(List.of(task("A", "run")))
                        .next(List.of(jump("missing", "")))
                        .build();
        StubWorkflowEngineClient stub = new StubWorkflowEngineClient("A");
        ExecutionResult result =
                new WorkflowExecutor(
                                Workflow.builder().name("invalid").steps(List.of(step)).build(),
                                autoCp(),
                                stub,
                                recordingCallback(),
                                "",
                                "zh")
                        .run()
                        .join();
        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("missing step"));
        assertEquals(0, stub.getSentCount());
    }

    @Test
    void cyclicWorkflowFailsBeforeExecution() {
        WorkflowStep first =
                WorkflowStep.builder()
                        .name("first")
                        .subtasks(List.of(task("A", "run")))
                        .next(List.of(jump("second", "")))
                        .build();
        WorkflowStep second =
                WorkflowStep.builder()
                        .name("second")
                        .subtasks(List.of(task("B", "run")))
                        .next(List.of(jump("first", "")))
                        .build();
        StubWorkflowEngineClient stub = new StubWorkflowEngineClient("A", "B");
        ExecutionResult result =
                new WorkflowExecutor(
                                Workflow.builder()
                                        .name("cycle")
                                        .steps(List.of(first, second))
                                        .build(),
                                autoCp(),
                                stub,
                                recordingCallback(),
                                "",
                                "zh")
                        .run()
                        .join();
        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("cycle"));
        assertEquals(0, stub.getSentCount());
    }

    @Test
    void mergeDoesNotWaitForUnselectedConditionalBranch() {
        WorkflowStep start =
                WorkflowStep.builder()
                        .name("start")
                        .layer(0)
                        .subtasks(List.of(task("A", "start")))
                        .next(List.of(jump("left", "left"), jump("right", "right")))
                        .build();
        WorkflowStep left =
                WorkflowStep.builder()
                        .name("left")
                        .layer(1)
                        .subtasks(List.of(task("B", "left")))
                        .next(List.of(jump("merge", "")))
                        .build();
        WorkflowStep right =
                WorkflowStep.builder()
                        .name("right")
                        .layer(1)
                        .subtasks(List.of(task("C", "right")))
                        .next(List.of(jump("merge", "")))
                        .build();
        WorkflowStep merge =
                WorkflowStep.builder()
                        .name("merge")
                        .layer(2)
                        .subtasks(List.of(task("M", "merge")))
                        .build();
        ControlPoint chooseRight =
                new ControlPoint() {
                    @Override
                    public CompletableFuture<TaskResponse> onTask(
                            TaskRequest request, TaskDispatcher taskDispatcher) {
                        return taskDispatcher.dispatch(
                                        dev.openan.workflow.engine.model.TaskSubmission.fromText(
                                                request.getAgentName(), request.getMessage()))
                                .thenApply(
                                        response ->
                                                TaskResponse.builder()
                                                        .success(true)
                                                        .output(response.getText())
                                                        .build());
                    }

                    @Override
                    public CompletableFuture<RouteDecision> onRoute(
                            String stepName,
                            Map<String, Object> results,
                            List<JumpCondition> conditions) {
                        return CompletableFuture.completedFuture(
                                RouteDecision.builder().nextStep("right").reason("test").build());
                    }
                };
        StubWorkflowEngineClient stub = new StubWorkflowEngineClient("A", "B", "C", "M");
        ExecutionResult result =
                new WorkflowExecutor(
                                Workflow.builder()
                                        .name("conditional-merge")
                                        .steps(List.of(start, left, right, merge))
                                        .build(),
                                chooseRight,
                                stub,
                                recordingCallback(),
                                "",
                                "zh")
                        .run()
                        .join();
        assertTrue(result.isSuccess());
        assertEquals(List.of("A", "C", "M"),
                stub.getSentMessages().stream().map(message -> message.agentName).toList());
    }

    @Test
    void selfLoopStepCallsOnSelfTaskNotOnTask() {
        // A SELF_LOOP step must dispatch via onSelfTask (local callback),
        // NOT onTask (A2A-T). The stub client records sends, so onSelfTask
        // producing a result without a send proves no A2A-T message went out.
        Task merge = Task.builder().agent("Workbench").description("merge results").build();
        WorkflowStep remote =
                WorkflowStep.builder()
                        .name("diag")
                        .layer(0)
                        .subtasks(List.of(task("SPN", "diagnose")))
                        .next(List.of(jump("merge", "")))
                        .build();
        WorkflowStep selfStep =
                WorkflowStep.builder()
                        .name("merge")
                        .layer(1)
                        .stepType(StepType.SELF_LOOP)
                        .subtasks(List.of(merge))
                        .next(List.of())
                        .build();
        Workflow wf = Workflow.builder().name("self-loop").steps(List.of(remote, selfStep)).build();
        StubWorkflowEngineClient stub = new StubWorkflowEngineClient("SPN");
        List<String> selfTaskMsgs = Collections.synchronizedList(new ArrayList<>());
        ControlPoint cp =
                new ControlPoint() {
                    @Override
                    public CompletableFuture<TaskResponse> onTask(
                            TaskRequest req, TaskDispatcher dispatcher) {
                        return dispatcher.dispatch(
                                        dev.openan.workflow.engine.model.TaskSubmission.fromText(
                                                req.getAgentName(), req.getMessage()))
                                .thenApply(
                                        r ->
                                                TaskResponse.builder()
                                                        .success(true)
                                                        .output(r.getText())
                                                        .build());
                    }

                    @Override
                    public CompletableFuture<TaskResponse> onSelfTask(TaskRequest req) {
                        selfTaskMsgs.add(req.getMessage());
                        return CompletableFuture.completedFuture(
                                TaskResponse.builder().success(true).output("merged").build());
                    }

                    @Override
                    public CompletableFuture<RouteDecision> onRoute(
                            String s, Map<String, Object> r, List<JumpCondition> c) {
                        return CompletableFuture.completedFuture(
                                RouteDecision.builder()
                                        .nextStep(c.get(0).getStep())
                                        .reason("first")
                                        .build());
                    }
                };
        WorkflowExecutor exec =
                new WorkflowExecutor(wf, cp, stub, recordingCallback(), "intent", "zh");
        ExecutionResult result = exec.run().join();
        assertTrue(result.isSuccess());
        // Only the remote diagnosis step sends via A2A-T; the merge step is local.
        assertEquals(1, stub.getSentCount());
        assertEquals("SPN", stub.getSentMessages().get(0).agentName);
        assertEquals(1, selfTaskMsgs.size());
        assertTrue(events.contains(EventType.TASK_REQUEST));
        assertTrue(events.contains(EventType.TASK_RESPONSE));
    }

    @Test
    void repeatedDescriptionsInParallelStepDoNotOverwriteResults() {
        WorkflowStep parallel =
                WorkflowStep.builder()
                        .name("diagnosis")
                        .layer(0)
                        .subtasks(
                                List.of(
                                        task("City1", "SPN专线故障诊断"),
                                        task("City2", "SPN专线故障诊断")))
                        .build();
        ControlPoint cp =
                new ControlPoint() {
                    @Override
                    public CompletableFuture<TaskResponse> onTask(
                            TaskRequest request, TaskDispatcher taskDispatcher) {
                        return CompletableFuture.completedFuture(
                                TaskResponse.builder()
                                        .success(true)
                                        .output(request.getAgentName() + " result")
                                        .build());
                    }

                    @Override
                    public CompletableFuture<RouteDecision> onRoute(
                            String stepName,
                            Map<String, Object> results,
                            List<JumpCondition> conditions) {
                        return CompletableFuture.completedFuture(
                                RouteDecision.builder().nextStep("end").build());
                    }
                };

        ExecutionResult result =
                new WorkflowExecutor(
                                Workflow.builder()
                                        .name("duplicate-descriptions")
                                        .steps(List.of(parallel))
                                        .build(),
                                cp,
                                new StubWorkflowEngineClient(),
                                recordingCallback(),
                                "",
                                "zh")
                        .run()
                        .join();

        assertTrue(result.isSuccess());
        assertEquals(
                Map.of(
                        "SPN专线故障诊断 [City1#0]", "City1 result",
                        "SPN专线故障诊断 [City2#1]", "City2 result"),
                result.getStepOutputs().get("diagnosis"));
        assertEquals(List.of(0, 1),
                result.getHistory().stream().map(item -> item.get("subtask_index")).toList());
    }

    @Test
    void nullTaskOutputAndNullExceptionMessageAreNormalized() {
        WorkflowStep step =
                WorkflowStep.builder()
                        .name("nullable")
                        .layer(0)
                        .subtasks(List.of(task("A", "empty")))
                        .build();
        ControlPoint nullOutput =
                new ControlPoint() {
                    @Override
                    public CompletableFuture<TaskResponse> onTask(
                            TaskRequest request, TaskDispatcher taskDispatcher) {
                        return CompletableFuture.completedFuture(
                                TaskResponse.builder().success(true).output(null).build());
                    }

                    @Override
                    public CompletableFuture<RouteDecision> onRoute(
                            String stepName,
                            Map<String, Object> results,
                            List<JumpCondition> conditions) {
                        return CompletableFuture.completedFuture(
                                RouteDecision.builder().nextStep("end").build());
                    }
                };
        ExecutionResult success =
                new WorkflowExecutor(
                                Workflow.builder().name("null-output").steps(List.of(step)).build(),
                                nullOutput,
                                new StubWorkflowEngineClient(),
                                recordingCallback(),
                                "",
                                "zh")
                        .run()
                        .join();
        assertTrue(success.isSuccess());
        assertEquals("", success.getStepOutputs().get("nullable").get("empty"));

        ControlPoint nullMessageFailure =
                new ControlPoint() {
                    @Override
                    public CompletableFuture<TaskResponse> onTask(
                            TaskRequest request, TaskDispatcher taskDispatcher) {
                        return CompletableFuture.failedFuture(new RuntimeException((String) null));
                    }

                    @Override
                    public CompletableFuture<RouteDecision> onRoute(
                            String stepName,
                            Map<String, Object> results,
                            List<JumpCondition> conditions) {
                        return CompletableFuture.completedFuture(
                                RouteDecision.builder().nextStep("end").build());
                    }
                };
        ExecutionResult failure =
                new WorkflowExecutor(
                                Workflow.builder().name("null-error").steps(List.of(step)).build(),
                                nullMessageFailure,
                                new StubWorkflowEngineClient(),
                                recordingCallback(),
                                "",
                                "zh")
                        .run()
                        .join();
        assertFalse(failure.isSuccess());
        assertFalse(String.valueOf(failure.getHistory().get(0).get("output")).isBlank());
    }
}
