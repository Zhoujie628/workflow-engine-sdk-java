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

import dev.openan.workflow.engine.examples.util.LlmHelper;
import org.a2aproject.sdk.server.agentexecution.RequestContext;
import org.a2aproject.sdk.server.tasks.AgentEmitter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

import dev.openan.workflow.engine.examples.negotiation.NegotiationUtils;
import dev.openan.workflow.engine.examples.util.EnvResolver;
/**
 * SPN Domain Agent for City2 (City2 OMC).
 *
 * <p>Server-side negotiation-capable (extends {@link NegotiationBaseAgentExecutor}). City2 side is
 * NORMAL. Diagnosis/recovery text is LLM-generated when the A2A-T .env is configured, else
 * deterministic. Authorization-T and Notification-T are pre-positioned before the workflow starts.
 */
public class SpnDomainAgentCity2Executor extends NegotiationBaseAgentExecutor {
    private static final Logger log = LoggerFactory.getLogger(SpnDomainAgentCity2Executor.class);

    // Diagnosis result placed in artifact.metadata[Task-T URI], mirroring spec case 7.1 (normal).
    private static final String NORMAL_DIAGNOSIS_RESULT =
            "1. 诊断结果：成功\n"
                    + "2. 诊断结果详情：城市2OMC端口状态正常，光功率-17dBm(正常范围)，无异常告警，故障不在此地市\n"
                    + "3. 修复建议：城市2无需修复";

    private static String llmDiagnosisResult(String input, String fallback) {
        String env = EnvResolver.resolveEnvPath();
        String sys = "你是SPN领域OMC故障诊断专家。按如下结构输出：1. 诊断结果（成功/失败）；"
                + "2. 诊断结果详情；3. 修复建议。城市2侧端口正常、无故障。简洁专业，中文。";
        String user = "输入：\n" + input + "\n\n城市2OMC端口port-3=UP，光功率-17dBm，无告警。请输出诊断结论。";
        return LlmHelper.text(env, sys, user, fallback);
    }

    @Override
    protected String resolveEnvPath() {
        return EnvResolver.resolveEnvPath();
    }

    @Override
    protected String executeBusiness(RequestContext ctx, AgentEmitter emitter, String input) {
        String result = llmDiagnosisResult(input, NORMAL_DIAGNOSIS_RESULT);
        log.info(
                "[SPN-Domain-Agent-City2] Diagnosis complete (City2), no fault, no recovery needed");
        return result;
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
