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
import dev.openan.workflow.engine.model.TaskResponse;
import dev.openan.workflow.engine.model.TaskStatus;
import dev.openan.workflow.engine.model.Workflow;
import dev.openan.workflow.engine.model.WorkflowStep;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.stream.Collectors;

/**
 * Main entry point. Traverses DAG, calls ControlPoint at decision points. Mirrors Python
 * WorkflowExecutor.
 */
public class WorkflowExecutor {
    private static final Logger log = LoggerFactory.getLogger(WorkflowExecutor.class);

    private final Workflow workflow;
    private final ControlPoint controlPoint;
    private final WorkflowEngineClient engineClient;
    private final EventCallback eventCallback;
    private final ContextBuilder contextBuilder;
    private final String lang;
    private final Map<String, Map<String, Object>> stepOutputs = new ConcurrentHashMap<>();
    private final List<Map<String, Object>> executionHistory =
            Collections.synchronizedList(new ArrayList<>());

    public WorkflowExecutor(
            Workflow workflow,
            ControlPoint controlPoint,
            WorkflowEngineClient engineClient,
            EventCallback eventCallback,
            String runtimeIntent,
            String lang) {
        this.workflow = workflow;
        this.controlPoint = controlPoint;
        this.engineClient = engineClient;
        this.eventCallback = eventCallback != null ? eventCallback : new EventCallback();
        this.contextBuilder = new ContextBuilder(workflow, runtimeIntent);
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
        Set<Integer> activated = ConcurrentHashMap.newKeySet();
        for (int i = 0; i < workflow.getSteps().size(); i++) {
            var s = workflow.getSteps().get(i);
            if (contextBuilder.getStepPredecessors(s.getName()).isEmpty()) {
                pending.add(i);
                activated.add(i);
            }
        }
        Set<Integer> executed = ConcurrentHashMap.newKeySet();
        boolean[] failed = {false};
        return executeSteps(pending, executed, activated, failed)
                .thenApply(
                        v -> {
                            emit(EventType.WORKFLOW_COMPLETE, Map.of());
                            log.info(
                                    "[Executor] Workflow completed: {}, {} task(s) executed",
                                    workflow.getName(),
                                    executionHistory.size());
                            return ExecutionResult.builder()
                                    .success(!failed[0])
                                    .history(new ArrayList<>(executionHistory))
                                    .stepOutputs(new HashMap<>(stepOutputs))
                                    .error(failed[0] ? "Step execution failed" : null)
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
    }

    private CompletableFuture<Void> executeSteps(
            Deque<Integer> pending,
            Set<Integer> executed,
            Set<Integer> activated,
            boolean[] failed) {
        if (pending.isEmpty() || failed[0]) {
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
            stepFutures.add(executeStep(step, executed, activated, pending, failed));
        }
        return CompletableFuture.allOf(stepFutures.toArray(new CompletableFuture[0]))
                .thenCompose(v -> executeSteps(pending, executed, activated, failed));
    }

    private CompletableFuture<Void> executeStep(
            WorkflowStep step,
            Set<Integer> executed,
            Set<Integer> activated,
            Deque<Integer> pending,
            boolean[] failed) {
        emit(EventType.STEP_START, Map.of("step", step.getName()));
        log.info("--- Executing step: {} ---", step.getName());
        return executeSubtasks(step)
                .thenCompose(
                        result -> {
                            stepOutputs.put(step.getName(), result.results());
                            if (!result.success()) {
                                log.error("Step {} failed, stopping.", step.getName());
                                emit(
                                        EventType.ERROR,
                                        Map.of(
                                                "step",
                                                step.getName(),
                                                "results",
                                                result.results()));
                                failed[0] = true;
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
                                                    if (!executed.contains(nxt)
                                                            && !pending.contains(nxt)) {
                                                        activated.add(nxt);
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
            String taskDesc, String output, boolean success, Map<String, Object> results) {}

    private CompletableFuture<StepResult> executeSubtasks(WorkflowStep step) {
        String contextMessage = contextBuilder.buildContext(step, stepOutputs);
        List<CompletableFuture<StepResult>> futures = new ArrayList<>();
        for (int i = 0; i < step.getSubtasks().size(); i++) {
            final int subtaskIndex = i;
            final var task = step.getSubtasks().get(i);
            var request = buildTaskRequest(step, task, subtaskIndex, contextMessage);
            emit(
                    EventType.TASK_REQUEST,
                    Map.of(
                            "step",
                            step.getName(),
                            "agent",
                            task.getAgent(),
                            "task",
                            task.getDescription()));
            futures.add(
                    dispatchTask(step, request)
                            .thenApply(r -> processTaskResponse(step, task, subtaskIndex, r))
                            .exceptionally(e -> processTaskError(step, task, subtaskIndex, e)));
        }
        if (step.getStepType() == StepType.ANY_SUCCESS) {
            return anySuccess(futures);
        }
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> collectAllSuccess(futures));
    }

    private TaskRequest buildTaskRequest(
            WorkflowStep step, Task task, int subtaskIndex, String contextMessage) {
        String taskMessage =
                contextBuilder.buildTaskMessage(task.getDescription(), contextMessage, lang);
        log.info(
                "[Executor] Dispatching task: step={}, agent={}, subtask_index={}, desc={}",
                step.getName(),
                task.getAgent(),
                subtaskIndex,
                task.getDescription());
        log.debug("[Executor] Task message to {}: [{}]", task.getAgent(), taskMessage);
        return TaskRequest.builder()
                .agentName(task.getAgent())
                .skill(task.getSkill())
                .message(taskMessage)
                .description(task.getDescription())
                .context(contextMessage)
                .stepName(step.getName())
                .subtaskIndex(subtaskIndex)
                .build();
    }

    private CompletableFuture<TaskResponse> dispatchTask(WorkflowStep step, TaskRequest request) {
        if (step.getStepType() == StepType.SELF_LOOP) {
            log.info(
                    "[Executor] Self-loop task: step={}, agent={} (local, no A2A-T)",
                    step.getName(),
                    request.getAgentName());
            return controlPoint.onSelfTask(request);
        }
        return controlPoint.onTask(request, engineClient);
    }

    private StepResult collectAllSuccess(List<CompletableFuture<StepResult>> futures) {
        Map<String, Object> results = new HashMap<>();
        boolean anyFailed = false;
        for (var f : futures) {
            var r = f.join();
            results.put(r.taskDesc(), r.output());
            if (!r.success()) {
                anyFailed = true;
            }
        }
        return new StepResult(null, null, !anyFailed, results);
    }

    private StepResult processTaskResponse(
            WorkflowStep step, Task task, int subtaskIndex, TaskResponse response) {
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
        if (response.isSuccess() && response.getOutput() != null) {
            log.debug(
                    "[Executor] Task output from {}: [{}]", task.getAgent(), response.getOutput());
        }
        String output =
                response.isSuccess()
                        ? response.getOutput()
                        : (response.getError() != null ? response.getError() : "");
        executionHistory.add(
                Map.of(
                        "step",
                        step.getName(),
                        "task",
                        task.getDescription(),
                        "agent",
                        task.getAgent(),
                        "status",
                        status,
                        "output",
                        output));
        emit(
                EventType.TASK_RESPONSE,
                Map.of(
                        "step",
                        step.getName(),
                        "agent",
                        task.getAgent(),
                        "task",
                        task.getDescription(),
                        "output",
                        output));
        return new StepResult(task.getDescription(), output, response.isSuccess(), null);
    }

    private StepResult processTaskError(
            WorkflowStep step, Task task, int subtaskIndex, Throwable e) {
        task.setStatus(TaskStatus.FAILED);
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
                        TaskStatus.FAILED.getValue()));
        log.error(
                "[Executor] Task {} -> {}: exception: {}",
                task.getDescription(),
                task.getAgent(),
                e.getMessage());
        executionHistory.add(
                Map.of(
                        "step",
                        step.getName(),
                        "task",
                        task.getDescription(),
                        "agent",
                        task.getAgent(),
                        "status",
                        "failed",
                        "output",
                        e.getMessage()));
        return new StepResult(task.getDescription(), e.getMessage(), false, null);
    }

    /**
     * ANY_SUCCESS logic: iterate futures as they complete; on the first success, cancel the rest
     * and return success=true. If all fail, return success=false. Mirrors Python's
     * asyncio.as_completed loop.
     */
    private CompletableFuture<StepResult> anySuccess(List<CompletableFuture<StepResult>> futures) {
        if (futures.isEmpty()) {
            return CompletableFuture.completedFuture(new StepResult(null, null, true, Map.of()));
        }
        CompletableFuture<StepResult> result = new CompletableFuture<>();
        int total = futures.size();
        int[] completed = {0};
        int[] failedCount = {0};

        for (CompletableFuture<StepResult> f : futures) {
            f.handle(
                    (sr, ex) -> {
                        synchronized (completed) {
                            completed[0]++;
                            boolean success = (ex == null && sr != null && sr.success());
                            if (success && !result.isDone()) {
                                // Cancel all remaining
                                for (CompletableFuture<StepResult> other : futures) {
                                    if (!other.isDone()) {
                                        other.cancel(true);
                                    }
                                }
                                // Collect results from completed futures
                                Map<String, Object> results = new HashMap<>();
                                for (CompletableFuture<StepResult> cf : futures) {
                                    if (cf.isDone() && !cf.isCompletedExceptionally()) {
                                        try {
                                            var r = cf.join();
                                            if (r != null && r.taskDesc() != null) {
                                                results.put(r.taskDesc(), r.output());
                                            }
                                        } catch (Exception ignored) {
                                            // Cancellation race, skip
                                        }
                                    }
                                }
                                result.complete(new StepResult(null, null, true, results));
                            } else if (!success) {
                                failedCount[0]++;
                                if (completed[0] == total && !result.isDone()) {
                                    // All failed
                                    Map<String, Object> results = new HashMap<>();
                                    for (CompletableFuture<StepResult> cf : futures) {
                                        if (cf.isDone() && !cf.isCompletedExceptionally()) {
                                            try {
                                                var r = cf.join();
                                                if (r != null && r.taskDesc() != null) {
                                                    results.put(r.taskDesc(), r.output());
                                                }
                                            } catch (Exception ignored) {
                                                // Cancellation race, skip
                                            }
                                        }
                                    }
                                    result.complete(new StepResult(null, null, false, results));
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
        // Build context for onRoute: upstream context_from results + current step results
        Map<String, Object> routeContext = new HashMap<>();
        if (step.getContextFrom() != null) {
            for (String ref : step.getContextFrom()) {
                Map<String, Object> out = stepOutputs.get(ref);
                if (out != null) {
                    routeContext.put(ref, out);
                }
            }
        }
        routeContext.put(step.getName(), stepResult);
        return controlPoint
                .onRoute(step.getName(), routeContext, step.getNext())
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
