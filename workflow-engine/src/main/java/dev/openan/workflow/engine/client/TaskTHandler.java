/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 *    Licensed under the Apache License, Version 2.0 (the License); you may
 *    not use this file except in compliance with the License. You may obtain
 *    a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an AS IS BASIS, WITHOUT
 *    WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *    License for the specific language governing permissions and limitations
 *    under the License.
 */

package dev.openan.workflow.engine.client;

import dev.openan.workflow.engine.control.ControlPoint;
import dev.openan.workflow.engine.control.EventCallback;
import dev.openan.workflow.engine.model.SendMessageResult;

import net.openan.a2at.sdk.client.A2ATClient;
import net.openan.a2at.sdk.client.model.PromptGenerationFailure;
import net.openan.a2at.sdk.client.model.PromptGenerationResult;

import org.a2aproject.sdk.spec.AgentCard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Task-T extension handler.
 *
 * <p>Mirrors the Python SDK's {@code TaskTHandler}. When an AgentCard declares the Task-T
 * extension, this handler calls the A2ATClient to generate a structured task prompt and injects it
 * into the message metadata.
 */
class TaskTHandler implements ExtensionHandler {

    private static final Logger log = LoggerFactory.getLogger(TaskTHandler.class);

    private static String findTaskTUri(AgentCard agentCard) {
        if (agentCard.capabilities() == null) {
            return null;
        }
        var extensions = agentCard.capabilities().extensions();
        if (extensions == null) {
            return null;
        }
        for (var ext : extensions) {
            String uri = ext.uri();
            if (A2ATExtension.TASK_T.uri().equals(uri)) {
                return uri;
            }
        }
        return null;
    }

    private static String getAgentName(AgentCard agentCard) {
        return agentCard.name();
    }

    @Override
    public String extensionKeyword() {
        return "Task-T";
    }

