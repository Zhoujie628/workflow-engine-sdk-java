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
import dev.openan.workflow.engine.client.WorkflowEngineClient;
import dev.openan.workflow.engine.control.DefaultControlPoint;
import dev.openan.workflow.engine.client.A2ATExtension;
import dev.openan.workflow.engine.examples.negotiation.NegotiationUtils;
import dev.openan.workflow.engine.model.JumpCondition;
import dev.openan.workflow.engine.model.RouteDecision;
import dev.openan.workflow.engine.model.TaskRequest;
import dev.openan.workflow.engine.model.TaskResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
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
 * pre-positioned before the workflow starts, not handled here.
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
     * Build a targeted task message containing ONLY the parameters relevant to the target agent's
     * city. The upstream context (which mixes both cities' info) is NOT forwarded -- each sub-agent
     * receives a clean, self-contained task description with only its own scope.
     */
    private static String buildTargetedTaskMessage(String step) {
        return switch (step) {
           case "diagnosis_city1" -> buildCity1Task();
           case "diagnosis_city2" -> buildCity2Task();
            default -> "创建专线业务投诉诊断任务";
        };
    }

    private static String buildCity1Task() {
        // Task-T structured prompt, mirroring spec case 7.3 (missing params, triggers negotiation).
        return "## 任务类型(Task Type)\n传输专线业务投诉诊断\n\n"
                + "## 任务描述(Task Description)\n"
                + "基于<任务对象>、<任务上下文> 进行投诉场景的网络侧故障根因诊断, "
                + "达成<任务目标>中定义的投诉诊断目标，按照<预期输出>中定义的结构返回任务处理结果。\n\n"
                + "## 任务目标(Task Target)\n对网络侧故障进行诊断，返回故障根因和修复建议等诊断结果信息。\n\n"
                + "## 任务对象(Task Object)\n接入端口名称：\n\n"
                + "## 任务上下文(Task Context)\n"
                + "1. 投诉分类：\n"
                + "2. 问题发生时间：\"2026-05-11T08:21:46Z\"\n"
                + "3. OSS侧事件流水号：\"event-id-20260511-09013\"\n"
                + "4. 投诉详情：\"从5月11号早上8点半开始，深圳访问广州的响应延迟从平均12ms骤升至320ms，"
                + "访问广州机房的核心交易系统非常慢。\"\n\n"
                + "## 预期输出(Expected Output)\n"
                + "1. 诊断结果；参数的取值范围包括：成功、失败；(必选)\n"
                + "2. 诊断结果详情；(必选)\n"
                + "3. 修复建议；(可选)\n"
                + "4. 故障根因列表，每个故障根因包含故障根因名称、详细描述、修复建议、故障根因点位置等信息；(可选)";
    }

    private static String buildCity2Task() {
        return "## 任务类型(Task Type)\n传输专线业务投诉诊断\n\n"
                + "## 任务描述(Task Description)\n"
                + "基于<任务对象>、<任务上下文> 进行投诉场景的网络侧故障根因诊断, "
                + "达成<任务目标>中定义的投诉诊断目标，按照<预期输出>中定义的结构返回任务处理结果。\n\n"
                + "## 任务目标(Task Target)\n对网络侧故障进行诊断，返回故障根因和修复建议等诊断结果信息。\n\n"
                + "## 任务对象(Task Object)\n接入端口名称：P882-珠江新城-PTN7900-23-TPA1EG24-11\n\n"
                + "## 任务上下文(Task Context)\n"
                + "1. 投诉分类：\"专线质差\"\n"
                + "2. 问题发生时间：\"2026-05-11T08:21:46Z\"\n"
                + "3. OSS侧事件流水号：\"event-id-20260511-09013\"\n"
                + "4. 投诉详情：\"从5月11号早上8点半开始，深圳访问广州的响应延迟从平均12ms骤升至320ms，"
                + "访问广州机房的核心交易系统非常慢。\"\n\n"
                + "## 预期输出(Expected Output)\n"
                + "1. 诊断结果；参数的取值范围包括：成功、失败；(必选)\n"
                + "2. 诊断结果详情；(必选)\n"
                + "3. 修复建议；(可选)\n"
                + "4. 故障根因列表，每个故障根因包含故障根因名称、详细描述、修复建议、故障根因点位置等信息；(可选)";
    }

    @Override
    public CompletableFuture<TaskResponse> onTask(
            TaskRequest request, WorkflowEngineClient engineClient) {
        String step = request.getStepName();
        String agentName = request.getAgentName();
        String targetedMessage = buildTargetedTaskMessage(step);
        // Send the Task-T structured prompt via message metadata (spec §6), so the OMC agent
        // reads it from message.metadata[Task-T URI] exactly like spec cases 7.1/7.3.
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(NegotiationUtils.TASK_PROMPT_KEY, targetedMessage);
        // For diagnosis_city1, activate Negotiation-T extension (spec case 7.3: missing params
        // trigger negotiation). The ExtensionInterceptor adds it to the A2A-Extensions header
        // when the metadata key is present.
        if ("diagnosis_city1".equals(step)) {
            metadata.put(NegotiationUtils.NEGOTIATION_T_URI, "");
        }
        String partsText = "创建专线业务投诉诊断任务";
        return engineClient
                .sendMessage(agentName, partsText, null, metadata)
                .thenApply(
                        r -> {
                            boolean success = r.getText() != null && !r.getText().isEmpty();
                            log.info(
                                    "[onTask] Response from {}: {} chars, success={}",
                                    agentName,
                                    r.getText() != null ? r.getText().length() : 0,
                                    success);
                            return TaskResponse.builder()
                                    .success(success)
                                    .output(r.getText())
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

    @Override
    public CompletableFuture<TaskResponse> onSelfTask(TaskRequest request) {
        String step = request.getStepName();
        log.info(
                "[onSelfTask] Self-loop step={}, agent={} (local merge, no A2A-T)",
                step,
                request.getAgentName());
        String message = request.getMessage();
        String fallback = analyzeFaultLocation(message);
        String sys = "你是SPN跨城故障协同诊断汇总专家。按如下结构输出：1. 诊断结果；2. 诊断结果详情；3. 修复建议。简洁专业，中文。";
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
        // via the pre-positioned Authorization-T whitelist and reported through
        // the Notification-T channel. Just delegate to the default routing.
        return super.onRoute(stepName, results, conditions);
    }

    @Override
    public CompletableFuture<String> onNegotiation(
            String agentName, String negotiationText, Map<String, Object> receiveResult) {
        return negotiationStrategy.resolve(agentName, negotiationText, receiveResult);
    }
}
