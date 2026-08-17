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

package dev.openan.workflow.engine.examples.agents;

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
        this.authInput =
                "任务类型新增授权，操作名称业务抢通，"
                        + "操作类型光模块更换，"
                        + "操作对象SPN专线业务，溢权策略OMC自动执行，"
                        + "触发执行条件业务投诉诊断确认故障，"
                        + "预期输出返回是否成功。";
        this.notifInput =
                "通知主题为service-recovery-execution-result，"
                        + "订阅条件为业务抢通方案执行结果，"
                        + "上报通知数据格式为TextPart。";
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
                        sender.sendAuthorization(name, "下发授权放行策略", authInput).join();
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
                                        "订阅业务抢通结果通知",
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
