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

import dev.openan.workflow.engine.control.ControlPoint;
import dev.openan.workflow.engine.control.EventCallback;
import dev.openan.workflow.engine.model.SendMessageResult;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Workflow-execution send facade over a shared {@link A2ATransport}.
 *
 * <p>Single responsibility: the workflow execution send path. This facade owns Task-T prompt
 * generation, the Negotiation-T auto-loop, the global {@link EventCallback}, and the ControlPoint
 * wiring. All wire-level work (client runtime, auth, SSE event extraction) delegates to the
 * transport.
 *
 * <p>One-shot pre-positioning (Authorization-T / Notification-T) is a separate concern and lives on
 * {@link ExtensionSender}; callers that only need pre-positioning hold that lighter facade over the
 * same transport.
 *
 * <p>The single message type on this facade:
 *
 * <ul>
 *   <li>{@link #sendMessage} - streaming send used during workflow execution (invoked from {@link
 *       ControlPoint#onTask}). Runs through Task-T prompt generation, the Negotiation-T auto-loop,
 *       and the global {@link EventCallback}.
 * </ul>
 */
public interface WorkflowEngineClient {

    /**
     * Send a message to an agent via SSE streaming. Used during workflow execution. The engine
     * handles Task-T prompt generation, Negotiation-T auto-loop, auth, and extension header
     * injection automatically.
     *
     * @param agentName target agent name (must match AgentCard.name)
     * @param message full assembled message text
     * @param contextId optional context ID (null = auto-generated)
     * @param metadata optional preset metadata
     * @return future completing with response text, task, metadata, task state
     */
    CompletableFuture<SendMessageResult> sendMessage(
            String agentName, String message, String contextId, Map<String, Object> metadata);

    /** Convenience: no context ID, no preset metadata. */
    default CompletableFuture<SendMessageResult> sendMessage(String agentName, String message) {
        return sendMessage(agentName, message, null, null);
    }

    /**
     * Structured-data send: renders the Task-T prompt deterministically from typed data via the
     * SDK's fromData pipeline (no scenario recognition, no LLM), then runs the normal send path
     * (Negotiation-T auto-loop, auth, extension headers).
     *
     * <p>Callers holding structured business data should prefer this over {@link #sendMessage}:
     * the SDK renders the prompt from the data and schema instead of the caller hand-writing the
     * rendered prompt text.
     *
     * @param agentName target agent name (must match AgentCard.name)
     * @param message short accompanying message text (parts text of the A2A message)
     * @param data structured task input (string-to-object map)
     * @param schema JSON schema describing the meaning of each data field
     * @param templateUri template the data renders through (e.g. {@code
     *     StandardTemplates.PRIVATE_LINE_COMPLAINT}); null uses the SDK default
     * @return future completing with response text, task, metadata, task state
     */
    default CompletableFuture<SendMessageResult> sendMessageFromData(
            String agentName,
            String message,
            Map<String, Object> data,
            Map<String, Object> schema,
            net.openan.a2at.sdk.core.model.TemplateUri templateUri) {
        return sendMessageFromData(agentName, message, data, schema, templateUri, null);
    }

    /**
     * Structured-data send with additional preset metadata (e.g. the empty Negotiation-T key to
     * activate the extension alongside the task).
     *
     * @param extraMetadata additional preset metadata merged into the message; engine-internal
     *     keys (a2at.taskData/taskSchema/taskTemplate) are added on top
     */
    default CompletableFuture<SendMessageResult> sendMessageFromData(
            String agentName,
            String message,
            Map<String, Object> data,
            Map<String, Object> schema,
            net.openan.a2at.sdk.core.model.TemplateUri templateUri,
            Map<String, Object> extraMetadata) {
        Map<String, Object> metadata =
                extraMetadata != null
                        ? new java.util.LinkedHashMap<>(extraMetadata)
                        : new java.util.LinkedHashMap<>();
        metadata.put(A2ATExtension.TASK_DATA_META_KEY, data);
        metadata.put(A2ATExtension.TASK_SCHEMA_META_KEY, schema);
        if (templateUri != null) {
            metadata.put(A2ATExtension.TASK_TEMPLATE_META_KEY, templateUri.uri());
        }
        return sendMessage(agentName, message, null, metadata);
    }

    void setControlPoint(ControlPoint controlPoint);

    void setEventCallback(EventCallback callback);

    /**
     * Lists every A2A-T prompt template of the configured language across all extensions
     * (Task-T / Notification-T / Authorization-T / Negotiation-T). Never throws.
     *
     * @return template list sorted by URI; empty when the A2A-T SDK is not configured
     */
    default java.util.List<net.openan.a2at.sdk.core.model.PromptTemplate> getPrompts() {
        return java.util.List.of();
    }

    /**
     * Lists every negotiation template of the configured language (propose / accept-reject for
     * information, target, feasibility, plus the common abort template). Never throws.
     *
     * @return negotiation template list; empty when the A2A-T SDK is not configured
     */
    default java.util.List<net.openan.a2at.sdk.core.model.PromptTemplate> getNegotiationPrompts() {
        return java.util.List.of();
    }

    /**
     * Loads one template by URI regardless of extension. Never throws.
     *
     * @param templateUri template URI such as {@code StandardTemplates.PRIVATE_LINE_COMPLAINT}
     * @return the addressed template, or empty when missing / SDK not configured
     */
    default java.util.Optional<net.openan.a2at.sdk.core.model.PromptTemplate> getPrompt(
            net.openan.a2at.sdk.core.model.TemplateUri templateUri) {
        return java.util.Optional.empty();
    }


    /** Query a task by ID (A2A GET tasks/{id}). */
    default CompletableFuture<SendMessageResult> getTask(String agentName, String taskId) {
        throw new UnsupportedOperationException("getTask not implemented");
    }

    /** Cancel a task by ID (A2A POST tasks/{id}:cancel). */
    default CompletableFuture<SendMessageResult> cancelTask(String agentName, String taskId) {
        throw new UnsupportedOperationException("cancelTask not implemented");
    }

    /** Subscribe to a task stream (A2A POST tasks/{id}:subscribe). */
    default CompletableFuture<SendMessageResult> subscribeToTask(
            String agentName, String taskId,
            java.util.function.Consumer<java.util.Map<String, Object>> eventCallback) {
        throw new UnsupportedOperationException("subscribeToTask not implemented");
    }

    void close();
}
