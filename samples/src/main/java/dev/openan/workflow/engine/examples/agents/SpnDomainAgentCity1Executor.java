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

package dev.openan.workflow.engine.examples.agents;

import dev.openan.workflow.engine.examples.extension.AuthorizationPolicy;
import dev.openan.workflow.engine.examples.extension.PrePositionedExtensionHandler;
import dev.openan.workflow.engine.examples.util.LlmHelper;
import org.a2aproject.sdk.server.agentexecution.RequestContext;
import org.a2aproject.sdk.server.tasks.AgentEmitter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

import dev.openan.workflow.engine.examples.negotiation.NegotiationUtils;
import dev.openan.workflow.engine.examples.util.EnvResolver;
/**
 * SPN Domain Agent for City1 (City1 OMC).
 *
 * <p>Server-side negotiation-capable (extends {@link NegotiationBaseAgentExecutor}): on an incomplete task
 * it replies INPUT_REQUIRED to start a Negotiation-T round, and on the follow-up it runs the
 * diagnosis business. City1 side has a FAULT (port Down, optical power -28dBm). Diagnosis text is
 * LLM-generated when the A2A-T .env is configured, otherwise deterministic. The recovery event
 * payload is formatted deterministically so required protocol fields and identifiers cannot be
 * hallucinated. Authorization-T and Notification-T use independent channels.
 */
public class SpnDomainAgentCity1Executor extends NegotiationBaseAgentExecutor {
    private static final Logger log = LoggerFactory.getLogger(SpnDomainAgentCity1Executor.class);

    // Diagnosis result placed in artifact.metadata[Task-T URI], mirroring spec case 7.1.
    private static final String FAULT_DIAGNOSIS_RESULT =
            "1. 诊断结果：成功\n"
                    + "2. 诊断结果详情：P33206-YWHJ-业务汇聚机房1(990)的12-TPJ1EM8F-4端口出现无收光问题，"
                    + "判断原因为对端设备掉电或端口关闭\n"
                    + "3. 修复建议：恢复供电后重新启动网元，或者重新开启端口";

    private static final String ACCESS_PORT = "P781-珠江新城-PTN7900-23-TPA1EG24-17";
    private static final String RECOVERY_DETAIL =
            "将时延越限和丢包越限的受影响隧道调优到新的可用路径，保留原路径用于回退。";

    public SpnDomainAgentCity1Executor() {
        super();
    }

    public SpnDomainAgentCity1Executor(PrePositionedExtensionHandler extensionHandler) {
        super(extensionHandler);
    }

    private static String llmDiagnosisResult(String input, String fallback) {
        String env = EnvResolver.resolveEnvPath();
        String sys = "你是SPN领域OMC故障诊断专家。按如下结构输出：1. 诊断结果（成功/失败）；"
                + "2. 诊断结果详情（故障根因点资源对象、端口、现象）；3. 修复建议。简洁专业，中文。";
        String user = "输入：\n" + input + "\n\n已知：P33206-YWHJ-业务汇聚机房1(990)的12-TPJ1EM8F-4端口出现无收光问题。请输出诊断结果。";
        return LlmHelper.text(env, sys, user, fallback);
    }

    @Override
    protected String resolveEnvPath() {
        return EnvResolver.resolveEnvPath();
    }

    @Override
    protected String executeBusiness(RequestContext ctx, AgentEmitter emitter, SpnTaskInput input) {
        String diagnosisResult = llmDiagnosisResult(input.diagnosisInput(), FAULT_DIAGNOSIS_RESULT);
        publishRecoveryLifecycle(ctx, input);
        // Task-T returns diagnosis only. Recovery plan/result belong exclusively to the
        // independently established Notification-T subscription.
        return diagnosisResult;
    }

    /**
     * After diagnosis, publish the recovery plan. If it matches the active Authorization-T
     * whitelist, execute it automatically and publish the final result as a second event.
     */
    private void publishRecoveryLifecycle(RequestContext ctx, SpnTaskInput input) {
        AuthorizationPolicy policy = getAuthorizationPolicy();
        boolean inWhitelist =
                policy != null
                        && policy.authorizes(
                                "业务投诉诊断",
                                "业务抢通",
                                "隧道调优",
                                LocalDate.now(ZoneId.of("Asia/Shanghai")));
        String taskId = ctx.getTaskId() != null ? ctx.getTaskId() : "unknown-task";
        pushRecoveryPlan(formatRecoveryEvent("未启动", taskId, inWhitelist, null, null, input));
        if (inWhitelist) {
            log.info("[SPN-Domain-Agent] Fault in whitelist, self-triggering recovery");
            String recoveryResult =
                    formatRecoveryEvent(
                            "已结束",
                            taskId,
                            true,
                            "成功",
                            java.time.Instant.now().toString(), input);
            log.info(
                    "[SPN-Domain-Agent] Recovery result reported via Notification-T: {}",
                    recoveryResult);
            pushRecoveryResult(recoveryResult);
            return;
        }
        log.info(
                "[SPN-Domain-Agent] Recovery plan reported but automatic execution was not started because it is outside the active whitelist");
    }

    private static String formatRecoveryEvent(
            String state,
            String taskId,
            boolean authorized,
            String executionResult,
            String finishedAt, SpnTaskInput input) {
        StringBuilder event =
                new StringBuilder("### 业务抢通事件\n")
                        .append("1. 业务抢通方案执行状态：").append(state).append('\n')
                        .append("2. 投诉诊断任务流水号：").append(taskId).append('\n')
                        .append("3. OSS侧事件流水号：").append(input.incidentId()).append('\n')
                        .append("4. 接入端口名称：").append(input.accessPort()).append('\n')
                        .append("5. 是否已授权OMC自动抢通：").append(authorized ? "是" : "否").append('\n')
                        .append("6. 业务抢通方案名称：隧道调优\n")
                        .append("7. 业务抢通方案详情：").append(RECOVERY_DETAIL);
        if (finishedAt != null) {
            event.append('\n').append("8. 业务抢通方案执行结束时间：").append(finishedAt);
        }
        if (executionResult != null) {
            event.append('\n').append("9. 业务抢通方案执行结果：").append(executionResult);
        }
        return event.toString();
    }

    @Override
    protected java.util.List<String> invalidTaskFields(Map<String, Object> data) {
        var invalid = new java.util.ArrayList<>(super.invalidTaskFields(data));
        // Sample inventory: City1 must not diagnose a foreign or unknown access port.
        if (!ACCESS_PORT.equals(SpnTaskInput.field(data, "任务对象", "接入端口名称"))
                && !invalid.contains("任务对象")) invalid.add("任务对象");
        return invalid;
    }

    @Override
    protected Map<String, Object> buildResponseMetadata(RequestContext ctx, String response) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(NegotiationUtils.TASK_PROMPT_KEY, response);
        return metadata;
    }

    @Override
    protected String buildResultSummary() {
        return "专线业务投诉诊断任务诊断结果消息";
    }

    @Override
    protected String buildArtifactName() {
        return "spn-fault-diagnosis";
    }
}
