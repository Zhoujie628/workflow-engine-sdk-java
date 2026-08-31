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

import dev.openan.workflow.engine.client.WorkflowEngineClient;
import dev.openan.workflow.engine.control.ControlPoint;
import dev.openan.workflow.engine.control.EventCallback;
import dev.openan.workflow.engine.control.EventType;
import dev.openan.workflow.engine.model.ExecutionResult;
import dev.openan.workflow.engine.model.JumpCondition;
import dev.openan.workflow.engine.model.StepType;
import dev.openan.workflow.engine.model.Task;
import dev.openan.workflow.engine.model.TaskRequest;
import dev.openan.workflow.engine.model.TaskStatus;
import dev.openan.workflow.engine.model.Workflow;
import dev.openan.workflow.engine.model.WorkflowStep;
import dev.openan.workflow.engine.model.TaskExecutionResult;
import dev.openan.workflow.engine.model.TaskResult;
import dev.openan.workflow.engine.model.WorkflowInput;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Main entry point. Traverses DAG, calls ControlPoint at decision points. Mirrors Python
 * WorkflowExecutor.
 */
public class WorkflowExecutor {
    private static final Logger log = LoggerFactory.getLogger(WorkflowExecutor.class);

    private final Workflow workflow;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean stopped = new AtomicBoolean();
    private final Set<CompletableFuture<?>> activeTasks = ConcurrentHashMap.newKeySet();
    private final ControlPoint controlPoint;
    private final WorkflowEngineClient engineClient;
    private final EventCallback eventCallback;
    private final ContextBuilder contextBuilder;
    private final String lang;
    private final String executionId = java.util.UUID.randomUUID().toString();
    private final Map<String, Map<String, Object>> stepOutputs = new ConcurrentHashMap<>();
    private final Map<String, List<TaskExecutionResult>> stepExecutionResults = new ConcurrentHashMap<>();
    private final List<Map<String, Object>> executionHistory =
            Collections.synchronizedList(new ArrayList<>());

    public WorkflowExecutor(
            Workflow workflow,
            ControlPoint controlPoint,
            WorkflowEngineClient engineClient,
            EventCallback eventCallback,
            String runtimeIntent,
            String lang) {
        this.workflow =
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .convertValue(workflow, Workflow.class);
        this.controlPoint = controlPoint;
        this.engineClient = engineClient;
        this.eventCallback = eventCallback != null ? eventCallback : new EventCallback();
        this.contextBuilder = new ContextBuilder(this.workflow, runtimeIntent);
        this.lang = lang != null ? lang : "zh";
        try {
            this.engineClient.setControlPoint(this.controlPoint);
        } catch (Exception ignored) {
            // Engine client may not support control point injection
        }
        try {
            this.engineClient.setEventCallback(this.eventCallback);
        } catch (Exception ignored) {
            // Engine client may not support event callback injection
        }
        log.info(
                "[Executor] Workflow: {}, steps={}, intent={}, lang={}",
                workflow.getName(),
                workflow.getSteps().size(),
                runtimeIntent,
                lang);
    }

    /**
     * Current step outputs (mutable, updated during execution). Mirrors Python SDK's {@code
     * current_step_outputs} property.
     */
    public Map<String, Map<String, Object>> getCurrentStepOutputs() {
        return new HashMap<>(stepOutputs);
    }

    /**
     * Execution history (mutable, updated during execution). Mirrors Python SDK's {@code history}
     * property.
     */
    public List<Map<String, Object>> getHistory() {
        return new ArrayList<>(executionHistory);
    }

    private void emit(String type, Map<String, Object> data) {
        try {
            eventCallback.onEvent(type, data);
        } catch (Exception e) {
            log.warn("Event callback error: {}", e.getMessage());
        }
    }

