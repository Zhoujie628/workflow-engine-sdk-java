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

package dev.openan.workflow.engine.examples.extension;

import dev.openan.workflow.engine.client.ExtensionSender;

import org.a2aproject.sdk.spec.AgentCard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.concurrent.TimeUnit;

/**
 * Pre-positions Authorization-T and Notification-T to downstream agents.
 *
 * <p>Single responsibility: send the two pre-positioning control messages (one-shot whitelist
 * policy + long-lived result subscription) to each non-workbench agent. The workbench extension
 * lifecycle calls this once; individual workflow tasks do not own or repeat these operations.
 */
public class ExtensionPrePositioner {

    private static final Logger log = LoggerFactory.getLogger(ExtensionPrePositioner.class);

    private final String authInput;
    private final String notifInput;

    public ExtensionPrePositioner() {
        // Authorization-T structured prompt, mirroring spec case 7.5 (add authorization).
        this.authInput =
                "## 授权策略的操作类型\n新增授权策略\n\n"
                        + "## 授权策略的操作描述\n请根据<授权策略的操作类型>和<动网操作的授权策略列表>完成相应的授权操作，"
                        + "按照<预期输出>中定义的结构返回授权策略的操作执行结果。\n\n"
                        + "## 动网操作的授权策略列表\n"
                        + "  - 动网操作的授权策略1\n"
                        + "    - 动网操作的业务场景：业务投诉诊断\n"
                        + "    - 动网操作的处置类型：业务抢通\n"
                        + "    - 动网操作名称：隧道调优\n"
                        + "    - 有效期：2026-06-01T12:00:00Z ~ 2030-06-18T12:00:00Z\n\n"
                        + "## 预期输出\n"
                        + "1. 授权操作执行结果，取值范围：成功、失败、部分成功；\n"
                        + "2. 授权操作执行成功时，返回执行成功的<动网操作的授权策略列表>；\n"
                        + "3. 授权操作执行失败或部分成功时，返回失败列表，包含授权策略和失败原因；";
        // Notification-T structured prompt, mirroring spec case 7.8 (subscribe).
        this.notifInput =
                "## 订阅描述\n请根据以下 <通知主题>、<订阅条件>、<上报通知数据格式> 及 <预期输出> 信息，"
                        + "完成网络侧业务抢通事件的订阅与上报任务。\n\n"
                        + "## 通知主题\n业务抢通事件\n\n"
                        + "## 订阅条件\n\n"
                        + "## 上报通知数据格式\n### 业务抢通事件\n"
                        + "1. 业务抢通方案执行状态，取值范围：未启动、已结束；举例：已结束（必选）\n"
                        + "2. 投诉诊断任务流水号（必选）\n"
                        + "3. OSS侧事件流水号（必选）\n"
                        + "4. 接入端口名称（必选）\n"
                        + "5. 是否已授权OMC自动抢通，取值范围：是、否；举例：是（必选）\n"
                        + "6. 业务抢通方案名称（必选）\n"
                        + "7. 业务抢通方案详情（必选）\n"
                        + "8. 业务抢通方案执行结束时间（可选）\n"
                        + "9. 业务抢通方案执行结果，取值范围：成功、失败（可选）\n"
                        + "10. 业务抢通方案执行失败原因（可选）\n\n"
                        + "## 预期输出\n"
                        + "1. 订阅结果，取值范围：成功、失败\n"
                        + "2. 订阅失败原因（可选）\n"
                        + "3. 订阅成功后，按照<上报通知数据格式>上报消息";
    }

    /**
     * Pre-position Authorization-T + Notification-T to every non-workbench agent.
     *
     * <p>When {@code notificationCallback} is non-null, subsequent events pushed by agents
     * through the Notification-T SSE stream (e.g. recovery results) are forwarded to the
     * callback. The callback receives a Map with keys: agent, text, metadata, state.
     */
    public void prePosition(
            ExtensionSender sender,
            List<AgentCard> agentCards,
            Consumer<Map<String, Object>> notificationCallback) {
        long allStarted = System.nanoTime();
        int targetCount = 0;
        for (AgentCard card : agentCards) {
            if (!card.name().contains("Workbench")) {
                targetCount++;
            }
        }
        log.info(
                "[PrePosition] START targetAgents={}, notificationCallback={}",
                targetCount,
                notificationCallback != null);
        for (AgentCard card : agentCards) {
            String name = card.name();
            if (name.contains("Workbench")) {
                continue;
            }
            long agentStarted = System.nanoTime();
            try {
                long operationStarted = System.nanoTime();
               log.info("[PrePosition] SEND extension=Authorization-T, agent={}", name);
               var authResult =
                        sender.sendAuthorization(name, "新增动网操作授权", authInput).join();
                log.info(
                        "[PrePosition] ACK extension=Authorization-T, agent={}, state={}, "
                                + "responseChars={}, elapsedMs={}",
                        name,
                        authResult.getTaskState(),
                        authResult.getText() != null ? authResult.getText().length() : 0,
                        elapsedMillis(operationStarted));

                operationStarted = System.nanoTime();
                log.info("[PrePosition] SEND extension=Notification-T, agent={}", name);
                var notificationResult =
                        sender.sendNotification(
                                       name,
                                        "订阅业务抢通事件",
                                       notifInput,
                                       notificationCallback)
                                .join();
                log.info(
                        "[PrePosition] ACK extension=Notification-T, agent={}, state={}, "
                                + "responseChars={}, elapsedMs={}",
                        name,
                        notificationResult.getTaskState(),
                        notificationResult.getText() != null
                                ? notificationResult.getText().length()
                                : 0,
                        elapsedMillis(operationStarted));
                log.info(
                        "[PrePosition] AGENT_DONE agent={}, elapsedMs={}",
                        name,
                        elapsedMillis(agentStarted));
            } catch (RuntimeException e) {
                log.error(
                        "[PrePosition] AGENT_FAILED agent={}, elapsedMs={}, errorType={}, message={}",
                        name,
                        elapsedMillis(agentStarted),
                        e.getClass().getSimpleName(),
                        e.getMessage(),
                        e);
                throw e;
            }
        }
        log.info(
                "[PrePosition] DONE targetAgents={}, elapsedMs={}",
                targetCount,
                elapsedMillis(allStarted));
    }

    /** Pre-position without Notification-T event callback. */
    public void prePosition(ExtensionSender sender, List<AgentCard> agentCards) {
        prePosition(sender, agentCards, null);
    }

    private static long elapsedMillis(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }
}
