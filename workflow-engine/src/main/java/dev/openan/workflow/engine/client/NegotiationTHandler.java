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
import net.openan.a2at.sdk.core.model.FilledParamData;
import net.openan.a2at.sdk.core.model.StandardTemplates;
import net.openan.a2at.sdk.core.model.TemplateUri;

import java.util.Locale;
import java.util.Map;

import org.a2aproject.sdk.spec.AgentCard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Negotiation-T extension handler.
 *
 * <p>Mirrors the Python SDK's {@code NegotiationTHandler}. When an agent returns INPUT_REQUIRED and
 * supports Negotiation-T, this handler calls the A2ATClient to extract the negotiation context and
 * message.
 */
class NegotiationTHandler implements ExtensionHandler {

    private static final Logger log = LoggerFactory.getLogger(NegotiationTHandler.class);

    /** Empty object schema: context params (id/round/maxRounds) only. */
    private static final Map<String, Object> DEFAULT_SCHEMA = Map.of("type", "object");

    private final Map<String, Object> paramSchema;

    /**
     * Creates the handler with the default empty parameter schema (context params only).
     */
    NegotiationTHandler() {
        this(null);
    }

    /**
     * Creates the handler with a caller-provided business parameter schema used by the
     * validate-and-fill pipeline on received propose messages.
     *
     * @param paramSchema parameter JSON schema declaring the business fields to extract; null
     *     falls back to {@link #DEFAULT_SCHEMA}
     */
    NegotiationTHandler(Map<String, Object> paramSchema) {
        this.paramSchema = paramSchema != null ? paramSchema : DEFAULT_SCHEMA;
    }

