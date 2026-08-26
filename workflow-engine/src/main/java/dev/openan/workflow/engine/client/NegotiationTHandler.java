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
import net.openan.a2at.sdk.core.model.MetadataContent;
import net.openan.a2at.sdk.core.model.StandardTemplates;
import net.openan.a2at.sdk.core.model.TemplateUri;

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
     * Extracts the canonical stateless content-layer context map from reply metadata.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> extractNegotiationContext(Map<String, Object> metadata) {
        if (metadata == null) {
            return null;
        }
        Object contextValue =
                metadata.get(
                        net.openan.a2at.sdk.core.model.MetadataContent
                                .NEGOTIATION_CONTEXT_METADATA_KEY);
        if (contextValue instanceof Map<?, ?> contextMap) {
            return (Map<String, Object>) contextMap;
        }
        return null;
    }

    private static String extractNegotiationText(Map<String, Object> metadata) {
        if (metadata == null) {
            return null;
        }
        Object value = metadata.get(A2ATExtension.NEGOTIATION_T.uri());
        return value instanceof String text ? text : null;
    }

    private static boolean supportsNegotiation(AgentCard agentCard) {
        if (agentCard.capabilities() == null) {
            return false;
        }
        var extensions = agentCard.capabilities().extensions();
        if (extensions == null) {
            return false;
        }
        for (var ext : extensions) {
            if (A2ATExtension.NEGOTIATION_T.uri().equals(ext.uri())) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasNegotiationMetadata(Map<String, Object> metadata) {
        return metadata != null && metadata.containsKey(A2ATExtension.NEGOTIATION_T.uri());
    }

    private static String getAgentName(AgentCard agentCard) {
        return agentCard.name();
    }

    /**
     * Parses the negotiation session context for the validate APIs.
     *
     * <p>The latest SDK carries all three fields in {@code negotiationContext}; the content layer
     * is stateless and the engine advances this value between messages.
     */
    private static net.openan.a2at.sdk.core.model.NegotiationContext parseNegotiationContext(
            Map<String, Object> metadata) {
        return A2ATContentFacade.contextFromMap(extractNegotiationContext(metadata));
    }

    /**
     * Resolves the propose template URI carried by {@link MetadataContent#buildMetadataContent()}.
     */
    private static TemplateUri proposeTemplateFor(Map<String, Object> metadata) {
        Object raw = metadata.get(
                net.openan.a2at.sdk.core.model.MetadataContent.TEMPLATE_URI_METADATA_KEY);
        TemplateUri parsed = null;
        if (raw instanceof String text) {
            parsed = TemplateUri.parse(text).orElse(null);
        }
        if (parsed == null
                || !java.util.List.of(
                                StandardTemplates.INFORMATION_NEGOTIATION_PROPOSE,
                                StandardTemplates.TARGET_NEGOTIATION_PROPOSE,
                                StandardTemplates.FEASIBILITY_NEGOTIATION_PROPOSE)
                        .contains(parsed)) {
            throw new IllegalArgumentException("Missing or invalid Negotiation-T propose templateUri");
        }
        return parsed;
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
                        // in the negotiationContext metadata key; without it the SDK treats
                        // the text as a non-negotiation message.
                        TemplateUri proposeUri = proposeTemplateFor(metadata);
                        net.openan.a2at.sdk.core.model.NegotiationContext context =
                                parseNegotiationContext(metadata);
                        if (context == null) {
                            throw new IllegalArgumentException(
                                    "Missing or invalid Negotiation-T negotiationContext");
                        }
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
                            throw new IllegalStateException(
                                    "Negotiation-T propose validation failed for '"
                                            + getAgentName(agentCard)
                                            + "' ("
                                            + proposeUri.uri()
                                            + ")",
                                    ve);
                        }
                    }
                    result.setMetadata(metadata);
                    return result;
                });
    }
}
