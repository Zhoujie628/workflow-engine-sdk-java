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
 * Default {@link ExtensionSender} built on a caller-owned {@link A2ATransport}.
 *
 * <p>Uses the A2A-T SDK to render Authorization-T and Notification-T metadata. All wire-level work
 * delegates to the transport. The facade does not close that transport; the caller owns it.
 */
public record DefaultExtensionSender(A2ATransport transport)
        implements ExtensionSender, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DefaultExtensionSender.class);

    public DefaultExtensionSender {
        java.util.Objects.requireNonNull(transport, "transport");
    }

    @Override
    public CompletableFuture<SendMessageResult> sendExtensionMessage(
            String agentName,
            String instruction,
            String naturalLanguageInput,
            TemplateUri templateUri,
            A2ATExtension extension) {
        CompletableFuture<SendMessageResult> invalid =
                validateRequest(agentName, instruction, naturalLanguageInput, extension);
        if (invalid != null) return invalid;
        IllegalArgumentException templateError = validateTemplate(extension, templateUri);
        if (templateError != null) return CompletableFuture.failedFuture(templateError);
        AgentCard agentCard = transport.getCard(agentName);
        if (agentCard == null) {
            log.error("[ExtensionSender] Agent not found: {}", agentName);
            return CompletableFuture.failedFuture(
                    new RuntimeException("Agent not found: " + agentName));
        }
        if (!supports(agentCard, extension)) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException(
                            "Agent '" + agentName + "' does not declare " + extension.uri()));
        }
        return CompletableFuture.supplyAsync(
                        () -> generateExtensionPrompt(extension, naturalLanguageInput, templateUri))
                .thenCompose(
                        mc -> {
                            if (mc == null || mc.promptText() == null || mc.promptText().isEmpty()) {
                                return CompletableFuture.failedFuture(
                                        new IllegalStateException(
                                                "A2A-T SDK prompt generation failed for "
                                                        + extension.displayName()));
                            }
                            String value = mc.promptText();
                            Map<String, Object> metadata = new HashMap<>();
                            metadata.putAll(mc.buildMetadataContent());
                            log.info(
                                    "[ExtensionSender] sendExtensionMessage to {}: extension={}, metadataValue={} chars",
                                    agentName,
                                    extension.displayName(),
                                    value.length());
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
    public CompletableFuture<SendMessageResult> sendExtensionMessageFromData(
            String agentName,
            String instruction,
            Map<String, Object> data,
            Map<String, Object> schema,
            TemplateUri templateUri,
            A2ATExtension extension) {
        if (agentName == null || agentName.isBlank() || instruction == null || instruction.isBlank()) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("agentName and instruction must not be blank"));
        }
        if (data == null || schema == null || schema.isEmpty()) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("fromData requires non-null data and a non-empty schema"));
        }
        if (extension != A2ATExtension.AUTHORIZATION_T) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException(
                            "One-shot fromData rendering supports AUTHORIZATION_T only; "
                                    + "use openNotificationFromData for Notification-T"));
        }
        IllegalArgumentException templateError = validateTemplate(extension, templateUri);
        if (templateError != null) return CompletableFuture.failedFuture(templateError);
        AgentCard agentCard = transport.getCard(agentName);
        if (agentCard == null) {
            log.error("[ExtensionSender] Agent not found: {}", agentName);
            return CompletableFuture.failedFuture(
                    new RuntimeException("Agent not found: " + agentName));
        }
        if (!supports(agentCard, extension)) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException(
                            "Agent '" + agentName + "' does not declare " + extension.uri()));
        }
        return CompletableFuture.supplyAsync(
                        () -> generateExtensionPromptFromData(extension, data, schema, templateUri))
                .thenCompose(
                        mc -> {
                            if (mc == null || mc.promptText() == null || mc.promptText().isEmpty()) {
                                log.error(
                                        "[ExtensionSender] fromData rendering unavailable for {} ({}); refusing to send raw data",
                                        agentName,
                                        extension.displayName());
                                return CompletableFuture.failedFuture(
                                        new RuntimeException(
                                                "fromData rendering failed for " + extension));
                            }
                            Map<String, Object> metadata = new HashMap<>();
                            metadata.putAll(mc.buildMetadataContent());
                            log.info(
                                    "[ExtensionSender] sendExtensionMessageFromData to {}: extension={}, renderedChars={}",
                                    agentName,
                                    extension.displayName(),
                                    mc.promptText().length());
                            return transport
                                    .send(
                                            agentCard,
                                            agentName,
                                            instruction,
                                            transport.getContextId(),
                                            metadata,
                                            null)
                                    .whenComplete(
                                            (ignored, error) ->
                                                    transport.closeConversation(
                                                            agentCard, transport.getContextId()));
                        });
    }

    /** SDK schema-aware fromData rendering for Authorization-T / Notification-T. */
    private MetadataContent generateExtensionPromptFromData(
            A2ATExtension extension,
            Map<String, Object> data,
            Map<String, Object> schema,
            TemplateUri templateUri) {
        net.openan.a2at.sdk.client.A2ATClient a2atClient = transport.getA2atClient();
        if (a2atClient == null) {
            return null;
        }
        return switch (extension) {
            case AUTHORIZATION_T -> a2atClient.generateAuthPromptFromDataWithSchema(
                    data, schema, templateUri);
            case NOTIFICATION_T -> a2atClient.generateNotificationPromptFromDataWithSchema(
                    data, schema, templateUri);
            default -> throw new IllegalArgumentException(
                    "Unsupported schema-aware extension: " + extension);
        };
    }

    @Override
    public CompletableFuture<SendMessageResult> sendNotification(
            String agentName,
            String instruction,
            String naturalLanguageInput,
            TemplateUri templateUri,
            Consumer<Map<String, Object>> eventCallback) {
        return openNotification(
                        agentName,
                        instruction,
                        naturalLanguageInput,
                        templateUri,
                        eventCallback)
                .thenCompose(NotificationSubscription::acknowledgement);
    }

    @Override
    public CompletableFuture<NotificationSubscription> openNotification(
            String agentName,
            String instruction,
            String naturalLanguageInput,
            TemplateUri templateUri,
            Consumer<Map<String, Object>> eventCallback) {
        if (agentName == null
                || agentName.isBlank()
                || instruction == null
                || instruction.isBlank()
                || naturalLanguageInput == null
                || naturalLanguageInput.isBlank()) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException(
                        "agentName, instruction and naturalLanguageInput must not be blank"));
        }
        IllegalArgumentException templateError =
                validateTemplate(A2ATExtension.NOTIFICATION_T, templateUri);
        if (templateError != null) return CompletableFuture.failedFuture(templateError);
        AgentCard agentCard = transport.getCard(agentName);
        if (agentCard == null) {
            log.error("[ExtensionSender] Agent not found: {}", agentName);
            return CompletableFuture.failedFuture(
                    new RuntimeException("Agent not found: " + agentName));
        }
        if (!supports(agentCard, A2ATExtension.NOTIFICATION_T)) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException(
                            "Agent '"
                                    + agentName
                                    + "' does not declare "
                                    + A2ATExtension.NOTIFICATION_T.uri()));
        }
        return CompletableFuture.supplyAsync(
                        () ->
                                generateExtensionPrompt(
                                        A2ATExtension.NOTIFICATION_T,
                                        naturalLanguageInput,
                                        templateUri))
                .thenCompose(
                        mc -> {
                            if (mc == null || mc.promptText() == null || mc.promptText().isEmpty()) {
                                return CompletableFuture.failedFuture(
                                        new IllegalStateException(
                                                "A2A-T SDK prompt generation failed for Notification-T"));
                            }
                            String value = mc.promptText();
                            Map<String, Object> metadata = new HashMap<>();
                            metadata.putAll(mc.buildMetadataContent());
                            return sendNotificationMetadata(
                                    agentCard, agentName, instruction, metadata, eventCallback, value);
                        });
    }

    @Override
    public CompletableFuture<SendMessageResult> sendNotificationFromData(
            String agentName,
            String instruction,
            Map<String, Object> data,
            Map<String, Object> schema,
            TemplateUri templateUri,
            Consumer<Map<String, Object>> eventCallback) {
        return openNotificationFromData(
                        agentName, instruction, data, schema, templateUri, eventCallback)
                .thenCompose(NotificationSubscription::acknowledgement);
    }

    @Override
    public CompletableFuture<NotificationSubscription> openNotificationFromData(
            String agentName,
            String instruction,
            Map<String, Object> data,
            Map<String, Object> schema,
            TemplateUri templateUri,
            Consumer<Map<String, Object>> eventCallback) {
        if (agentName == null
                || agentName.isBlank()
                || instruction == null
                || instruction.isBlank()
                || data == null
                || schema == null
                || schema.isEmpty()) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException(
                        "fromData notification requires agentName, instruction, data and schema"));
        }
        IllegalArgumentException templateError =
                validateTemplate(A2ATExtension.NOTIFICATION_T, templateUri);
        if (templateError != null) return CompletableFuture.failedFuture(templateError);
        AgentCard agentCard = transport.getCard(agentName);
        if (agentCard == null) {
            log.error("[ExtensionSender] Agent not found: {}", agentName);
            return CompletableFuture.failedFuture(
                    new RuntimeException("Agent not found: " + agentName));
        }
        if (!supports(agentCard, A2ATExtension.NOTIFICATION_T)) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException(
                            "Agent '"
                                    + agentName
                                    + "' does not declare "
                                    + A2ATExtension.NOTIFICATION_T.uri()));
        }
        return CompletableFuture.supplyAsync(
                        () ->
                                generateExtensionPromptFromData(
                                        A2ATExtension.NOTIFICATION_T,
                                        data,
                                        schema,
                                        templateUri))
                .thenCompose(
                        mc -> {
                            if (mc == null || mc.promptText() == null || mc.promptText().isEmpty()) {
                                log.error(
                                        "[ExtensionSender] Notification-T fromData rendering"
                                                + " unavailable for {}; refusing to send raw data",
                                        agentName);
                                return CompletableFuture.failedFuture(
                                        new RuntimeException(
                                                "Notification-T fromData rendering failed"));
                            }
                            Map<String, Object> metadata = new HashMap<>();
                            metadata.putAll(mc.buildMetadataContent());
                            return sendNotificationMetadata(
                                    agentCard,
                                    agentName,
                                    instruction,
                                    metadata,
                                    eventCallback,
                                    mc.promptText());
                        });
    }

    /** Shared Notification-T stream-open path used by both the text and fromData tracks. */
    private CompletableFuture<NotificationSubscription> sendNotificationMetadata(
            AgentCard agentCard,
            String agentName,
            String instruction,
            Map<String, Object> metadata,
            Consumer<Map<String, Object>> eventCallback,
            String renderedValue) {
        Consumer<ClientEvent> eventSink =
                eventCallback != null
                        ? event -> forwardNotificationEvent(event, agentName, eventCallback)
                        : null;
        log.info(
                "[ExtensionSender] sendNotification to {}: metadataValue={} chars, callback={}",
                agentName,
                renderedValue.length(),
                eventCallback != null);
        return CompletableFuture.completedFuture(
                transport.openNotificationStream(
                        agentCard,
                        agentName,
                        instruction,
                        transport.getContextId(),
                        metadata,
                        eventSink));
    }

    private void forwardNotificationEvent(
            ClientEvent event, String agentName, Consumer<Map<String, Object>> callback) {
        Map<String, Object> data = ClientEventMapper.toMap(event, agentName);
        if (!data.isEmpty()) {
            log.info("[ExtensionSender] Notification-T callback for {}: {} keys", agentName, data.keySet());
            try {
                callback.accept(data);
            } catch (RuntimeException callbackError) {
                log.warn(
                        "[ExtensionSender] Notification-T callback failed for {}: {}",
                        agentName,
                        callbackError.getMessage(),
                        callbackError);
            }
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
     * <p>The caller selects the current SDK template explicitly. This keeps the generic sender
     * capable of both Notification-T scenarios ({@code SERVICE_RECOVERY} and {@code
     * SUBSCRIBE_INCIDENT}) without a scenario-specific default.
     *
     * @return {@link MetadataContent} with prompt text and template URI, or null if SDK unavailable
     */
    MetadataContent generateExtensionPrompt(
            A2ATExtension extension,
            String naturalLanguageInput,
            TemplateUri templateUri) {
        A2ATClient a2atClient = transport.getA2atClient();
        if (a2atClient == null) {
            return null;
        }
        return switch (extension) {
            case TASK_T -> a2atClient.generateTaskPromptFromText(
                    naturalLanguageInput, templateUri);
            case AUTHORIZATION_T -> a2atClient.generateAuthPromptFromText(
                    naturalLanguageInput, templateUri);
            case NOTIFICATION_T -> a2atClient.generateNotificationPromptFromText(
                    naturalLanguageInput, templateUri);
            case NEGOTIATION_T -> throw new IllegalArgumentException(
                    "Negotiation-T uses the dedicated negotiation flow");
        };
    }

    private static boolean supports(AgentCard card, A2ATExtension extension) {
        return A2ATransport.extractExtensionUris(card).contains(extension.uri());
    }

    private static CompletableFuture<SendMessageResult> validateRequest(
            String agentName,
            String instruction,
            String naturalLanguageInput,
            A2ATExtension extension) {
        if (agentName == null
                || agentName.isBlank()
                || instruction == null
                || instruction.isBlank()
                || naturalLanguageInput == null
                || naturalLanguageInput.isBlank()) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException(
                            "agentName, instruction and naturalLanguageInput must not be blank"));
        }
        if (extension != A2ATExtension.AUTHORIZATION_T) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException(
                            "One-shot sending supports Authorization-T only; "
                                    + "use openNotification for Notification-T"));
        }
        return null;
    }

    private static IllegalArgumentException validateTemplate(
            A2ATExtension extension, TemplateUri templateUri) {
        if (templateUri == null) {
            return new IllegalArgumentException("A2A-T template URI is required");
        }
        String expectedExtension =
                switch (extension) {
                    case TASK_T -> StandardTemplates.TASK_EXTENSION_NAME;
                    case NEGOTIATION_T -> StandardTemplates.NEGOTIATION_EXTENSION_NAME;
                    case AUTHORIZATION_T -> StandardTemplates.AUTHORIZATION_EXTENSION_NAME;
                    case NOTIFICATION_T -> StandardTemplates.NOTIFICATION_EXTENSION_NAME;
                };
        if (!expectedExtension.equals(templateUri.extensionName())) {
            return new IllegalArgumentException(
                    "Template "
                            + templateUri.uri()
                            + " does not belong to "
                            + extension.displayName());
        }
        return null;
    }

    @Override
    public void close() {
        // Transport is owned by the caller; do not close it here.
    }
}
