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
 * <p>Server-side negotiation-capable (extends {@link NegotiationBaseAgentExecutor}): on a new task
 * it replies INPUT_REQUIRED to start a Negotiation-T round, and on the follow-up it runs the
 * diagnosis/recovery business. City1 side has a FAULT (port Down, optical power -28dBm).
 * Diagnosis/recovery text is LLM-generated (deepseek) when the A2A-T .env is configured, else
 * deterministic. Authorization-T and Notification-T are initiated on independent channels,
 * so this agent no longer injects them in response metadata.
 */
public class SpnDomainAgentCity1Executor extends NegotiationBaseAgentExecutor {
    private static final Logger log = LoggerFactory.getLogger(SpnDomainAgentCity1Executor.class);

    // Diagnosis result placed in artifact.metadata[Task-T URI], mirroring spec case 7.1.
    private static final String FAULT_DIAGNOSIS_RESULT =
            "1. 诊断结果：成功\n"
                    + "2. 诊断结果详情：P33206-YWHJ-业务汇聚机房1(990)的12-TPJ1EM8F-4端口出现无收光问题，"
                    + "判断原因为对端设备掉电或端口关闭\n"
                    + "3. 修复建议：恢复供电后重新启动网元，或者重新开启端口";

    // Recovery (business-preemption) result reported via Notification-T, mirroring spec case 7.9.
    private static final String RECOVERY_RESULT =
            "### 业务抢通事件\n"
                    + "1. 业务抢通方案执行状态：已结束\n"
                    + "2. 投诉诊断任务流水号：9de168c0-6179-4778-8b72-4279582c0a3f\n"
                    + "3. OSS侧事件流水号：event-id-202606250128\n"
                    + "4. 接入端口名称：P781-珠江新城-PTN7900-23-TPA1EG24-17\n"
                    + "5. 是否已授权OMC自动抢通：是\n"
                    + "6. 业务抢通方案名称：隧道调优\n"
                    + "7. 业务抢通方案详情：将受影响的隧道列表调优到新的路径列表，业务恢复。\n"
                    + "8. 业务抢通方案执行结束时间：2026-05-11T08:31:46Z\n"
                    + "9. 业务抢通方案执行结果：成功";

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

    private static String llmRecoveryResult(String input, String fallback) {
        String env = EnvResolver.resolveEnvPath();
        String sys = "你是SPN领域OMC业务抢通执行专家。按业务抢通事件数据格式输出结果，"
                + "包含执行状态、方案名称、详情、执行结果。中文。";
        String user = "输入：\n" + input + "\n\n已执行隧道调优，业务恢复。请输出业务抢通结果。";
        return LlmHelper.text(env, sys, user, fallback);
    }

    @Override
    protected String resolveEnvPath() {
        return EnvResolver.resolveEnvPath();
    }

    @Override
    protected String executeBusiness(RequestContext ctx, AgentEmitter emitter, String input) {
        String diagnosisResult = llmDiagnosisResult(input, FAULT_DIAGNOSIS_RESULT);
        String recoveryResult = selfTriggerRecovery(ctx, diagnosisResult);
        return diagnosisResult + "\n\n" + recoveryResult;
    }

    /**
     * After diagnosis, check the active Authorization-T whitelist policy. If the repair
     * action matches the whitelist, execute recovery and return the result (reported as
     * Notification-T metadata by the base class). If not in whitelist, return a refusal message.
     */
    private String selfTriggerRecovery(RequestContext ctx, String diagnosisResult) {
        AuthorizationPolicy policy = getAuthorizationPolicy();
        boolean inWhitelist =
                policy != null
                        && policy.authorizes(
                                "业务投诉诊断",
                                "业务抢通",
                                "隧道调优",
                                LocalDate.now(ZoneId.of("Asia/Shanghai")));
        if (inWhitelist) {
            log.info("[SPN-Domain-Agent] Fault in whitelist, self-triggering recovery");
            String recoveryResult = llmRecoveryResult(diagnosisResult, RECOVERY_RESULT);
            log.info(
                    "[SPN-Domain-Agent] Recovery result reported via Notification-T: {}",
                    recoveryResult);
            pushNotificationResult(recoveryResult);
            return recoveryResult;
        }
        log.info("[SPN-Domain-Agent] Fault not in whitelist, refusing recovery");
        String refusalResult = "## 授权操作执行结果\n    授权操作执行结果：失败\n\n## 失败原因\n操作不在授权白名单内，拒绝执行业务抢通。";
        pushNotificationResult(refusalResult);
        return refusalResult;
    }

    @Override
    protected net.openan.a2at.sdk.negotiation.content.NegotiationProposeContent buildProposeContent(
            String input) {
        // Information negotiation: the two missing parameters required to start diagnosis.
        return new net.openan.a2at.sdk.negotiation.content.InformationProposeContent(
                java.util.List.of(
                        new net.openan.a2at.sdk.negotiation.content.NegotiationItem(
                                "接入端口名称",
                                "举例：P533-珠江旧城-PTN3900-23-TPA1EG24-1"),
                        new net.openan.a2at.sdk.negotiation.content.NegotiationItem(
                                "投诉分类", "举例：专线质差")),
                "以上参数均为必选，缺少无法启动诊断");
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
