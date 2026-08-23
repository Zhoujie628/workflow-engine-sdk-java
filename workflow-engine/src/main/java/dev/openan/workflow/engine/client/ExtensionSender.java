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
 * One-shot pre-positioning facade over a shared {@link A2ATransport}.
 *
 * <p>Single responsibility: send Authorization-T, establish Notification-T subscriptions, or send
 * another pre-positioning extension before a workflow starts. It bypasses Task-T prompt
 * generation and the Negotiation-T auto-loop. The returned future carries the first response;
 * later Notification-T events flow through the subscription callback.
 *
 * <p>Kept separate from {@link WorkflowEngineClient} so a caller that only wants to pre-position is
 * not forced to hold a workflow-machinery facade.
 */
public interface ExtensionSender {

    /**
     * Send a one-shot extension message for pre-positioning.
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

    /** Convenience for Authorization-T pre-positioning. */
    default CompletableFuture<SendMessageResult> sendAuthorization(
            String agentName, String instruction, String naturalLanguageInput) {
        return sendExtensionMessage(
                agentName, instruction, naturalLanguageInput, A2ATExtension.AUTHORIZATION_T);
    }

    /**
     * Structured-data send: renders the extension prompt deterministically from typed data via
     * the SDK's fromData pipeline (no LLM), then sends as a one-shot pre-positioning message.
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
    default CompletableFuture<SendMessageResult> sendExtensionMessageFromData(
            String agentName,
            String instruction,
            Map<String, Object> data,
            Map<String, Object> schema,
            A2ATExtension extension) {
        throw new UnsupportedOperationException("sendExtensionMessageFromData not implemented");
    }

    /**
     * Establish a Notification-T subscription.
     *
     * <p>The returned future completes on the first acknowledgement or event. Subsequent events
     * pushed by the agent must be forwarded to {@code eventCallback}; implementations cannot
     * silently discard it.
     */
    CompletableFuture<SendMessageResult> sendNotification(
            String agentName,
            String instruction,
            String naturalLanguageInput,
            Consumer<Map<String, Object>> eventCallback);

    /** Convenience for Notification-T pre-positioning (no event callback). */
    default CompletableFuture<SendMessageResult> sendNotification(
            String agentName, String instruction, String naturalLanguageInput) {
        return sendNotification(agentName, instruction, naturalLanguageInput, null);
    }
}