    @Override
    public CompletableFuture<Map<String, Object>> beforeSend(
            AgentCard agentCard,
            String messageText,
            Map<String, Object> metadata,
            A2ATClient a2atClient,
            ControlPoint controlPoint) {
        if (a2atClient == null) {
            Map<String, Object> cleaned =
                    metadata != null ? new HashMap<>(metadata) : new HashMap<>();
            cleaned.remove(A2ATExtension.TASK_TEMPLATE_META_KEY);
            Object taskData = cleaned.remove(A2ATExtension.TASK_DATA_META_KEY);
            Object taskSchema = cleaned.remove(A2ATExtension.TASK_SCHEMA_META_KEY);
            if (taskData != null || taskSchema != null) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException(
                                "A2A-T client is required for structured Task-T rendering"));
            }
            log.warn(
                    "[Task-T] A2A-T client unavailable for '{}'; sending plain A2A without forged Task-T metadata",
                    getAgentName(agentCard));
            return CompletableFuture.completedFuture(cleaned);
        }
        // Skip Task-T prompt generation when this is a Negotiation-T follow-up: the metadata
        // carries a RENDERED follow-up message under the Negotiation-T key. An EMPTY
        // Negotiation-T value only activates the A2A-Extensions header (first task send) and
        // must NOT skip generation.
        if (metadata != null) {
            Object negotiation = metadata.get(A2ATExtension.NEGOTIATION_T.uri());
            if (negotiation instanceof String s && !s.isEmpty()) {
                log.info("[Task-T] Skipping prompt generation for negotiation follow-up");
                // Still strip engine-internal fromData keys: they must never reach the wire.
                Map<String, Object> cleaned = new HashMap<>(metadata);
                cleaned.remove(A2ATExtension.TASK_DATA_META_KEY);
                cleaned.remove(A2ATExtension.TASK_SCHEMA_META_KEY);
                cleaned.remove(A2ATExtension.TASK_TEMPLATE_META_KEY);
                return CompletableFuture.completedFuture(cleaned);
            }
        }
        String taskTUri = findTaskTUri(agentCard);
        if (taskTUri == null) {
            return CompletableFuture.completedFuture(metadata);
        }
        Map<String, Object> result = metadata != null ? new HashMap<>(metadata) : new HashMap<>();
        if (result.containsKey(taskTUri)) {
            log.info("[Task-T] Metadata already preset, skipping generation");
            return CompletableFuture.completedFuture(result);
        }
        // Structured-data track: caller supplied taskData + taskSchema. The SDK bypasses scenario
        // recognition but its schema-aware slot extractor can still invoke the configured LLM.
        Object taskData = result.remove(A2ATExtension.TASK_DATA_META_KEY);
        Object taskSchema = result.remove(A2ATExtension.TASK_SCHEMA_META_KEY);
        Object taskTemplate = result.remove(A2ATExtension.TASK_TEMPLATE_META_KEY);
        if (taskData != null || taskSchema != null) {
            if (!(taskData instanceof Map<?, ?> data)
                    || !(taskSchema instanceof Map<?, ?> schema)
                    || schema.isEmpty()) {
                return CompletableFuture.failedFuture(
                        new IllegalArgumentException(
                                "Structured Task-T requires non-null data and a non-empty schema"));
            }
            return CompletableFuture.supplyAsync(
                    () ->
                            renderFromData(
                                    a2atClient,
                                    agentCard,
                                    taskTUri,
                                    data,
                                    schema,
                                    taskTemplate instanceof String s ? s : null,
                                    result));
        }
        // Free-text track: scenario recognition on the message text.
        return CompletableFuture.supplyAsync(
                () -> {
                    try {
                        PromptGenerationResult promptResult =
                                a2atClient.generateTaskPrompt(messageText);
                        if (promptResult != null && promptResult.success()) {
                            String promptText = promptResult.promptText();
                            if (promptText != null && !promptText.isEmpty()) {
                                result.put(taskTUri, promptText);
                                log.info(
                                        "[Task-T] Generated prompt for '{}': {} chars",
                                        getAgentName(agentCard),
                                        promptText.length());
                                log.debug("[Task-T] Prompt content: [{}]", promptText);
                                return result;
                            }
                            throw new IllegalStateException("A2A-T SDK returned an empty Task-T prompt");
                        } else {
                            PromptGenerationFailure f = promptResult != null ? promptResult.failure() : null;
                            throw new IllegalStateException(
                                    "Task-T prompt generation failed for '"
                                            + getAgentName(agentCard)
                                            + "': code="
                                            + (f != null ? f.code() : "unknown")
                                            + ", stage="
                                            + (f != null ? f.stage() : "unknown")
                                            + ", message="
                                            + (f != null ? f.message() : "unknown"));
                        }
                    } catch (Exception e) {
                        throw e instanceof IllegalStateException state
                                ? state
                                : new IllegalStateException(
                                        "Task-T prompt generation failed for '"
                                                + getAgentName(agentCard)
                                                + "'",
                                        e);
                    }
                });
    }

    /** SDK schema-aware fromData rendering. Failure is fatal; never send an unrendered task. */
    private Map<String, Object> renderFromData(
            A2ATClient a2atClient,
            AgentCard agentCard,
            String taskTUri,
            Map<?, ?> data,
            Map<?, ?> schema,
            String templateUriStr,
            Map<String, Object> result) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> typedData = (Map<String, Object>) data;
            @SuppressWarnings("unchecked")
            Map<String, Object> typedSchema = (Map<String, Object>) schema;
            net.openan.a2at.sdk.core.model.TemplateUri templateUri =
                    templateUriStr != null
                            ? net.openan.a2at.sdk.core.model.TemplateUri.parse(templateUriStr)
                                    .orElseThrow(
                                            () ->
                                                    new IllegalArgumentException(
                                                            "Invalid Task-T template URI: "
                                                                    + templateUriStr))
                            : net.openan.a2at.sdk.core.model.StandardTemplates
                                    .PRIVATE_LINE_COMPLAINT;
            net.openan.a2at.sdk.core.model.MetadataContent content =
                    a2atClient.generateTaskPromptFromDataWithSchema(
                            typedData, typedSchema, templateUri);
            if (content != null && content.promptText() != null && !content.promptText().isEmpty()) {
                result.putAll(content.buildMetadataContent());
                log.info(
                        "[Task-T] Rendered prompt from task data for '{}': {} chars (template={})",
                        getAgentName(agentCard),
                        content.promptText().length(),
                        content.templateUri());
                return result;
            }
            throw new IllegalStateException("A2A-T SDK returned empty Task-T content");
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Task-T fromData rendering failed for '"
                            + getAgentName(agentCard)
                            + "': "
                            + e.getMessage(),
                    e);
        }
    }

    @Override
    public CompletableFuture<SendMessageResult> afterReceive(
            AgentCard agentCard,
            SendMessageResult result,
            A2ATClient a2atClient,
            ControlPoint controlPoint,
            EventCallback eventCallback) {
        return CompletableFuture.completedFuture(result);
    }
}