    @Override
    public Map<String, Object> negotiationParamSchema() {
        return paramSchema;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> extractNegotiationContext(Map<String, Object> metadata) {
        if (metadata == null) {
            return null;
        }
        for (var entry : metadata.entrySet()) {
            if (entry.getKey().contains("DATA-NEGOTIATION-T") && entry.getValue() instanceof Map) {
                return (Map<String, Object>) entry.getValue();
            }
        }
        return null;
    }

    private static String extractNegotiationText(Map<String, Object> metadata) {
        if (metadata == null) {
            return null;
        }
        for (var entry : metadata.entrySet()) {
            String upperKey = entry.getKey().toUpperCase(Locale.ROOT);
            if (upperKey.contains("NEGOTIATION-T")
                    && !upperKey.contains("DATA-NEGOTIATION-T")
                    && entry.getValue() instanceof String) {
                return (String) entry.getValue();
            }
        }
        return null;
    }

    private static boolean supportsNegotiation(AgentCard agentCard) {
        var extensions = agentCard.capabilities().extensions();
        if (extensions == null) {
            return false;
        }
        for (var ext : extensions) {
            String uri = ext.uri();
            if (uri.toUpperCase(Locale.ROOT).contains("NEGOTIATION-T")) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasNegotiationMetadata(Map<String, Object> metadata) {
        if (metadata == null) return false;
        for (String key : metadata.keySet()) {
            String upper = key.toUpperCase(Locale.ROOT);
            if (upper.contains("NEGOTIATION-T") && !upper.contains("DATA-NEGOTIATION-T")) {
                return true;
            }
        }
        return false;
    }

    private static String getAgentName(AgentCard agentCard) {
        return agentCard.name();
    }

    /**
     * Parses the SDK-carried negotiation context ({@code negotiationContext} metadata key with
     * id/round/maxRounds) from the received message metadata. Returns null when absent or
     * malformed; the SDK validate APIs treat a null context as not-a-negotiation-message.
     */
    private static net.openan.a2at.sdk.core.model.NegotiationContext parseNegotiationContext(
            Map<String, Object> metadata) {
        Object raw = metadata.get(net.openan.a2at.sdk.core.model.MetadataContent.NEGOTIATION_CONTEXT_METADATA_KEY);
        if (!(raw instanceof Map<?, ?> contextMap)) {
            return null;
        }
        try {
            Object id = contextMap.get("id");
            Object round = contextMap.get("round");
            Object maxRounds = contextMap.get("maxRounds");
            if (id instanceof String s && round instanceof Number r && maxRounds instanceof Number m) {
                return new net.openan.a2at.sdk.core.model.NegotiationContext(
                        s, r.intValue(), m.intValue());
            }
        } catch (IllegalArgumentException e) {
            log.debug("[Negotiation-T] Malformed negotiationContext metadata: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Resolves the propose template URI matching the negotiation type declared in the received
     * context payload. Falls back to information negotiation when the type is missing or unknown.
     */
    private static TemplateUri proposeTemplateFor(Map<String, Object> contextMap) {
        Object type = contextMap != null ? contextMap.get("negotiationType") : null;
        if (type instanceof String s) {
            return switch (s.replace('-', '_').toUpperCase(Locale.ROOT)) {
                case "TARGET" -> StandardTemplates.TARGET_NEGOTIATION_PROPOSE;
                case "FEASIBILITY" -> StandardTemplates.FEASIBILITY_NEGOTIATION_PROPOSE;
                default -> StandardTemplates.INFORMATION_NEGOTIATION_PROPOSE;
            };
        }
        return StandardTemplates.INFORMATION_NEGOTIATION_PROPOSE;
    }

    @Override
    public String extensionKeyword() {
        return "Negotiation-T";
    }

    @Override
    public CompletableFuture<Map<String, Object>> beforeSend(
            AgentCard agentCard,
            String messageText,
            Map<String, Object> metadata,
            A2ATClient a2atClient,
            ControlPoint controlPoint) {
        // Accept prompt generation is handled by buildNegotiationFollowUpMeta
        // in DefaultWorkflowEngineClient, which sets the Negotiation-T URI key
        // before this method is called. No additional generation needed here.
        return CompletableFuture.completedFuture(metadata);
    }

    @Override
    public CompletableFuture<SendMessageResult> afterReceive(
            AgentCard agentCard,
            SendMessageResult result,
            A2ATClient a2atClient,
            ControlPoint controlPoint,
            EventCallback eventCallback) {
        if (a2atClient == null
                || !supportsNegotiation(agentCard)) {
            return CompletableFuture.completedFuture(result);
        }
        boolean inputRequired =
                result.getTaskState() != null
                        && result.getTaskState().contains("INPUT_REQUIRED");
        boolean hasNegMeta = hasNegotiationMetadata(result.getMetadata());
        if (!inputRequired && !hasNegMeta) {
            return CompletableFuture.completedFuture(result);
        }
        return CompletableFuture.supplyAsync(
                () -> {
                    Map<String, Object> metadata =
                            result.getMetadata() != null
                                    ? new HashMap<>(result.getMetadata())
                                    : new HashMap<>();
                    try {
                        Map<String, Object> contextMap = extractNegotiationContext(metadata);
                        if (contextMap == null) {
                            contextMap = metadata;
                        }
                        Map<String, Object> receiveResult =
                                a2atClient.receiveNegotiation(result.getText(), contextMap);
                        {
                            Map<String, Object> rr = receiveResult;
                            Boolean needResponse = (Boolean) rr.get("needResponse");
                            if (Boolean.TRUE.equals(needResponse)) {
                                String negMsg = (String) rr.getOrDefault("message", "");
                                metadata.put(A2ATExtension.NEGOTIATION_MESSAGE_META_KEY, negMsg);
                                metadata.put(A2ATExtension.NEGOTIATION_CONTEXT_META_KEY, rr);
                                log.info(
                                        "[Negotiation-T] Agent '{}' requested negotiation: {}",
                                        getAgentName(agentCard),
                                        negMsg);
                                // Validate the received negotiation message and extract params
                                // against the propose template matching the declared type. The
                                // SDK contract carries the context in the negotiationContext
                                // metadata key; without it the SDK treats the text as a
                                // non-negotiation message.
                                TemplateUri proposeUri = proposeTemplateFor(contextMap);
                                net.openan.a2at.sdk.core.model.NegotiationContext context =
                                        parseNegotiationContext(metadata);
                                try {
                                    FilledParamData paramData = a2atClient.validateProposePromptAndDataFilling(
                                            result.getText(), context, negotiationParamSchema(), proposeUri);
                                    if (paramData != null && paramData.data() != null
                                            && !paramData.data().isEmpty()) {
                                        metadata.put(
                                                A2ATExtension.NEGOTIATION_PARAMS_META_KEY,
                                                paramData.data());
                                        log.info(
                                                "[Negotiation-T] Validated negotiation message"
                                                        + " for '{}' ({}), extracted {} params",
                                                getAgentName(agentCard),
                                                proposeUri.uri(),
                                                paramData.data().keySet());
                                    }
                                } catch (Exception ve) {
                                    log.warn(
                                            "[Negotiation-T] validateProposePromptAndDataFilling"
                                                    + " failed for '{}' ({}): {}",
                                            getAgentName(agentCard),
                                            proposeUri.uri(),
                                            ve.getMessage());
                                }
                            }
                        }
                    } catch (Exception e) {
                        if (e.getMessage() != null
                                && e.getMessage().contains("Unsupported negotiation type")) {
                            log.debug(
                                    "[Negotiation-T] SDK receiveNegotiation has no handler for"
                                            + " '{}' ({}); using direct extraction",
                                    getAgentName(agentCard),
                                    e.getMessage());
                        } else {
                            log.warn(
                                    "[Negotiation-T] receiveNegotiation error for '{}': {}; using direct extraction",
                                    getAgentName(agentCard),
                                    e.getMessage());
                        }
                        String fallbackText = extractNegotiationText(metadata);
                        if (fallbackText != null && !fallbackText.isEmpty()) {
                            metadata.put(A2ATExtension.NEGOTIATION_MESSAGE_META_KEY, fallbackText);
                            log.info(
                                    "[Negotiation-T] Agent '{}' requested negotiation (direct): {}",
                                    getAgentName(agentCard),
                                    fallbackText);
                        }
                    }
                    result.setMetadata(metadata);
                    return result;
                });
    }
}
