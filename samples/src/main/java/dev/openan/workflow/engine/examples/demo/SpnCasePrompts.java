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

package dev.openan.workflow.engine.examples.demo;

import dev.openan.workflow.engine.client.A2ATExtension;
import dev.openan.workflow.engine.examples.extension.SdkSlotSchemaLoader;

import net.openan.a2at.sdk.core.model.StandardTemplates;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Original business data for the SPN private-line complaint scenario.
 *
 * <p>Holds the raw structured fields (not the rendered prompt): the demo passes these to the
 * engine's fromData track ({@code WorkflowEngineClient.sendMessageFromData}) and the A2A-T SDK
 * renders the Task-T prompt from the data + schema. This bypasses scenario recognition but the
 * SDK's schema-aware slot extraction may invoke its configured LLM. Callers hand over business
 * data, never a pre-rendered protocol prompt.
 *
 * <p>The prompt-fixture variants (blank object / unknown port) for the protocol verification
 * cases are expressed as data too: the same schema with different values.
 */
public final class SpnCasePrompts {

    /** Standard Task-T extension URI. */
    public static final String TASK_T_URI = A2ATExtension.TASK_T.uri();

    /** Northbound user text accompanying the Task-T prompt (spec case 7.1). */
    public static final String TASK_TEXT = "创建专线业务投诉诊断任务";

    private SpnCasePrompts() {}

    /**
     * JSON schema for the private-line complaint data, mirroring the SDK's bundled slot schema
     * ({@code Task-T/network-layer/private-line-complaint/v1}): 任务对象 identifies the line,
     * 任务上下文 carries the complaint context.
     */
    public static Map<String, Object> privateLineComplaintSchema() {
        return SdkSlotSchemaLoader.loadConfigured(StandardTemplates.PRIVATE_LINE_COMPLAINT);
    }

    /** Required business fields returned by Task-T validation for this scenario. */
    @SuppressWarnings("unchecked")
    public static List<String> privateLineComplaintSchemaProperties() {
        Object required = privateLineComplaintSchema().get("required");
        if (!(required instanceof List<?> fields)
                || fields.stream().anyMatch(field -> !(field instanceof String))) {
            throw new IllegalStateException(
                    "Current SDK private-line complaint schema has no string required list");
        }
        return (List<String>) fields;
    }

    /** Well-formed complaint (spec case 7.1): known faulty port in City1. */
    public static Map<String, Object> privateLineComplaintData() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("任务对象", "接入端口名称：P781-珠江新城-PTN7900-23-TPA1EG24-17");
        data.put(
                "任务上下文",
                "投诉分类：\"专线质差\"；问题发生时间：\"2026-05-11T08:21:46Z\"；"
                        + "OSS侧事件流水号：\"event-id-20260511-09013\"；"
                        + "投诉详情：\"从5月11号早上8点半开始，深圳访问广州的响应延迟从平均12ms骤升至320ms，"
                        + "访问广州机房的核心交易系统非常慢。\"");
        return data;
    }

    /** Blank task-object variant (spec case 7.3): triggers blank-slot negotiation. */
    public static Map<String, Object> privateLineComplaintDataBlankObject() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("任务对象", "");
        data.put(
                "任务上下文",
                "投诉分类：；问题发生时间：\"2026-05-11T08:21:46Z\"；"
                        + "OSS侧事件流水号：\"event-id-20260511-09013\"");
        return data;
    }

    /** Protocol-case fixture intentionally carrying a blank required task object. */
    public static String privateLineComplaintPromptBlankObject() {
        return "## 任务类型(Task Type)\n传输专线业务投诉诊断\n\n"
                + "## 任务对象(Task Object)\n接入端口名称：\n\n"
                + "## 任务上下文(Task Context)\n投诉分类：专线质差；"
                + "问题发生时间：2026-05-11T08:21:46Z；"
                + "OSS侧事件流水号：event-id-20260511-09013";
    }

    /** Unknown-port variant (spec case 7.4): triggers the semantic-error negotiation path. */
    public static Map<String, Object> privateLineComplaintDataUnknownPort() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("任务对象", "接入端口名称：P781-珠江新城-PTN7900-23-TPA1EG24-18");
        data.put(
                "任务上下文",
                "投诉分类：\"专线质差\"；问题发生时间：\"2026-05-11T08:21:46Z\"；"
                        + "OSS侧事件流水号：\"event-id-20260511-09013\"");
        return data;
    }

    /**
     * City2-scoped complaint for the workbench's southbound dispatch: complete parameters
     * (distinct port in City2's range), no negotiation expected.
     */
    public static Map<String, Object> privateLineComplaintDataCity2() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("任务对象", "接入端口名称：P882-珠江新城-PTN7900-23-TPA1EG24-11");
        data.put(
                "任务上下文",
                "投诉分类：\"专线质差\"；问题发生时间：\"2026-05-11T08:21:46Z\"；"
                        + "OSS侧事件流水号：\"event-id-20260511-09013\"；"
                        + "投诉详情：\"从5月11号早上8点半开始，深圳访问广州的响应延迟从平均12ms骤升至320ms，"
                        + "访问广州机房的核心交易系统非常慢。\"");
        return data;
    }

    /** Task-T metadata map carrying the given prompt under the standard extension key. */
    public static Map<String, Object> taskTMetadata(String taskPrompt) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(TASK_T_URI, taskPrompt);
        return metadata;
    }

    // ------------------------------------------------------------------
    // Authorization-T (spec case 7.5: add authorization policy)
    // ------------------------------------------------------------------

    /** Schema mirroring the SDK's Authorization-T slot schema (授权策略的操作类型 + 策略列表). */
    public static Map<String, Object> authorizationSchema() {
        return SdkSlotSchemaLoader.loadConfigured(
                StandardTemplates.AUTHORIZATION_POLICY_MANAGEMENT);
    }

    /** Add-authorization data (spec case 7.5): whitelist the tunnel-tuning recovery action. */
    public static Map<String, Object> addAuthorizationData() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("授权策略的操作类型", "新增授权策略");
        data.put(
                "动网操作的授权策略列表",
                "业务投诉诊断，业务抢通，隧道调优，2026-06-01~2030-06-18");
        return data;
    }

    // ------------------------------------------------------------------
    // Notification-T (spec case 7.8: subscribe service-recovery events)
    // ------------------------------------------------------------------

    /** Schema mirroring the SDK's Notification-T service-recovery slot schema. */
    public static Map<String, Object> serviceRecoverySchema() {
        return SdkSlotSchemaLoader.loadConfigured(StandardTemplates.SERVICE_RECOVERY);
    }

    /** Service-recovery subscription data (spec case 7.8). */
    public static Map<String, Object> subscribeServiceRecoveryData() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("订阅条件", "");
        data.put(
                "上报通知数据格式",
                "业务抢通事件数据包含：业务抢通方案执行状态（未启动、已结束）、投诉诊断任务流水号、"
                        + "OSS侧事件流水号、接入端口名称、是否已授权OMC自动抢通（是、否）、"
                        + "业务抢通方案名称、业务抢通方案详情、业务抢通方案执行结束时间、"
                        + "业务抢通方案执行结果（成功、失败）及失败原因。");
        return data;
    }
}
