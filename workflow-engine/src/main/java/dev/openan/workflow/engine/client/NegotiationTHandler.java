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

    /**
     * Extracts the stateful negotiation context map from the reply metadata.
     *
     * <p>Priority 1: the {@code negotiation_context} key — the SDK demo convention; the agent
     * embeds the {@code startNegotiation} payload's context map (negotiationType /
     * negotiationId / round / status) there, exactly what {@code receiveNegotiation} expects.
     *
     * <p>Priority 2 (legacy): a {@code DATA-NEGOTIATION-T} metadata entry. When neither is
     * present the caller falls back to passing the whole metadata map, which the SDK rejects
     * with a context-parse error — handled by the direct-extraction fallback.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> extractNegotiationContext(Map<String, Object> metadata) {
        if (metadata == null) {
            return null;
        }
        Object contextValue = metadata.get("negotiation_context");
        if (contextValue instanceof Map<?, ?> contextMap
                && ((Map<?, ?>) contextMap).containsKey("negotiationType")) {
            return (Map<String, Object>) contextMap;
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
     * Parses the negotiation session context for the validate APIs.
     *
     * <p>Primary source: the engine's {@code negotiation_context} key (id/round/maxRounds —
     * the wire serialization from {@code A2ATContentFacade.contextPayload}). Fallback: the
     * SDK content-layer's {@code negotiationContext} key, whose {@code buildMetadataContent}
     * embeds {@code id} only (round defaults to 1). Returns null when neither is usable; the
     * SDK validate APIs treat a null context as not-a-negotiation-message.
     */
    private static net.openan.a2at.sdk.core.model.NegotiationContext parseNegotiationContext(
            Map<String, Object> metadata) {
        Object stateful = metadata.get("negotiation_context");
        if (stateful instanceof Map<?, ?> stateMap) {
            net.openan.a2at.sdk.core.model.NegotiationContext ctx =
                    A2ATContentFacade.contextFromMap(stateMap);
            if (ctx != null) {
                return ctx;
            }
        }
        Object raw = metadata.get(net.openan.a2at.sdk.core.model.MetadataContent.NEGOTIATION_CONTEXT_METADATA_KEY);
        if (raw instanceof Map<?, ?> contextMap) {
            Object id = contextMap.get("id");
            if (id instanceof String s) {
                try {
                    return new net.openan.a2at.sdk.core.model.NegotiationContext(
                            s,
                            1,
                            net.openan.a2at.sdk.core.model.NegotiationContext.DEFAULT_MAX_ROUNDS);
                } catch (IllegalArgumentException e) {
                    log.debug("[Negotiation-T] Malformed negotiationContext metadata: {}", e.getMessage());
                }
            }
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
                    // The negotiation message travels in metadata under the Negotiation-T
                    // URI key (the parts text is a short placeholder), per the SDK demo
                    // convention.
                    String proposeText = extractNegotiationText(metadata);
                    if (proposeText == null || proposeText.isEmpty()) {
                        proposeText = result.getText();
                    }
                    // The agent's reply already signals a pending negotiation: INPUT_REQUIRED
                    // task state plus a negotiation message in metadata. The engine answers
                    // directly (no SDK state machine involved — the content layer is stateless
                    // and the engine owns the session context).
                    if (proposeText != null && !proposeText.isEmpty()) {
                        metadata.put(A2ATExtension.NEGOTIATION_MESSAGE_META_KEY, proposeText);
                        log.info(
                                "[Negotiation-T] Agent '{}' requested negotiation: {}",
                                getAgentName(agentCard),
                                proposeText);
                        // Validate the received negotiation message and extract params against
                        // the propose template matching the declared type. The context travels
                        // in the negotiation_context metadata key; without it the SDK treats
                        // the text as a non-negotiation message.
                        TemplateUri proposeUri = proposeTemplateFor(extractNegotiationContext(metadata));
                        net.openan.a2at.sdk.core.model.NegotiationContext context =
                                parseNegotiationContext(metadata);
                        try {
                            FilledParamData paramData =
                                    a2atClient.validateProposePromptAndDataFilling(
                                            proposeText, context, negotiationParamSchema(), proposeUri);
                            if (paramData != null
                                    && paramData.data() != null
                                    && !paramData.data().isEmpty()) {
                                metadata.put(
                                        A2ATExtension.NEGOTIATION_PARAMS_META_KEY, paramData.data());
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
                    result.setMetadata(metadata);
                    return result;
                });
    }
}
