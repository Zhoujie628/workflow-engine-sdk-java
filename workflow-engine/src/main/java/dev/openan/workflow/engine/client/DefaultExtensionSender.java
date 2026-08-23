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

import net.openan.a2at.sdk.client.A2ATClient;
import net.openan.a2at.sdk.core.model.MetadataContent;
import net.openan.a2at.sdk.core.model.StandardTemplates;
import net.openan.a2at.sdk.core.model.TemplateUri;

import org.a2aproject.sdk.spec.AgentCard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.client.MessageEvent;
import org.a2aproject.sdk.client.TaskUpdateEvent;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.TaskArtifactUpdateEvent;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;
import org.a2aproject.sdk.spec.TextPart;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Default {@link ExtensionSender} built on a shared {@link A2ATransport}.
 *
 * <p>Owns extension prompt-generation dispatch (Task-T via the A2A-T SDK; Negotiation-T /
 * Authorization-T / Notification-T reserved for future SDK support). All wire-level work delegates
 * to the transport.
 */
public record DefaultExtensionSender(A2ATransport transport)
        implements ExtensionSender, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DefaultExtensionSender.class);

    @Override
    public CompletableFuture<SendMessageResult> sendExtensionMessage(
            String agentName,
            String instruction,
            String naturalLanguageInput,
            A2ATExtension extension) {
        AgentCard agentCard = transport.getCard(agentName);
        if (agentCard == null) {
            log.error("[ExtensionSender] Agent not found: {}", agentName);
            return CompletableFuture.failedFuture(
                    new RuntimeException("Agent not found: " + agentName));
        }
        return CompletableFuture.supplyAsync(
                        () -> generateExtensionPrompt(extension, naturalLanguageInput))
                .thenCompose(
                        mc -> {
                            String value;
                            Map<String, Object> metadata = new HashMap<>();
                            if (mc != null && mc.promptText() != null && !mc.promptText().isEmpty()) {
                                value = mc.promptText();
                                metadata.putAll(mc.buildMetadataContent());
                            } else {
                                value = naturalLanguageInput;
                                metadata.put(extension.uri(), value);
                                log.info(
                                        "[ExtensionSender] SDK prompt generation unavailable for {} ({}), using input as metadata",
                                        agentName,
                                        extension.displayName());
                            }
                            log.info(
                                    "[ExtensionSender] sendExtensionMessage to {}: extension={}, metadataValue={} chars",
                                    agentName,
                                    extension.displayName(),
                                    value.length());
                            if (extension == A2ATExtension.NOTIFICATION_T) {
                                return transport.sendNotificationStream(
                                        agentCard,
                                        agentName,
                                        instruction,
                                        transport.getContextId(),
                                        metadata,
                                        null);
                            }
                            CompletableFuture<SendMessageResult> send =
                                    transport
                                    .send(
                                            agentCard,
                                            agentName,
                                            instruction,
                                            transport.getContextId(),
                                            metadata,
                                            null)
                                    .thenApply(
                                            result -> {
                                                log.info(
                                                        "[ExtensionSender] Extension response from {}: state={}",
                                                        agentName,
                                                        result.getTaskState());
                                                return result;
                                            });
                            return send.whenComplete(
                                    (ignored, error) ->
                                            transport.closeConversation(
                                                    agentCard, transport.getContextId()));
                        });
    }

    @Override
    public CompletableFuture<SendMessageResult> sendNotification(
            String agentName,
            String instruction,
            String naturalLanguageInput,
            Consumer<Map<String, Object>> eventCallback) {
        AgentCard agentCard = transport.getCard(agentName);
        if (agentCard == null) {
            log.error("[ExtensionSender] Agent not found: {}", agentName);
            return CompletableFuture.failedFuture(
                    new RuntimeException("Agent not found: " + agentName));
        }
        return CompletableFuture.supplyAsync(
                        () ->
                                generateExtensionPrompt(
                                        A2ATExtension.NOTIFICATION_T, naturalLanguageInput))
                .thenCompose(
                        mc -> {
                            String value;
                            Map<String, Object> metadata = new HashMap<>();
                            if (mc != null && mc.promptText() != null && !mc.promptText().isEmpty()) {
                                value = mc.promptText();
                                metadata.putAll(mc.buildMetadataContent());
                            } else {
                                value = naturalLanguageInput;
                                metadata.put(A2ATExtension.NOTIFICATION_T.uri(), value);
                            }
                            Consumer<ClientEvent> eventSink =
                                    eventCallback != null
                                            ? event ->
                                                    forwardNotificationEvent(
                                                            event, agentName, eventCallback)
                                            : null;
                            log.info(
                                    "[ExtensionSender] sendNotification to {}: metadataValue={} chars, callback={}",
                                    agentName,
                                    value.length(),
                                    eventCallback != null);
                            return transport.sendNotificationStream(
                                    agentCard,
                                    agentName,
                                    instruction,
                                    transport.getContextId(),
                                    metadata,
                                    eventSink);
                        });
    }

    private void forwardNotificationEvent(
            ClientEvent event, String agentName, Consumer<Map<String, Object>> callback) {
        Map<String, Object> data = new HashMap<>();
        data.put("agent", agentName);
        if (event instanceof TaskUpdateEvent tue) {
            if (tue.getUpdateEvent() instanceof TaskStatusUpdateEvent sue) {
                data.put("state", sue.status().state().name());
                data.put("is_final", sue.isFinal());
                StringBuilder text = new StringBuilder();
                A2ATransport.extractTextFromMessage(sue.status().message(), text);
                if (!text.isEmpty()) data.put("text", text.toString());
                if (sue.metadata() != null && !sue.metadata().isEmpty())
                    data.put("metadata", sue.metadata());
            } else if (tue.getUpdateEvent() instanceof TaskArtifactUpdateEvent ae) {
                StringBuilder text = new StringBuilder();
                for (Part<?> part : ae.artifact().parts()) {
                    if (part instanceof TextPart tp) text.append(tp.text());
                }
                data.put("artifact_name", ae.artifact().name());
                data.put("append", ae.append());
                if (!text.isEmpty()) data.put("text", text.toString());
                if (ae.metadata() != null && !ae.metadata().isEmpty())
                    data.put("metadata", ae.metadata());
            }
        } else if (event instanceof MessageEvent me) {
            Message msg = me.getMessage();
            StringBuilder text = new StringBuilder();
            A2ATransport.extractTextFromMessage(msg, text);
            data.put("role", msg.role().name());
            if (!text.isEmpty()) data.put("text", text.toString());
            if (msg.metadata() != null && !msg.metadata().isEmpty())
                data.put("metadata", msg.metadata());
        }
        if (!data.isEmpty()) {
            log.info("[ExtensionSender] Notification-T callback for {}: {} keys", agentName, data.keySet());
            callback.accept(data);
        }
    }

    // ------------------------------------------------------------------
    // Extension prompt generation dispatch
    // ------------------------------------------------------------------

    /**
     * Generates a structured extension prompt via the A2A-T SDK.
     *
     * <p>The template is addressed by a {@link TemplateUri}; the message language comes from the
     * SDK's own {@code A2AT_LANGUAGE} configuration, no longer from a caller-supplied language
     * string.
     *
     * @return {@link MetadataContent} with prompt text and template URI, or null if SDK unavailable
     */
    MetadataContent generateExtensionPrompt(A2ATExtension extension, String naturalLanguageInput) {
        A2ATClient a2atClient = transport.getA2atClient();
        if (a2atClient == null) {
            return null;
        }
        try {
            return switch (extension) {
                case TASK_T -> a2atClient.generateTaskPromptFromText(
                        naturalLanguageInput, StandardTemplates.PRIVATE_LINE_COMPLAINT);
                case AUTHORIZATION_T -> a2atClient.generateAuthPromptFromText(
                        naturalLanguageInput, StandardTemplates.AUTHORIZATION_POLICY_MANAGEMENT);
                case NOTIFICATION_T -> a2atClient.generateNotificationPromptFromText(
                        naturalLanguageInput, StandardTemplates.SUBSCRIBE_INCIDENT);
                case NEGOTIATION_T -> null; // Negotiation uses a dedicated flow in NegotiationTHandler
            };
        } catch (Exception e) {
            log.warn(
                    "[ExtensionSender] SDK {} prompt generation error: {}",
                    extension.displayName(),
                    e.getMessage());
            return null;
        }
    }

    @Override
    public void close() {
        // Transport is owned by the caller; do not close it here.
    }
}
