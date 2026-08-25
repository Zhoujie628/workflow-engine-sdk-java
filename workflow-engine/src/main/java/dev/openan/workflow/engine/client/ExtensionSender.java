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

package dev.openan.workflow.engine.client;

import dev.openan.workflow.engine.model.SendMessageResult;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Facade for protocol operations that are independent of workflow DAG execution.
 *
 * <p>Single responsibility: send Authorization-T operations and establish Notification-T
 * subscriptions. These operations may happen before, during, or after a workflow according to the
 * workbench business lifecycle. They bypass Task-T prompt generation and the Negotiation-T
 * auto-loop. The returned future carries the first response; later Notification-T events flow
 * through the subscription callback.
 *
 * <p>Kept separate from {@link WorkflowEngineClient} so protocol channels and lifecycles cannot be
 * accidentally coupled to a workflow task.
 */
public interface ExtensionSender {

    /**
     * Send a one-shot independent extension message.
     *
     * @param agentName target agent name
     * @param instruction short instruction text (becomes message parts)
     * @param naturalLanguageInput input for SDK prompt generation
     * @param extension extension type (never hardcode URIs)
     */
    CompletableFuture<SendMessageResult> sendExtensionMessage(
            String agentName,
            String instruction,
            String naturalLanguageInput,
            A2ATExtension extension);

    /** Natural-language compatibility convenience for a one-shot Authorization-T operation. */
    default CompletableFuture<SendMessageResult> sendAuthorization(
            String agentName, String instruction, String naturalLanguageInput) {
        return sendExtensionMessage(
                agentName, instruction, naturalLanguageInput, A2ATExtension.AUTHORIZATION_T);
    }

    /**
     * Structured-data send: renders the extension prompt from typed data via the SDK's
     * schema-aware fromData pipeline. Scenario recognition is bypassed, but slot mapping may
     * invoke the configured LLM. Rendering failures are never sent as raw extension metadata.
     *
     * <p>Callers holding structured business data (e.g. an authorization policy as fields, not
     * prose) should prefer this over the natural-language variants.
     *
     * @param agentName target agent name
     * @param instruction short instruction text (becomes message parts)
     * @param data structured extension input (string-to-object map)
     * @param schema JSON schema describing the meaning of each data field
     * @param extension extension type; only AUTHORIZATION_T and NOTIFICATION_T support
     *     fromData rendering here
     * @return future completing with the first response
     */
    CompletableFuture<SendMessageResult> sendExtensionMessageFromData(
            String agentName,
            String instruction,
            Map<String, Object> data,
            Map<String, Object> schema,
            A2ATExtension extension);

    /**
     * Structured-data Notification-T subscription: renders the service-recovery subscription
     * prompt from typed data through the SDK schema-aware pipeline, then opens the long-lived
     * stream. Slot mapping may invoke the configured LLM.
     * Subsequent events pushed by the agent flow to {@code eventCallback}.
     */
    CompletableFuture<SendMessageResult> sendNotificationFromData(
            String agentName,
            String instruction,
            Map<String, Object> data,
            Map<String, Object> schema,
            Consumer<Map<String, Object>> eventCallback);

    /**
     * Opens a structured-data Notification-T subscription and exposes its full stream lifecycle.
     * Callers should retain and close the returned handle after the expected notification arrives.
     */
    CompletableFuture<NotificationSubscription> openNotificationFromData(
            String agentName,
            String instruction,
            Map<String, Object> data,
            Map<String, Object> schema,
            Consumer<Map<String, Object>> eventCallback);

    /**
     * Establish a Notification-T subscription.
     *
     * <p>The returned future completes on the first status-bearing acknowledgement (normally
     * {@code TASK_STATE_WORKING}), not on an artifact-only event. Subsequent events pushed by the
     * agent must be forwarded to {@code eventCallback}; implementations cannot silently discard it.
     */
    CompletableFuture<SendMessageResult> sendNotification(
            String agentName,
            String instruction,
            String naturalLanguageInput,
            Consumer<Map<String, Object>> eventCallback);

    /** Opens a Notification-T subscription and returns an explicit close/health handle. */
    CompletableFuture<NotificationSubscription> openNotification(
            String agentName,
            String instruction,
            String naturalLanguageInput,
            Consumer<Map<String, Object>> eventCallback);

    /** Natural-language compatibility convenience for a Notification-T subscription. */
    default CompletableFuture<SendMessageResult> sendNotification(
            String agentName, String instruction, String naturalLanguageInput) {
        return sendNotification(agentName, instruction, naturalLanguageInput, null);
    }
}
