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

package dev.openan.workflow.engine.examples.workbench;

import dev.openan.workflow.engine.examples.util.LlmHelper;
import dev.openan.workflow.engine.control.DefaultControlPoint;
import dev.openan.workflow.engine.control.TaskDispatcher;
import dev.openan.workflow.engine.model.JumpCondition;
import dev.openan.workflow.engine.model.NegotiationDecision;
import dev.openan.workflow.engine.model.NegotiationRequest;
import dev.openan.workflow.engine.model.RouteDecision;
import dev.openan.workflow.engine.model.TaskRequest;
import dev.openan.workflow.engine.model.TaskResponse;
import dev.openan.workflow.engine.model.TaskSubmission;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import dev.openan.workflow.engine.examples.negotiation.NegotiationStrategy;
import dev.openan.workflow.engine.examples.util.EnvResolver;
/**
 * ControlPoint for the SPN cross-city diagnosis workflow.
 *
 * <p>Handles task dispatch (with city-specific message enrichment), route decisions (fault-based
 * routing to recovery steps), and negotiation responses. Authorization-T and Notification-T are
 * initiated independently of the workflow, not handled here.
 *
 * <p>SRP: this class only contains workflow decision logic, separating it from the agent executor
 * that handles message I/O.
 */
public class WorkbenchControlPoint extends DefaultControlPoint {
    private static final Logger log = LoggerFactory.getLogger(WorkbenchControlPoint.class);

    private final String a2atEnvPath;
    private final NegotiationStrategy negotiationStrategy;

    public WorkbenchControlPoint() {
        this(null, null);
    }

    public WorkbenchControlPoint(String a2atEnvPath) {
        this(a2atEnvPath, null);
    }

    public WorkbenchControlPoint(String a2atEnvPath, NegotiationStrategy negotiationStrategy) {
        this.a2atEnvPath = a2atEnvPath != null ? a2atEnvPath : EnvResolver.resolveEnvPath();
        this.negotiationStrategy =
                negotiationStrategy != null
                        ? negotiationStrategy
                        : new NegotiationStrategy(this.a2atEnvPath);
    }

    private static String analyzeFaultLocation(String messageText) {
        // Identify City1 fault by spec case 7.1 diagnostic-detail fault signatures, since the
        // diagnosis text now follows the document format (no longer contains the "城市1" keyword).
        boolean hasCity1Fault =
                messageText.contains("无收光")
                        || messageText.contains("掉电")
                        || messageText.contains("端口关闭")
                        || messageText.contains("端口Down")
                        || (messageText.contains("城市1")
                                && (messageText.contains("故障") || messageText.contains("Down")));
        boolean hasCity2Fault =
                messageText.contains("城市2")
                        && (messageText.contains("故障") || messageText.contains("Down"));
        if (hasCity1Fault) {
            // Merge result mirroring spec case 7.1 diagnosis summary in Task-T metadata.
            return "1. 诊断结果：成功\n"
                    + "2. 诊断结果详情：汇总分析完成。故障定位：城市1地市OMC，"
                    + "P33206-YWHJ-业务汇聚机房1(990)的12-TPJ1EM8F-4端口出现无收光问题，"
                    + "判断原因为对端设备掉电或端口关闭\n"
                    + "3. 修复建议：恢复供电后重新启动网元，或者重新开启端口";
        }
        if (hasCity2Fault) {
            return "1. 诊断结果：成功\n"
                    + "2. 诊断结果详情：汇总分析完成。城市2地市OMC端口状态正常，"
                    + "光功率-17dBm(正常范围)，无异常告警，故障不在此地市\n"
                    + "3. 修复建议：城市2无需修复";
        }
        return "1. 诊断结果：成功\n2. 诊断结果详情：汇总分析完成。两地市均未见异常。\n3. 修复建议：无需修复";
    }

    /**
     * Build the city-scoped structured data for the target agent's step. The upstream context
     * (which mixes both cities' info) is NOT forwarded — each sub-agent receives a clean,
     * self-contained complaint with only its own scope. Both production workflow branches carry
     * complete diagnosis input. Negotiation-T protocol cases are exercised independently and do
     * not alter this main business flow.
     */
    private static java.util.Map<String, Object> buildTaskData(String step) {
        return switch (step) {
            case "diagnosis_city1" ->
                    dev.openan.workflow.engine.examples.demo.SpnCasePrompts
                            .privateLineComplaintData();
            case "diagnosis_city2" ->
                    dev.openan.workflow.engine.examples.demo.SpnCasePrompts
                            .privateLineComplaintDataCity2();
            default ->
                    dev.openan.workflow.engine.examples.demo.SpnCasePrompts
                            .privateLineComplaintData();
        };
    }