    public CompletableFuture<ExecutionResult> run() {
        if (!started.compareAndSet(false, true))
            return CompletableFuture.failedFuture(
                    new IllegalStateException("WorkflowExecutor is single-use"));
        // NOTE: START lifecycle event is emitted by the runner (ExecutePsop),
        // not here. Mirrors Python SDK where the executor emits only
        // step/task/route events and the runner emits start/complete/error/close.
        log.info(
                "[Executor] Starting workflow: {} ({} steps)",
                workflow.getName(),
                workflow.getSteps().size());
        try {
            validateWorkflowGraph();
        } catch (IllegalArgumentException e) {
            log.error("[Executor] Invalid workflow graph: {}", e.getMessage());
            emit(EventType.ERROR, Map.of("error", e.getMessage()));
            return CompletableFuture.completedFuture(
                    ExecutionResult.builder()
                            .success(false)
                            .history(new ArrayList<>(executionHistory))
                            .stepOutputs(new HashMap<>(stepOutputs))
                            .error(e.getMessage())
                            .build());
        }
        Deque<Integer> pending = new ConcurrentLinkedDeque<>();
        Set<Integer> scheduled = ConcurrentHashMap.newKeySet();
        Set<Integer> activated = ConcurrentHashMap.newKeySet();
        for (int i = 0; i < workflow.getSteps().size(); i++) {
            var s = workflow.getSteps().get(i);
            if (contextBuilder.getStepPredecessors(s.getName()).isEmpty()) {
                pending.add(i);
                scheduled.add(i);
                activated.add(i);
            }
        }
        Set<Integer> executed = ConcurrentHashMap.newKeySet();
        AtomicBoolean failed = new AtomicBoolean();
        CompletableFuture<ExecutionResult> execution = executeSteps(pending, scheduled, executed, activated, failed)
                .thenApply(
                        v -> {
                            emit(EventType.WORKFLOW_COMPLETE, Map.of());
                            log.info(
                                    "[Executor] Workflow completed: {}, {} task(s) executed",
                                    workflow.getName(),
                                    executionHistory.size());
                            return ExecutionResult.builder()
                                    .success(!failed.get())
                                    .history(new ArrayList<>(executionHistory))
                                    .stepOutputs(new HashMap<>(stepOutputs))
                                    .error(failed.get() ? "Step execution failed" : null)
                                    .build();
                        })
                .exceptionally(
                        e -> {
                            log.error("[Executor] DAG traversal error: {}", e.getMessage(), e);
                            emit(EventType.ERROR, Map.of("error", e.getMessage()));
                            return ExecutionResult.builder()
                                    .success(false)
                                    .history(new ArrayList<>(executionHistory))
                                    .stepOutputs(new HashMap<>(stepOutputs))
                                    .error(e.getMessage())
                                    .build();
                        });
        execution.whenComplete((value, error) -> {
            stopped.set(true);
            activeTasks.forEach(task -> task.cancel(true));
        });
        return execution;
    }

