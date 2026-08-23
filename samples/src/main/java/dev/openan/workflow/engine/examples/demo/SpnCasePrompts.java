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
 * Structured prompt fixtures for the SPN private-line complaint scenario (spec §6 metadata-key
 * conventions). Shared by the demo entry points and the protocol verification cases so the
 * Task-T payload is defined exactly once.
 */
public final class SpnCasePrompts {

    /** Standard Task-T extension URI. */
    public static final String TASK_T_URI = A2ATExtension.TASK_T.uri();

    /** Northbound user text accompanying the Task-T prompt (spec case 7.1). */
    public static final String TASK_TEXT = "创建专线业务投诉诊断任务";

    private SpnCasePrompts() {}

    /**
     * Task-T structured prompt for the private-line complaint diagnosis (spec case 7.1): a
     * well-formed complaint with a known faulty port in City1.
     */
    public static String privateLineComplaintTask() {
        return taskT(
                "P781-珠江新城-PTN7900-23-TPA1EG24-17",
                "\"专线质差\"",
                "\"event-id-20260511-09013\"");
    }

    /**
     * Task-T prompt variant with a blank task object and complaint category (spec case 7.3):
     * triggers server-side blank-slot negotiation.
     */
    public static String privateLineComplaintTaskBlankObject() {
        return taskT("", "", "\"event-id-20260511-09013\"");
    }

    /**
     * Task-T prompt variant referencing an unknown port (spec case 7.4): triggers the
     * semantic-error negotiation path.
     */
    public static String privateLineComplaintTaskUnknownPort() {
        return taskT(
                "P781-珠江新城-PTN7900-23-TPA1EG24-18",
                "\"专线质差\"",
                "\"event-id-20260511-09013\"");
    }

    /** Task-T metadata map carrying the given prompt under the standard extension key. */
    public static Map<String, Object> taskTMetadata(String taskPrompt) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(TASK_T_URI, taskPrompt);
        return metadata;
    }

    private static String taskT(String portName, String complaintCategory, String ossEventId) {
        return "## 任务类型(Task Type)\n传输专线业务投诉诊断\n\n"
                + "## 任务描述(Task Description)\n"
                + "基于<任务对象>、<任务上下文> 进行投诉场景的网络侧故障根因诊断, "
                + "达成<任务目标>中定义的投诉诊断目标，按照<预期输出>中定义的结构返回任务处理结果。\n\n"
                + "## 任务目标(Task Target)\n对网络侧故障进行诊断，返回故障根因和修复建议等诊断结果信息。\n\n"
                + "## 任务对象(Task Object)\n"
                + "接入端口名称："
                + portName
                + "\n\n"
                + "## 任务上下文(Task Context)\n"
                + "1. 投诉分类："
                + complaintCategory
                + "\n"
                + "2. 问题发生时间：\"2026-05-11T08:21:46Z\"\n"
                + "3. OSS侧事件流水号："
                + ossEventId
                + "\n"
                + "4. 投诉详情：\"从5月11号早上8点半开始，深圳访问广州的响应延迟从平均12ms骤升至320ms，"
                + "访问广州机房的核心交易系统非常慢。\"\n\n"
                + "## 预期输出(Expected Output)\n"
                + "1. 诊断结果；参数的取值范围包括：成功、失败；(必选)\n"
                + "2. 诊断结果详情；(必选)\n"
                + "3. 修复建议；(可选)\n"
                + "4. 故障根因列表，每个故障根因包含故障根因名称、详细描述、修复建议、故障根因点位置等信息；(可选)";
    }
}