    @Override
    public CompletableFuture<TaskResponse> onTask(
            TaskRequest request, TaskDispatcher taskDispatcher) {
        String step = request.getStepName();
        String agentName = request.getAgentName();
        // Structured-data track: hand over the city-scoped complaint fields + schema; the SDK
        // renders the Task-T prompt through its schema-aware pipeline (no hand-written prompt).
        String partsText = "创建专线业务投诉诊断任务";
        Map<String, Object> taskData = buildTaskData(step);
        return sendTaskWithRenderingRetry(
                        taskDispatcher, agentName, partsText, taskData, 1)
                .thenApply(
                        r -> {
                            boolean success =
                                    r.getTaskState() != null
                                            && r.getTaskState().contains("TASK_STATE_COMPLETED");
                            log.info(
                                    "[onTask] Response from {}: {} chars, success={}",
                                    agentName,
                                    r.getText() != null ? r.getText().length() : 0,
                                    success);
                            return TaskResponse.builder()
                                    .success(success)
                                    .output(r.getText())
                                    .error(success
                                            ? null
                                            : "Agent returned state=" + r.getTaskState())
                                    .build();
                        })
                .exceptionally(
                        e -> {
                            log.error("[onTask] Failed for {}: {}", agentName, e.getMessage());
                            return TaskResponse.builder()
                                    .success(false)
                                    .error("Agent call failed: " + e.getMessage())
                                    .build();
                        });
    }

    private CompletableFuture<dev.openan.workflow.engine.model.SendMessageResult>
            sendTaskWithRenderingRetry(
                    TaskDispatcher taskDispatcher,
                    String agentName,
                    String message,
                    Map<String, Object> taskData,
                    int attempt) {
        TaskSubmission submission =
                TaskSubmission.fromData(
                        agentName,
                        message,
                        taskData,
                        dev.openan.workflow.engine.examples.demo.SpnCasePrompts
                                .privateLineComplaintSchema(),
                        net.openan.a2at.sdk.core.model.StandardTemplates.PRIVATE_LINE_COMPLAINT);
        return taskDispatcher
                .dispatch(submission)
                .exceptionallyCompose(error -> {
                    Throwable cause = unwrap(error);
                    if (attempt < 3
                            && cause.getMessage() != null
                            && cause.getMessage().contains("Task-T fromData rendering failed")) {
                        log.warn(
                                "[onTask] SDK Task-T rendering failed for {}, retry={}/3: {}",
                                agentName,
                                attempt + 1,
                                cause.getMessage());
                        return sendTaskWithRenderingRetry(
                                taskDispatcher, agentName, message, taskData, attempt + 1);
                    }
                    return CompletableFuture.failedFuture(cause);
                });
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while ((current instanceof java.util.concurrent.CompletionException
                        || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    @Override
    public CompletableFuture<TaskResponse> onSelfTask(TaskRequest request) {
        String step = request.getStepName();
        log.info(
                "[onSelfTask] Self-loop step={}, agent={} (local merge, no A2A-T)",
                step,
                request.getAgentName());
        String message = request.getMessage();
        String fallback = analyzeFaultLocation(message);
        String sys = "你是SPN跨城故障协同诊断汇总专家。仅依据两地市结果进行汇总，不补造网元和端口。"
                + "按1.诊断结果、2.诊断结果详情、3.修复建议输出；明确城市1存在无收光故障、城市2诊断成功且本地正常。"
                + "全文不超过450个中文字符，不使用表格，最后一句必须完整结束。";
        String result = LlmHelper.text(a2atEnvPath, sys, message, fallback);
        log.info("[onSelfTask] Merge result ({} chars): {}", result.length(), result);
        return CompletableFuture.completedFuture(
                TaskResponse.builder().success(true).output(result).build());
    }

    @Override
    public CompletableFuture<RouteDecision> onRoute(
            String stepName, Map<String, Object> results, List<JumpCondition> conditions) {
        // merge_analysis has an unconditional next -> endNode, so the executor
        // never calls onRoute for it. Recovery is self-triggered by SPN agents
        // via the active Authorization-T whitelist and reported through
        // the Notification-T channel. Just delegate to the default routing.
        return super.onRoute(stepName, results, conditions);
    }

    @Override
    public CompletableFuture<NegotiationDecision> onNegotiation(NegotiationRequest request) {
        return negotiationStrategy.resolve(request);
    }
}
