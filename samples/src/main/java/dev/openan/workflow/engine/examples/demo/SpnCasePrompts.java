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

    /** Task-T metadata map carrying the given prompt under the standard extension key. */
    public static Map<String, Object> taskTMetadata(String taskPrompt) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(TASK_T_URI, taskPrompt);
        return metadata;
    }
}