    private CompletableFuture<Void> executeSteps(
            Deque<Integer> pending,
            Set<Integer> scheduled,
            Set<Integer> executed,
            Set<Integer> activated,
            AtomicBoolean failed) {
        if (pending.isEmpty() || failed.get() || stopped.get()) {
            return CompletableFuture.completedFuture(null);
        }

        // Collect all ready steps (predecessors complete) and deferred steps
        List<Integer> readySteps = new ArrayList<>();
        List<Integer> deferredSteps = new ArrayList<>();
        while (!pending.isEmpty()) {
            int idx = pending.pollFirst();
            if (idx >= workflow.getSteps().size() || executed.contains(idx)) {
                continue;
            }
            var step = workflow.getSteps().get(idx);
            var preds = contextBuilder.getStepPredecessors(step.getName());
            boolean activePredecessorsComplete =
                    preds.stream()
                            .filter(
                                    predecessor -> {
                                        Integer predecessorIndex =
                                                contextBuilder.findStepIndex(predecessor);
                                        return predecessorIndex != null
                                                && activated.contains(predecessorIndex);
                                    })
                            .allMatch(stepOutputs::containsKey);
            if (activePredecessorsComplete) {
                readySteps.add(idx);
            } else {
                deferredSteps.add(idx);
            }
        }
        // Add deferred steps back
        for (int idx : deferredSteps) {
            pending.addLast(idx);
        }
        if (readySteps.isEmpty()) {
            if (!deferredSteps.isEmpty()) {
                String details =
                        deferredSteps.stream()
                                .map(
                                        idx -> {
                                            WorkflowStep step = workflow.getSteps().get(idx);
                                            List<String> missing =
                                                    contextBuilder.getStepPredecessors(step.getName())
                                                            .stream()
                                                            .filter(
                                                                    predecessor -> {
                                                                        Integer predecessorIndex =
                                                                                contextBuilder
                                                                                        .findStepIndex(
                                                                                                predecessor);
                                                                        return predecessorIndex
                                                                                        != null
                                                                                && activated.contains(
                                                                                        predecessorIndex)
                                                                                && !stepOutputs.containsKey(
                                                                                        predecessor);
                                                                    })
                                                            .toList();
                                            return step.getName() + " <- " + missing;
                                        })
                                .collect(Collectors.joining(", "));
                return CompletableFuture.failedFuture(
                        new IllegalStateException(
                                "Workflow dependency deadlock; unresolved active predecessors: "
                                        + details));
            }
            return CompletableFuture.completedFuture(null);
        }
        // Execute all ready steps in parallel
        List<CompletableFuture<Void>> stepFutures = new ArrayList<>();
        for (int idx : readySteps) {
            executed.add(idx);
            var step = workflow.getSteps().get(idx);
            stepFutures.add(executeStep(step, scheduled, activated, pending, failed));
        }
        return CompletableFuture.allOf(stepFutures.toArray(new CompletableFuture[0]))
                .thenCompose(v -> executeSteps(pending, scheduled, executed, activated, failed));
    }

    private CompletableFuture<Void> executeStep(
            WorkflowStep step,
            Set<Integer> scheduled,
            Set<Integer> activated,
            Deque<Integer> pending,
            AtomicBoolean failed) {
        emit(EventType.STEP_START, Map.of("step", step.getName()));
        log.info("--- Executing step: {} ---", step.getName());
        return executeSubtasks(step)
                .thenCompose(
                        result -> {
                            stepOutputs.put(step.getName(), result.results());
                            stepExecutionResults.put(step.getName(), result.taskResults());
                            if (!result.success()) {
                                log.error("Step {} failed, stopping.", step.getName());
                                emit(
                                        EventType.ERROR,
                                        Map.of(
                                                "step",
                                                step.getName(),
                                                "results",
                                                result.results()));
                                failed.set(true);
                                return CompletableFuture.completedFuture(null);
                            }
                            emit(
                                    EventType.STEP_COMPLETE,
                                    Map.of("step", step.getName(), "results", result.results()));
                            return determineNextSteps(step, result.results())
                                    .thenAccept(
                                            nextIndices -> {
                                                for (int i = nextIndices.size() - 1; i >= 0; i--) {
                                                    int nxt = nextIndices.get(i);
                                                    activated.add(nxt);
                                                    if (scheduled.add(nxt)) {
                                                        pending.addFirst(nxt);
                                                    }
                                                }
                                            });
                        });
    }

