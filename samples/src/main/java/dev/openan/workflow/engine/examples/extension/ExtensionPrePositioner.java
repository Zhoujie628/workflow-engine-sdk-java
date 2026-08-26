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
import dev.openan.workflow.engine.client.NotificationSubscription;
import dev.openan.workflow.engine.examples.demo.SpnCasePrompts;
import dev.openan.workflow.engine.model.SendMessageResult;

import org.a2aproject.sdk.spec.AgentCard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.concurrent.TimeUnit;
import net.openan.a2at.sdk.core.model.StandardTemplates;

/**
 * Initializes independent Authorization-T and Notification-T operations for downstream agents.
 *
 * <p>Single responsibility: send a one-shot whitelist operation and open a long-lived result
 * subscription for each non-workbench agent on separate channels. The historical class name is
 * retained for sample source compatibility; individual workflow tasks do not own these operations.
 */
public class ExtensionPrePositioner {

    private static final Logger log = LoggerFactory.getLogger(ExtensionPrePositioner.class);
    private static final int AUTHORIZATION_VALIDATION_ATTEMPTS = 3;

    private final Map<String, Object> authData;
    private final Map<String, Object> authSchema;
    private final Map<String, Object> notifData;
    private final Map<String, Object> notifSchema;

    public ExtensionPrePositioner() {
        // Structured-data track: the raw policy / subscription fields; the SDK renders the
        // Authorization-T and Notification-T prompts through the SDK schema-aware pipelines
        // (spec cases 7.5/7.8; slot extraction may use the SDK-configured LLM).
        this.authData = SpnCasePrompts.addAuthorizationData();
        this.authSchema = SdkSlotSchemaLoader.loadConfigured(
                StandardTemplates.AUTHORIZATION_POLICY_MANAGEMENT);
        this.notifData = SpnCasePrompts.subscribeServiceRecoveryData();
        this.notifSchema = SdkSlotSchemaLoader.loadConfigured(StandardTemplates.SERVICE_RECOVERY);
    }

    /**
     * Pre-position Authorization-T + Notification-T to every non-workbench agent.
     *
     * <p>When {@code notificationCallback} is non-null, subsequent events pushed by agents
     * through the Notification-T SSE stream (e.g. recovery results) are forwarded to the
     * callback. The callback receives a Map with keys: agent, text, metadata, state.
     */
    public List<NotificationSubscription> prePosition(
            ExtensionSender authorizationSender,
            ExtensionSender notificationSender,
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
        java.util.ArrayList<NotificationSubscription> subscriptions = new java.util.ArrayList<>();
        for (AgentCard card : agentCards) {
            String name = card.name();
            if (name.contains("Workbench")) {
                continue;
            }
            long agentStarted = System.nanoTime();
            try {
                long operationStarted = System.nanoTime();
                log.info("[PrePosition] SEND extension=Authorization-T, agent={}", name);
                SendMessageResult authResult = sendAuthorization(authorizationSender, name);
                log.info(
                        "[PrePosition] ACK extension=Authorization-T, agent={}, state={}, "
                                + "responseChars={}, elapsedMs={}",
                        name,
                        authResult.getTaskState(),
                        authResult.getText() != null ? authResult.getText().length() : 0,
                        elapsedMillis(operationStarted));
                requireState(
                        authResult,
                        "Authorization-T",
                        name,
                        "TASK_STATE_COMPLETED");

                operationStarted = System.nanoTime();
                log.info("[PrePosition] SEND extension=Notification-T, agent={}", name);
                NotificationSubscription subscription =
                        notificationSender.openNotificationFromData(
                                        name,
                                        "订阅业务抢通事件",
                                        notifData,
                                        notifSchema,
                                        net.openan.a2at.sdk.core.model.StandardTemplates
                                                .SERVICE_RECOVERY,
                                        notificationCallback)
                                .join();
                subscriptions.add(subscription);
                var notificationResult = subscription.acknowledgement().join();
                requireState(
                        notificationResult,
                        "Notification-T",
                        name,
                        "TASK_STATE_WORKING",
                        "TASK_STATE_COMPLETED");
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
        return List.copyOf(subscriptions);
    }

    private SendMessageResult sendAuthorization(ExtensionSender sender, String agentName) {
        SendMessageResult result = null;
        for (int attempt = 1; attempt <= AUTHORIZATION_VALIDATION_ATTEMPTS; attempt++) {
            result = sender.sendExtensionMessageFromData(
                            agentName,
                            "新增动网操作授权",
                            authData,
                            authSchema,
                            net.openan.a2at.sdk.core.model.StandardTemplates
                                    .AUTHORIZATION_POLICY_MANAGEMENT,
                            dev.openan.workflow.engine.client.A2ATExtension.AUTHORIZATION_T)
                    .join();
            boolean retryableValidationFailure =
                    "TASK_STATE_FAILED".equals(result.getTaskState())
                            && "Authorization-T validation failed".equals(result.getText());
            if (!retryableValidationFailure || attempt == AUTHORIZATION_VALIDATION_ATTEMPTS) {
                return result;
            }
            log.warn(
                    "[PrePosition] RETRY extension=Authorization-T, agent={}, attempt={}/{}, reason=validator_mismatch",
                    agentName,
                    attempt + 1,
                    AUTHORIZATION_VALIDATION_ATTEMPTS);
        }
        return result;
    }

    /** Pre-position through explicitly isolated Authorization-T and Notification-T senders. */
    public List<NotificationSubscription> prePosition(
            ExtensionSender authorizationSender,
            ExtensionSender notificationSender,
            List<AgentCard> agentCards) {
        return prePosition(authorizationSender, notificationSender, agentCards, null);
    }

    private static long elapsedMillis(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }

    private static void requireState(
            dev.openan.workflow.engine.model.SendMessageResult result,
            String extension,
            String agent,
            String... allowedStates) {
        String state = result != null ? result.getTaskState() : null;
        boolean accepted =
                state != null
                        && java.util.Arrays.stream(allowedStates)
                                .anyMatch(state::contains);
        if (!accepted) {
            throw new IllegalStateException(
                    extension
                            + " independent protocol operation failed for '"
                            + agent
                            + "': state="
                            + state
                            + ", response="
                            + (result != null ? result.getText() : "null"));
        }
    }
}
