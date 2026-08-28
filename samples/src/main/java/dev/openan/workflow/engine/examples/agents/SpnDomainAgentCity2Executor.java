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

import dev.openan.workflow.engine.examples.extension.PrePositionedExtensionHandler;
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
 * NORMAL. Diagnosis text is LLM-generated when the A2A-T .env is configured, else deterministic.
 * Authorization-T and Notification-T are initiated on independent channels.
 */
public class SpnDomainAgentCity2Executor extends NegotiationBaseAgentExecutor {
    private static final Logger log = LoggerFactory.getLogger(SpnDomainAgentCity2Executor.class);

    // Diagnosis result placed in artifact.metadata[Task-T URI], mirroring spec case 7.1 (normal).
    private static final String NORMAL_DIAGNOSIS_RESULT =
            "1. 诊断结果：成功\n"
                    + "2. 诊断结果详情：城市2OMC端口状态正常，光功率-17dBm(正常范围)，无异常告警，故障不在此地市\n"
                    + "3. 修复建议：城市2无需修复";

    public SpnDomainAgentCity2Executor() {
        super();
    }

    public SpnDomainAgentCity2Executor(PrePositionedExtensionHandler extensionHandler) {
        super(extensionHandler);
    }

    private static String llmDiagnosisResult(String input, String fallback) {
        String env = EnvResolver.resolveEnvPath();
        String sys = "你是SPN领域OMC故障诊断专家。诊断结果表示诊断任务是否成功完成，不表示是否发现故障。"
                + "本例必须输出“1. 诊断结果：成功”；城市2本地端口正常、无故障，不得写成诊断失败，"
                + "不得把城市2的任务对象称为城市1。继续输出2.诊断结果详情、3.修复建议，限300个中文字符，确保句子完整。";
        String user = "输入：\n" + input
                + "\n\n城市2 OMC 已核验本次任务对象：端口状态UP，光功率-17dBm，无告警。"
                + "结论是城市2侧诊断执行成功且未发现本地故障。请按指定结构输出。";
        String result = LlmHelper.text(env, sys, user, fallback);
        String normalized = result.replace("*", "").replace("#", "");
        boolean successfulDiagnosis =
                normalized.matches("(?s).*诊断结果\\s*[：:]?\\s*成功.*")
                        && (normalized.contains("正常") || normalized.contains("未发现"));
        if (!successfulDiagnosis) {
            log.warn(
                    "[SPN-Domain-Agent-City2] LLM output contradicted the verified normal state; using deterministic result");
            return fallback;
        }
        return result;
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