    private void validateWorkflowGraph() {
        Map<String, Integer> indices = new HashMap<>();
        for (int i = 0; i < workflow.getSteps().size(); i++) {
            String name = workflow.getSteps().get(i).getName();
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("Workflow step name must not be blank");
            }
            if (indices.put(name, i) != null) {
                throw new IllegalArgumentException("Duplicate workflow step name: " + name);
            }
        }
        List<List<Integer>> graph = new ArrayList<>();
        for (int i = 0; i < workflow.getSteps().size(); i++) graph.add(new ArrayList<>());
        for (int i = 0; i < workflow.getSteps().size(); i++) {
            WorkflowStep step = workflow.getSteps().get(i);
            if (step.getNext() == null) continue;
            for (JumpCondition jump : step.getNext()) {
                if (isTerminalRoute(jump.getStep())) continue;
                Integer target = indices.get(jump.getStep());
                if (target == null) {
                    throw new IllegalArgumentException(
                            "Step " + step.getName() + " references missing step " + jump.getStep());
                }
                graph.get(i).add(target);
            }
        }
        for (WorkflowStep step : workflow.getSteps()) {
            List<String> contextFrom = step.getContextFrom();
            if (contextFrom == null || contextFrom.isEmpty()) continue;
            if (contextFrom.contains("*") && contextFrom.size() != 1) {
                throw new IllegalArgumentException(
                        "Step "
                                + step.getName()
                                + " cannot combine '*' with named context sources");
            }
            if (contextFrom.contains("*")) continue;
            Set<String> ancestors = Set.copyOf(contextBuilder.getAllPredecessors(step.getName()));
            for (String source : contextFrom) {
                if (!indices.containsKey(source)) {
                    throw new IllegalArgumentException(
                            "Step "
                                    + step.getName()
                                    + " references missing context source "
                                    + source);
                }
                if (!ancestors.contains(source)) {
                    throw new IllegalArgumentException(
                            "Step "
                                    + step.getName()
                                    + " context source "
                                    + source
                                    + " is not an upstream dependency");
                }
            }
        }
        int[] state = new int[workflow.getSteps().size()];
        for (int i = 0; i < state.length; i++) detectCycle(i, graph, state);
    }

    private void detectCycle(int node, List<List<Integer>> graph, int[] state) {
        if (state[node] == 2) return;
        if (state[node] == 1) {
            throw new IllegalArgumentException(
                    "Workflow graph contains a cycle at step "
                            + workflow.getSteps().get(node).getName());
        }
        state[node] = 1;
        for (int next : graph.get(node)) detectCycle(next, graph, state);
        state[node] = 2;
    }

    private static boolean isTerminalRoute(String stepName) {
        return "end".equals(stepName)
                || "retry".equals(stepName)
                || "endNode".equals(stepName);
    }

    private record StepResult(
            String taskDesc,
            String agentName,
            int subtaskIndex,
            List<Object> outputs,
            boolean success,
            Map<String, Object> results,
            List<TaskExecutionResult> taskResults) {}

    private CompletableFuture<StepResult> executeSubtasks(WorkflowStep step) {
        var workflowInput = contextBuilder.buildWorkflowInput(step, stepExecutionResults);
        List<CompletableFuture<StepResult>> futures = new ArrayList<>();
        for (int i = 0; i < step.getSubtasks().size(); i++) {
            final int subtaskIndex = i;
            final var task = step.getSubtasks().get(i);
            var request = buildTaskRequest(step, task, subtaskIndex, workflowInput);
            emit(
                    EventType.TASK_REQUEST,
                    Map.of(
                            "step",
                            step.getName(),
                            "agent",
                            task.getAgent(),
                            "task",
                            task.getDescription()));
            CompletableFuture<TaskResult> dispatch = dispatchTask(step, request);
            CompletableFuture<StepResult> processed = dispatch
                            .thenApply(r -> processTaskResult(step, task, subtaskIndex, r))
                            .exceptionally(e -> processTaskError(step, task, subtaskIndex, e));
            processed.whenComplete((result, error) -> {
                if (processed.isCancelled()) dispatch.cancel(true);
            });
            futures.add(processed);
        }
        if (step.getStepType() == StepType.ANY_SUCCESS) {
            return anySuccess(futures);
        }
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> collectAllSuccess(futures));
    }

    private String taskId(String stepName, int index) {
        return java.util
                .UUID
                .nameUUIDFromBytes(
                        (executionId + ":" + stepName + ":" + index)
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .toString();
    }

    private TaskRequest buildTaskRequest(
            WorkflowStep step, Task task, int subtaskIndex, WorkflowInput workflowInput) {
        log.info(
                "[Executor] Dispatching task: step={}, agent={}, subtask_index={}, desc={}",
                step.getName(),
                task.getAgent(),
                subtaskIndex,
                task.getDescription());
        return TaskRequest.builder()
                .agentName(task.getAgent())
                .skill(task.getSkill())
                .instruction(task.getDescription())
                .language(lang)
                .stepName(step.getName())
                .executionId(executionId)
                .taskId(taskId(step.getName(), subtaskIndex))
                .input(
                        task.getInput() == null
                                ? dev.openan.workflow.engine.model.BusinessInput.text(
                                        task.getDescription())
                                : task.getInput())
                .workflowInput(workflowInput)
                .build();
    }

    private CompletableFuture<TaskResult> dispatchTask(WorkflowStep step, TaskRequest request) {
        CompletableFuture<TaskResult> result = new CompletableFuture<>();
        activeTasks.add(result);
        result.orTimeout(engineClient.callbackTimeoutSeconds(), java.util.concurrent.TimeUnit.SECONDS);
        result.whenComplete((value, error) -> activeTasks.remove(result));
        CompletableFuture.runAsync(() -> {
            try {
                if (result.isDone() || stopped.get()) return;
                if (step.getStepType() == StepType.SELF_LOOP) {
                    completeFrom(result, java.util.Objects.requireNonNull(controlPoint.onSelfTask(request),
                            "onSelfTask returned null future"));
                    return;
                }
                var prepared = java.util.Objects.requireNonNull(controlPoint.onTask(request), "onTask returned null future");
                prepared.whenComplete((content, error) -> {
                    if (result.isDone() || stopped.get()) {
                        log.debug("[Executor] Late callback ignored step={}, taskId={}", request.getStepName(), request.getTaskId());
                        return;
                    }
                    if (error != null) { result.completeExceptionally(error); return; }
                    try {
                        var sent = engineClient.dispatch(request,
                                java.util.Objects.requireNonNull(content, "onTask returned null content"), controlPoint);
                        result.whenComplete((value, failure) -> { if (!sent.isDone()) sent.cancel(true); });
                        completeFrom(result, sent.thenApply(ProtocolResultAdapter::toTaskResult));
                    } catch (RuntimeException failure) { result.completeExceptionally(failure); }
                });
            } catch (RuntimeException error) { result.completeExceptionally(error); }
        });
        return result;
    }

    private static <T> void completeFrom(CompletableFuture<T> destination, CompletableFuture<T> source) {
        source.whenComplete((value, error) -> {
            if (error != null) destination.completeExceptionally(error);
            else if (value == null) destination.completeExceptionally(new IllegalStateException("Callback returned null"));
            else destination.complete(value);
        });
    }

    private StepResult collectAllSuccess(List<CompletableFuture<StepResult>> futures) {
        List<StepResult> completedResults = new ArrayList<>();
        boolean anyFailed = false;
        for (var f : futures) {
            var r = f.join();
            completedResults.add(r);
            if (!r.success()) {
                anyFailed = true;
            }
        }
        return new StepResult(
                null, null, -1,
                List.of(), !anyFailed, collectTaskResults(completedResults),
                collectExecutionResults(completedResults));
    }

    private static List<TaskExecutionResult> collectExecutionResults(
            List<StepResult> completedResults) {
        return completedResults.stream().flatMap(result -> result.taskResults().stream()).toList();
    }

    /** Keeps every subtask result even when descriptions repeat within one parallel step. */
    private static Map<String, Object> collectTaskResults(List<StepResult> completedResults) {
        Map<String, Long> descriptionCounts =
                completedResults.stream()
                        .filter(result -> result.taskDesc() != null)
                        .collect(
                                Collectors.groupingBy(
                                        StepResult::taskDesc,
                                        LinkedHashMap::new,
                                        Collectors.counting()));
        Map<String, Object> results = new LinkedHashMap<>();
        for (StepResult result : completedResults) {
            if (result.taskDesc() == null) continue;
            boolean duplicate = descriptionCounts.getOrDefault(result.taskDesc(), 0L) > 1;
            String key =
                    duplicate
                            ? result.taskDesc()
                                    + " ["
                                    + result.agentName()
                                    + "#"
                                    + result.subtaskIndex()
                                    + "]"
                            : result.taskDesc();
            results.put(key, result.outputs());
        }
        return results;
    }

    private StepResult processTaskResult(
            WorkflowStep step, Task task, int subtaskIndex, TaskResult response) {
        task.setStatus(response.isSuccess() ? TaskStatus.SUCCESS : TaskStatus.FAILED);
        emit(
                EventType.TASK_STATUS_CHANGED,
                Map.of(
                        "step",
                        step.getName(),
                        "subtask_index",
                        subtaskIndex,
                        "agent",
                        task.getAgent(),
                        "status",
                        task.getStatus().getValue()));
        String status = response.isSuccess() ? "success" : "failed";
        log.info("[Executor] Task {} -> {}: {}", task.getDescription(), task.getAgent(), status);
        if (response.isSuccess() && !response.getOutputs().isEmpty()) {
            log.debug(
                    "[Executor] Task outputs from {}: [{}]", task.getAgent(), response.getOutputs());
        }
        List<Object> outputs =
                response.getOutputs();
        executionHistory.add(
                Map.of(
                        "step",
                        step.getName(),
                        "subtask_index",
                        subtaskIndex,
                        "task",
                        task.getDescription(),
                        "agent",
                        task.getAgent(),
                        "status",
                        status,
                        "outputs",
                        outputs,
                        "error",
                        response.getError() == null ? "" : response.getError(),
                        "errorCode",
                        response.getErrorCode() == null ? "" : response.getErrorCode(),
                        "errorDetails",
                        response.getErrorDetails()));
        emit(
                EventType.TASK_RESPONSE,
                Map.of(
                        "step",
                        step.getName(),
                        "subtask_index",
                        subtaskIndex,
                        "agent",
                        task.getAgent(),
                        "task",
                        task.getDescription(),
                        "outputs",
                        outputs,
                        "success",
                        response.isSuccess(),
                        "error",
                        response.getError() == null ? "" : response.getError(),
                        "errorCode",
                        response.getErrorCode() == null ? "" : response.getErrorCode(),
                        "errorDetails",
                        response.getErrorDetails()));
        return new StepResult(
                task.getDescription(),
                task.getAgent(),
                subtaskIndex,
                outputs,
                response.isSuccess(),
                null,
                List.of(
                        new TaskExecutionResult(
                                task.getAgent(),
                                task.getSkill(),
                                taskId(step.getName(), subtaskIndex),
                                task.getDescription(),
                                task.getStatus(),
                                outputs,
                                response.getReceivedMessages(),
                                response.getError(),
                                response.getErrorCode(),
                                response.getErrorDetails())));
    }

    private StepResult processTaskError(
            WorkflowStep step, Task task, int subtaskIndex, Throwable e) {
        return processTaskResult(step, task,
                        subtaskIndex, FailureMapping.from(e));
    }

    /**
     * ANY_SUCCESS logic: iterate futures as they complete; on the first success, cancel the rest
     * and return success=true. If all fail, return success=false. Mirrors Python's
     * asyncio.as_completed loop.
     */
    private CompletableFuture<StepResult> anySuccess(List<CompletableFuture<StepResult>> futures) {
        if (futures.isEmpty()) {
            return CompletableFuture.completedFuture(
                    new StepResult(null, null, -1, List.of(), true, Map.of(), List.of()));
        }
        CompletableFuture<StepResult> result = new CompletableFuture<>();
        int total = futures.size();
        int[] completed = {0};
        int[] failedCount = {0};
        boolean[] winnerChosen = {false};

        for (CompletableFuture<StepResult> f : futures) {
            f.handle(
                    (sr, ex) -> {
                        synchronized (completed) {
                            completed[0]++;
                            boolean success = (ex == null && sr != null && sr.success());
                            if (success && !result.isDone()) {
                                winnerChosen[0] = true;
                                // Cancel all remaining
                                for (CompletableFuture<StepResult> other : futures) {
                                    if (!other.isDone()) {
                                        other.cancel(true);
                                    }
                                }
                                // Collect results from completed futures
                                List<StepResult> completedResults = new ArrayList<>();
                                for (CompletableFuture<StepResult> cf : futures) {
                                    if (cf.isDone() && !cf.isCompletedExceptionally()) {
                                        try {
                                            var r = cf.join();
                                            if (r != null && r.taskDesc() != null) {
                                                completedResults.add(r);
                                            }
                                        } catch (Exception ignored) {
                                            // Cancellation race, skip
                                        }
                                    }
                                }
                                result.complete(
                                        new StepResult(
                                                null,
                                                null,
                                                -1,
                                                List.of(),
                                                true,
                                                collectTaskResults(completedResults),
                                                collectExecutionResults(completedResults)));
                            } else if (!success && !winnerChosen[0]) {
                                failedCount[0]++;
                                if (completed[0] == total && !result.isDone()) {
                                    // All failed
                                    List<StepResult> completedResults = new ArrayList<>();
                                    for (CompletableFuture<StepResult> cf : futures) {
                                        if (cf.isDone() && !cf.isCompletedExceptionally()) {
                                            try {
                                                var r = cf.join();
                                                if (r != null && r.taskDesc() != null) {
                                                    completedResults.add(r);
                                                }
                                            } catch (Exception ignored) {
                                                // Cancellation race, skip
                                            }
                                        }
                                    }
                                    result.complete(
                                            new StepResult(
                                                    null,
                                                    null,
                                                    -1,
                                                    List.of(),
                                                    false,
                                                    collectTaskResults(completedResults),
                                                    collectExecutionResults(completedResults)));
                                }
                            }
                        }
                        return null;
                    });
        }
        return result;
    }

    private CompletableFuture<List<Integer>> determineNextSteps(
            WorkflowStep step, Map<String, Object> stepResult) {
        if (step.getNext() == null || step.getNext().isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        }
        if (step.getNext().stream()
                .allMatch(jc -> jc.getCondition() == null || jc.getCondition().isEmpty())) {
            List<Integer> indices = new ArrayList<>();
            for (var jc : step.getNext()) {
                if (isTerminalRoute(jc.getStep())) {
                    continue;
                }
                Integer idx = contextBuilder.findStepIndex(jc.getStep());
                if (idx != null) {
                    indices.add(idx);
                }
            }
            return CompletableFuture.completedFuture(indices);
        }
        var routeRequest = new dev.openan.workflow.engine.model.RouteRequest(
                        executionId,
                        step.getName(),
                        contextBuilder.buildWorkflowInput(step, stepExecutionResults),
                        stepExecutionResults.getOrDefault(step.getName(), List.of()),
                        step.getNext().stream()
                                .map(
                                        option ->
                                                new dev.openan.workflow.engine.model.RouteRequest
                                                        .RouteOption(
                                                        option.getStep(), option.getCondition()))
                                .toList());
        return controlPoint
                .onRoute(routeRequest)
                .orTimeout(engineClient.callbackTimeoutSeconds(), java.util.concurrent.TimeUnit.SECONDS)
                .thenApply(
                        decision -> {
                            log.info(
                                    "Route for '{}': {} ({})",
                                    step.getName(),
                                    decision.getNextStep(),
                                    decision.getReason());
                            emit(
                                    EventType.ROUTE_DECISION,
                                    Map.of(
                                            "step",
                                            step.getName(),
                                            "next",
                                            decision.getNextStep(),
                                            "reason",
                                            decision.getReason()));
                            List<String> allowed =
                                    step.getNext().stream()
                                            .map(JumpCondition::getStep)
                                            .collect(Collectors.toList());
                            if (!allowed.contains(decision.getNextStep())) {
                                throw new IllegalStateException(
                                        "onRoute returned '"
                                                + decision.getNextStep()
                                                + "' for step '"
                                                + step.getName()
                                                + "', allowed next steps are "
                                                + allowed);
                            }
                            if (isTerminalRoute(decision.getNextStep())) return List.of();
                            Integer idx = contextBuilder.findStepIndex(decision.getNextStep());
                            if (idx == null)
                                throw new IllegalStateException(
                                        "Route target does not exist: " + decision.getNextStep());
                            return List.of(idx);
                        });
    }
}
