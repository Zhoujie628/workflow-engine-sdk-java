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

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Original business data for the SPN private-line complaint scenario.
 *
 * <p>Holds the raw structured fields (not the rendered prompt): the demo passes these to the
 * engine's fromData track ({@code WorkflowEngineClient.sendMessageFromData}) and the A2A-T SDK
 * renders the Task-T prompt deterministically from the data + schema. This mirrors the official
 * SDK sample pattern — callers hand over business data, never a pre-rendered prompt.
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
        Map<String, Object> object = new LinkedHashMap<>();
        object.put("type", "string");
        object.put("description", "专线名称/专线业务标识/接入端口名称，三选一标识专线业务对象");
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("type", "string");
        context.put(
                "description",
                "专线业务的故障现象描述和诊断任务上下文：投诉分类(专线中断/专线质差)、问题发生时间、"
                        + "OSS侧事件流水号、投诉详情");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("任务对象", object);
        properties.put("任务上下文", context);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        return schema;
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
        Map<String, Object> operationType = new LinkedHashMap<>();
        operationType.put("type", "string");
        operationType.put("description", "授权策略的操作类型：新增/修改/删除/查询授权策略");
        Map<String, Object> policyList = new LinkedHashMap<>();
        policyList.put("type", "string");
        policyList.put(
                "description",
                "动网操作的授权策略列表，每条包含：业务场景、处置类型、操作名称、有效期");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("授权策略的操作类型", operationType);
        properties.put("动网操作的授权策略列表", policyList);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        return schema;
    }

    /** Add-authorization data (spec case 7.5): whitelist the tunnel-tuning recovery action. */
    public static Map<String, Object> addAuthorizationData() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("授权策略的操作类型", "新增授权策略");
        data.put(
                "动网操作的授权策略列表",
                "业务投诉诊断/业务抢通/隧道调优/2026-06-01~2030-06-18");
        return data;
    }

    // ------------------------------------------------------------------
    // Notification-T (spec case 7.8: subscribe service-recovery events)
    // ------------------------------------------------------------------

    /** Schema mirroring the SDK's Notification-T service-recovery slot schema. */
    public static Map<String, Object> serviceRecoverySchema() {
        Map<String, Object> condition = new LinkedHashMap<>();
        condition.put("type", "string");
        condition.put("description", "订阅条件，例如子网名称约束");
        Map<String, Object> format = new LinkedHashMap<>();
        format.put("type", "string");
        format.put(
                "description",
                "上报通知数据格式：业务抢通事件的数据结构描述（执行状态/流水号/端口/授权/方案等字段）");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("订阅条件", condition);
        properties.put("上报通知数据格式", format);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        return schema;
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
