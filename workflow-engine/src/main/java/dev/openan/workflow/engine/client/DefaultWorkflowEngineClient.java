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
import dev.openan.workflow.engine.control.EventType;
import dev.openan.workflow.engine.model.SendMessageResult;

import net.openan.a2at.sdk.client.A2ATClient;
import net.openan.a2at.sdk.core.model.MetadataContent;
import net.openan.a2at.sdk.core.model.PromptTemplate;
import net.openan.a2at.sdk.core.model.StandardTemplates;
import net.openan.a2at.sdk.core.model.TemplateUri;

import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.client.MessageEvent;
import org.a2aproject.sdk.client.TaskUpdateEvent;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Workflow-execution send facade built on a shared {@link A2ATransport}.
 *
 * <p>Single responsibility: the workflow execution send path. Owns the Task-T/Negotiation-T
 * extension handler chain, the Negotiation-T auto-loop, the global EventCallback, and the
 * ControlPoint wiring. All wire-level work (client runtime, auth, SSE event extraction) delegates
 * to the transport.
 *
 * <p>Independent Authorization-T operations and Notification-T subscriptions live on {@link
 * DefaultExtensionSender}.
 */
public class DefaultWorkflowEngineClient implements WorkflowEngineClient, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DefaultWorkflowEngineClient.class);

    private final A2ATransport transport;
    private final ExtensionRegistry extensionRegistry;
    private final int maxNegotiationRounds;
    private final boolean closeTransportOnClose;
    private final java.util.concurrent.atomic.AtomicBoolean closed =
            new java.util.concurrent.atomic.AtomicBoolean();
    private EventCallback eventCallback = new EventCallback();
    private ControlPoint controlPoint;

    public DefaultWorkflowEngineClient(
            A2ATransport transport,
            int maxNegotiationRounds,
            List<ExtensionHandler> customHandlers,
            Map<String, Object> negotiationParamSchema) {
        this(
                transport,
                maxNegotiationRounds,
                customHandlers,
                negotiationParamSchema,
                false);
    }

    private DefaultWorkflowEngineClient(
            A2ATransport transport,
            int maxNegotiationRounds,
            List<ExtensionHandler> customHandlers,
            Map<String, Object> negotiationParamSchema,
            boolean closeTransportOnClose) {
        this.transport = java.util.Objects.requireNonNull(transport, "transport");
        if (maxNegotiationRounds < 1) {
            throw new IllegalArgumentException("maxNegotiationRounds must be positive");
        }
        this.closeTransportOnClose = closeTransportOnClose;
        this.extensionRegistry = new ExtensionRegistry(negotiationParamSchema);
        if (customHandlers != null) {
            for (ExtensionHandler h : customHandlers) {
                extensionRegistry.register(h);
            }
        }
        this.maxNegotiationRounds = maxNegotiationRounds;
        log.info(
                "[EngineClient] Initialized over transport ({} agent(s)), maxNeg={}",
                transport.getAgentNames().size(),
                maxNegotiationRounds);
    }

    public DefaultWorkflowEngineClient(
            A2ATransport transport,
            int maxNegotiationRounds,
            List<ExtensionHandler> customHandlers) {
        this(transport, maxNegotiationRounds, customHandlers, null);
    }

    public DefaultWorkflowEngineClient(A2ATransport transport) {
        this(transport, 3, null, null);
    }

    /** Applies negotiation and extension settings from the same config used by the transport. */
    public DefaultWorkflowEngineClient(
            A2ATransport transport, WorkflowEngineClientConfig config) {
        this(
                transport,
                java.util.Objects.requireNonNull(config, "config").getMaxNegotiationRounds(),
                config.getCustomHandlers(),
                config.getNegotiationParamSchema());
    }

    /** Creates a client which closes the supplied transport when the client is closed. */
    public static DefaultWorkflowEngineClient owning(A2ATransport transport) {
        return new DefaultWorkflowEngineClient(transport, 3, null, null, true);
    }

    /** Owning variant which also applies all client-level configuration. */
    public static DefaultWorkflowEngineClient owning(
            A2ATransport transport, WorkflowEngineClientConfig config) {
        java.util.Objects.requireNonNull(config, "config");
        return new DefaultWorkflowEngineClient(
                transport,
                config.getMaxNegotiationRounds(),
                config.getCustomHandlers(),
                config.getNegotiationParamSchema(),
                true);
    }

    // ------------------------------------------------------------------
    // Wiring
    // ------------------------------------------------------------------

    /**
     * Whether the agent's reply opens a negotiation: metadata carries the Negotiation-T key AND
     * the task is not already completed with a business result. A completed reply carrying both
     * keys (e.g. a follow-up round whose artifact includes the negotiation Accept) ends the
     * loop — the business already ran.
     */
    private static boolean isNegotiationNeeded(SendMessageResult result) {
        Map<String, Object> meta = result.getMetadata();
        if (meta == null || !meta.containsKey(A2ATExtension.NEGOTIATION_T.uri())) {
            return false;
        }
        boolean completed =
                result.getTaskState() != null
                        && result.getTaskState().contains("TASK_STATE_COMPLETED");
        boolean hasBusinessResult = meta.containsKey(A2ATExtension.TASK_T.uri());
        return !(completed && hasBusinessResult);
    }

    @Override
    public void setControlPoint(ControlPoint controlPoint) {
        this.controlPoint = controlPoint;
    }

    @Override
    public void setEventCallback(EventCallback callback) {
        this.eventCallback = callback != null ? callback : new EventCallback();
    }

    // ------------------------------------------------------------------
    // A2A-T template queries
    // ------------------------------------------------------------------

    @Override
    public List<PromptTemplate> getPrompts() {
        A2ATContentFacade content = transport.getContentFacade();
        return content != null ? content.getPrompts() : List.of();
    }

    @Override
    public List<PromptTemplate> getNegotiationPrompts() {
        A2ATContentFacade content = transport.getContentFacade();
        return content != null ? content.getNegotiationPrompts() : List.of();
    }

    @Override
    public Optional<PromptTemplate> getPrompt(TemplateUri templateUri) {
        A2ATContentFacade content = transport.getContentFacade();
        return content != null ? content.getPrompt(templateUri) : Optional.empty();
    }

    // ------------------------------------------------------------------
    // Workflow send path
    // ------------------------------------------------------------------
    private void emit(String type, Map<String, Object> data) {
        try {
            eventCallback.onEvent(type, data);
        } catch (RuntimeException callbackError) {
            log.warn(
                    "[EngineClient] Event callback failed for type={}: {}",
                    type,
                    callbackError.getMessage(),
                    callbackError);
        }
    }

    // ------------------------------------------------------------------
    // Auto-negotiation
    // ------------------------------------------------------------------
    @Override
    public CompletableFuture<SendMessageResult> sendMessage(
            String agentName, String message, String contextId, Map<String, Object> metadata) {
        if (agentName == null || agentName.isBlank() || message == null || message.isBlank()) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("agentName and message must not be blank"));
        }
        AgentCard agentCard = transport.getCard(agentName);
        if (agentCard == null) {
            log.error("[EngineClient] Agent not found: {}", agentName);
            return CompletableFuture.failedFuture(
                    new RuntimeException("Agent not found: " + agentName));
        }
        log.info("[EngineClient] send_message to {}: {} chars", agentName, message.length());
        String effectiveContextId = contextId != null ? contextId : transport.getContextId();
        return runBeforeSendHandlers(agentCard, message, metadata)
                .thenCompose(
                        processedMetadata -> {
                            emit(
                                    EventType.AGENT_REQUEST,
                                    Map.of(
                                            "agent", agentName,
                                            "request", message,
                                            "metadata",
                                                    processedMetadata != null
                                                            ? processedMetadata
                                                            : Map.of()));
                            return transport
                                    .send(
                                            agentCard,
                                            agentName,
                                            message,
                                            effectiveContextId,
                                            processedMetadata,
                                            event -> forwardIntermediateEvent(event, agentName))
                                    .thenCompose(
                                            result -> runAfterReceiveHandlers(agentCard, result))
                                    .thenCompose(
                                            result ->
                                                    autoNegotiate(
                                                            agentCard,
                                                            agentName,
                                                            message,
                                                            effectiveContextId,
                                                            result, 1));
                        })
                .whenComplete(
                        (ignored, error) ->
                                transport.closeConversation(agentCard, effectiveContextId));
    }

    private CompletableFuture<SendMessageResult> autoNegotiate(
            AgentCard agentCard,
            String agentName,
            String originalMessage,
            String contextId,
            SendMessageResult result,
            int round) {
        if (!isNegotiationNeeded(result)) {
            emitAgentResponse(agentName, result);
            return CompletableFuture.completedFuture(result);
        }
        if (round > maxNegotiationRounds) {
            return abortNegotiation(agentCard, agentName, contextId, result, round);
        }
        Map<String, Object> negMeta =
                result.getMetadata() != null ? result.getMetadata() : new HashMap<>();
        Object negVal = negMeta.get(A2ATExtension.NEGOTIATION_T.uri());
        String negText = negVal instanceof String s ? s : "";
        log.info("[Negotiation] Round {} for '{}': {}", round, agentName, negText);
        emit(
                EventType.NEGOTIATION_REQUEST,
                Map.of("agent", agentName, "round", round, "concern", negText));
        CompletableFuture<String> clarFuture =
                controlPoint != null
                        ? controlPoint.onNegotiation(agentName, negText, negMeta)
                        : CompletableFuture.completedFuture(
                                "Please proceed with the original task using available information.");
        return clarFuture.thenCompose(
                clarification ->
                        clarification == null || clarification.isEmpty()
                                ? failWithoutClarification(agentName, result, round)
                                : continueNegotiationRound(
                                        agentCard,
                                        agentName,
                                        originalMessage,
                                        contextId,
                                        result,
                                        negMeta,
                                        clarification,
                                        round));
    }

    /** Emits the terminal AGENT_RESPONSE event for an agent's reply. */
    private void emitAgentResponse(String agentName, SendMessageResult result) {
        Map<String, Object> data = new HashMap<>();
        data.put("agent", agentName);
        data.put("response", result.getText() != null ? result.getText() : "");
        data.put("metadata", result.getMetadata() != null ? result.getMetadata() : Map.of());
        emit(EventType.AGENT_RESPONSE, data);
    }

    /**
     * Terminal path when the control point produces no clarification: negotiation failed, the
     * agent's reply stands as the final response.
     */
    private CompletableFuture<SendMessageResult> failWithoutClarification(
            String agentName, SendMessageResult result, int round) {
        emit(
                EventType.NEGOTIATION_FAILED,
                Map.of("agent", agentName, "round", round, "reason", "no clarification"));
        emitAgentResponse(agentName, result);
        return CompletableFuture.completedFuture(result);
    }

    /**
     * Round-budget-exhaustion path: terminates via the SDK abort flow. The abort message is sent
     * to the agent (best effort \u2014 send failures are logged, not propagated, since the negotiation
     * is over either way) and the last agent reply stands as the final response. The loop does
     * NOT continue on the abort reply.
     */
    private CompletableFuture<SendMessageResult> abortNegotiation(
            AgentCard agentCard,
            String agentName,
            String contextId,
            SendMessageResult result,
            int round) {
        log.warn(
                "[Negotiation] Round budget exhausted for '{}' ({} rounds); aborting",
                agentName,
                maxNegotiationRounds);
        emit(
                EventType.NEGOTIATION_FAILED,
                Map.of("agent", agentName, "round", round, "reason", "round budget exhausted"));
        return buildNegotiationFollowUpMeta(
                        agentName,
                        result.getMetadata() != null ? result.getMetadata() : new HashMap<>(),
                        "abort: negotiation round budget exhausted (" + maxNegotiationRounds + ")")
                .thenCompose(
                        abortMeta -> {
                            Object rendered = abortMeta.get(A2ATExtension.NEGOTIATION_T.uri());
                            String followUp =
                                    rendered instanceof String s && !s.isEmpty()
                                            ? s
                                            : "\u534f\u5546\u5df2\u7ec8\u6b62";
                            return runBeforeSendHandlers(agentCard, followUp, abortMeta)
                                    .thenCompose(
                                            meta ->
                                                    transport.send(
                                                            agentCard,
                                                            agentName,
                                                            followUp,
                                                            contextId,
                                                            meta,
                                                            event ->
                                                                    forwardIntermediateEvent(
                                                                            event, agentName)));
                        })
                .handle(
                        (ignored, error) -> {
                            if (error != null) {
                                log.warn(
                                        "[Negotiation] Abort message delivery to '{}' failed: {}",
                                        agentName,
                                        error.getMessage());
                            }
                            emitAgentResponse(agentName, result);
                            return result;
                        });
    }

    /**
     * One negotiation round with a clarification: renders the follow-up metadata via the SDK
     * content layer, sends it, and recurses into the next round.
     */
    private CompletableFuture<SendMessageResult> continueNegotiationRound(
            AgentCard agentCard,
            String agentName,
            String originalMessage,
            String contextId,
            SendMessageResult result,
            Map<String, Object> negMeta,
            String clarification,
            int round) {
        log.info(
                "[Negotiation] Clarification for '{}' round {}: {}",
                agentName,
                round,
                clarification);
        emit(
                EventType.NEGOTIATION_RESOLVED,
                Map.of("agent", agentName, "round", round, "clarification", clarification));
        String taskId = result.getTask() != null ? result.getTask().id() : null;
        return buildNegotiationFollowUpMeta(agentName, negMeta, clarification)
                .thenCompose(
                        followUpMeta -> {
                            // The message body is the rendered negotiation text itself: the SDK
                            // template output. The same text also travels in metadata under the
                            // Negotiation-T key; agents read either.
                            Object rendered =
                                    followUpMeta.get(A2ATExtension.NEGOTIATION_T.uri());
                            String followUp =
                                    rendered instanceof String s && !s.isEmpty()
                                            ? s
                                            : clarification;
                            return runBeforeSendHandlers(agentCard, followUp, followUpMeta)
                                    .thenCompose(
                                            meta -> {
                                                String ctx =
                                                        contextId != null
                                                                ? contextId
                                                                : transport.getContextId();
                                                return transport
                                                        .send(
                                                                agentCard,
                                                                agentName,
                                                                followUp,
                                                                ctx,
                                                                taskId,
                                                                meta,
                                                                event ->
                                                                        forwardIntermediateEvent(
                                                                                event, agentName))
                                                        .thenCompose(
                                                                r ->
                                                                        runAfterReceiveHandlers(
                                                                                agentCard, r))
                                                        .thenCompose(
                                                                r ->
                                                                        autoNegotiate(
                                                                                agentCard,
                                                                                agentName,
                                                                                originalMessage,
                                                                                contextId,
                                                                                r,
                                                                                round + 1));
                                            });
                        });
    }

    /**
     * Builds the follow-up metadata for one negotiation round using the SDK content layer.
     *
     * <p>The clarification text is classified: {@code reject:...} / {@code abort:...} prefixes
     * select the Reject or Abort terminal templates, anything else renders an Accept message via
     * {@code generateNegotiationAcceptPromptFromText} (one LLM extraction step). When the
     * negotiation context is available, the engine advances the stateless context so the
     * typed ending message preserves the received session/round context. Rendering failures are
     * fatal: raw clarifications are never disguised as protocol messages.
     */
    private CompletableFuture<Map<String, Object>> buildNegotiationFollowUpMeta(
            String agentName, Map<String, Object> negMeta, String clarification) {
        A2ATContentFacade content = transport.getContentFacade();
        if (content == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException(
                            "A2A-T client is required to render a Negotiation-T follow-up for '"
                                    + agentName
                                    + "'"));
        }
        return CompletableFuture.supplyAsync(
                () -> {
                    try {
                        MetadataContent mc = renderFollowUpMessage(content, agentName, negMeta, clarification);
                        if (mc.promptText() != null && !mc.promptText().isEmpty()) {
                            return A2ATContentFacade.toMetadata(mc);
                        }
                        throw new IllegalStateException("A2A-T SDK returned empty negotiation content");
                    } catch (Exception e) {
                        throw new IllegalStateException(
                                "Negotiation-T content generation failed for '" + agentName + "'",
                                e);
                    }
                });
    }

    /**
     * Renders the follow-up message matching the clarification's decision prefix:
     *
     * <ul>
     *   <li>{@code reject:...} / {@code abort:...} — Reject / Abort terminal templates
     *   <li>{@code data:{...}} — deterministic fromData Accept rendering (JSON body carries the
     *       filled parameter map; no LLM call)
     *   <li>anything else — Accept via fromText (one LLM extraction step)
     * </ul>
     */
    private MetadataContent renderFollowUpMessage(
            A2ATContentFacade content,
            String agentName,
            Map<String, Object> negMeta,
            String clarification) {
        net.openan.a2at.sdk.core.model.NegotiationContext contentCtx =
                extractContentContext(negMeta);
        net.openan.a2at.sdk.core.model.TemplateUri endingTemplate =
                endingTemplateFor(negMeta);
        String decision = clarification.strip().toLowerCase(java.util.Locale.ROOT);
        if (decision.startsWith("data:")) {
            // Deterministic fromData Accept: the JSON body carries the filled parameter map.
            return renderAcceptFromData(
                    content,
                    agentName,
                    contentCtx,
                    endingTemplate,
                    clarification.substring("data:".length()));
        }
        if (decision.startsWith("reject:")) {
            log.info("[Negotiation] SDK generateNegotiationRejectPrompt for '{}'", agentName);
            return content.generateRejectFromText(
                    clarification.substring("reject:".length()).strip(),
                    requireContext(contentCtx, agentName),
                    endingTemplate);
        }
        if (decision.startsWith("abort:")) {
            log.info("[Negotiation] SDK generateNegotiationAbortPrompt for '{}'", agentName);
            return content.generateAbortFromText(
                    clarification.substring("abort:".length()).strip(),
                    requireContext(contentCtx, agentName));
        }
        MetadataContent mc =
                content.generateAcceptFromText(
                        clarification,
                        requireContext(contentCtx, agentName),
                        endingTemplate);
        log.info(
                "[Negotiation] SDK generateNegotiationAcceptPrompt: templateUri={}",
                mc.templateUri());
        return mc;
    }

    /**
     * Deterministic fromData Accept rendering: parses the JSON payload into {@code NegotiationItem}
     * name/value pairs and renders through the SDK's fromData pipeline (no LLM call).
     */
    private MetadataContent renderAcceptFromData(
            A2ATContentFacade content,
            String agentName,
            net.openan.a2at.sdk.core.model.NegotiationContext contentCtx,
            net.openan.a2at.sdk.core.model.TemplateUri endingTemplate,
            String jsonBody) {
        requireContext(contentCtx, agentName);
        try {
            net.openan.a2at.sdk.negotiation.content.NegotiationEndingContent endingContent =
                    buildAcceptEndingContent(endingTemplate, jsonBody);
            MetadataContent mc =
                    content.generateAcceptFromData(
                            new net.openan.a2at.sdk.negotiation.content.NegotiationEndingData(
                                    contentCtx, endingContent),
                            endingTemplate);
            log.info(
                    "[Negotiation] SDK generateNegotiationAcceptPromptFromData: templateUri={}, contentType={}",
                    mc.templateUri(),
                    endingContent.getClass().getSimpleName());
            return mc;
        } catch (Exception e) {
            throw new IllegalStateException("fromData Accept rendering failed: " + e.getMessage(), e);
        }
    }

    static net.openan.a2at.sdk.core.model.TemplateUri endingTemplateFor(
            Map<String, Object> negotiationMetadata) {
        Object raw = negotiationMetadata.get(
                net.openan.a2at.sdk.core.model.MetadataContent.TEMPLATE_URI_METADATA_KEY);
        net.openan.a2at.sdk.core.model.TemplateUri propose =
                raw instanceof String text
                        ? net.openan.a2at.sdk.core.model.TemplateUri.parse(text).orElse(null)
                        : null;
        if (StandardTemplates.INFORMATION_NEGOTIATION_PROPOSE.equals(propose)) {
            return StandardTemplates.INFORMATION_NEGOTIATION_ACCEPT_REJECT;
        }
        if (StandardTemplates.TARGET_NEGOTIATION_PROPOSE.equals(propose)) {
            return StandardTemplates.TARGET_NEGOTIATION_ACCEPT_REJECT;
        }
        if (StandardTemplates.FEASIBILITY_NEGOTIATION_PROPOSE.equals(propose)) {
            return StandardTemplates.FEASIBILITY_NEGOTIATION_ACCEPT_REJECT;
        }
        throw new IllegalArgumentException(
                "Missing or unsupported Negotiation-T propose templateUri: " + raw);
    }

    static net.openan.a2at.sdk.negotiation.content.NegotiationEndingContent
            buildAcceptEndingContent(
                    net.openan.a2at.sdk.core.model.TemplateUri endingTemplate,
                    String jsonBody)
                    throws com.fasterxml.jackson.core.JsonProcessingException {
        Map<String, String> data = parseNegotiationData(jsonBody);
        var accept = net.openan.a2at.sdk.negotiation.content.NegotiationConclusion.ACCEPT;
        if (StandardTemplates.INFORMATION_NEGOTIATION_ACCEPT_REJECT.equals(endingTemplate)) {
            var items = data.entrySet().stream()
                    .map(entry -> new net.openan.a2at.sdk.negotiation.content.NegotiationItem(
                            entry.getKey(), entry.getValue()))
                    .toList();
            return new net.openan.a2at.sdk.negotiation.content.InformationEndingContent(
                    accept, items);
        }
        if (StandardTemplates.TARGET_NEGOTIATION_ACCEPT_REJECT.equals(endingTemplate)) {
            return new net.openan.a2at.sdk.negotiation.content.TargetEndingContent(
                    accept, requireOnlyField(data, "confirmedIntent"), null);
        }
        if (StandardTemplates.FEASIBILITY_NEGOTIATION_ACCEPT_REJECT.equals(endingTemplate)) {
            return new net.openan.a2at.sdk.negotiation.content.FeasibilityEndingContent(
                    accept, requireOnlyField(data, "feasibilitySummary"));
        }
        throw new IllegalArgumentException(
                "Unsupported Negotiation-T ending template: " + endingTemplate.uri());
    }

    static java.util.List<net.openan.a2at.sdk.negotiation.content.NegotiationItem>
            parseNegotiationItems(String jsonBody) throws com.fasterxml.jackson.core.JsonProcessingException {
        return parseNegotiationData(jsonBody).entrySet().stream()
                .map(entry -> new net.openan.a2at.sdk.negotiation.content.NegotiationItem(
                        entry.getKey(), entry.getValue()))
                .toList();
    }

    private static Map<String, String> parseNegotiationData(String jsonBody)
            throws com.fasterxml.jackson.core.JsonProcessingException {
        if (jsonBody == null || jsonBody.isBlank()) {
            throw new IllegalArgumentException("fromData Accept JSON must not be blank");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> params =
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .readValue(jsonBody.strip(), Map.class);
        if (params.isEmpty()) {
            throw new IllegalArgumentException(
                    "fromData Accept must contain at least one filled parameter");
        }
        Map<String, String> data = new java.util.LinkedHashMap<>();
        for (var entry : params.entrySet()) {
            String name = entry.getKey() != null ? entry.getKey().strip() : "";
            String value = entry.getValue() instanceof String text ? text.strip() : "";
            if (name.isEmpty() || value.isEmpty() || "null".equalsIgnoreCase(value)) {
                throw new IllegalArgumentException(
                        "fromData Accept items require non-blank string names and values");
            }
            data.put(name, value);
        }
        return java.util.Collections.unmodifiableMap(data);
    }

    private static String requireOnlyField(Map<String, String> data, String field) {
        if (data.size() != 1 || !data.containsKey(field)) {
            throw new IllegalArgumentException(
                    "fromData Accept requires exactly the field '" + field + "'");
        }
        return data.get(field);
    }

    /**
     * Extracts the negotiation session context (id/round/maxRounds) from the received
     * metadata. The latest SDK's canonical {@code negotiationContext} entry carries all three
     * values; no stateful negotiation API or legacy metadata shape is involved.
     */
    private static net.openan.a2at.sdk.core.model.NegotiationContext extractContentContext(
            Map<String, Object> negMeta) {
        Object stateful = negMeta.get(A2ATExtension.NEGOTIATION_CONTEXT_META_KEY);
        if (stateful instanceof Map<?, ?> stateMap) {
            net.openan.a2at.sdk.core.model.NegotiationContext ctx =
                    A2ATContentFacade.contextFromMap(stateMap);
            if (ctx != null) {
                return ctx;
            }
        }
        return null;
    }

    private static net.openan.a2at.sdk.core.model.NegotiationContext requireContext(
            net.openan.a2at.sdk.core.model.NegotiationContext context, String agentName) {
        if (context == null) {
            throw new IllegalStateException(
                    "No negotiation context from agent '" + agentName + "'; cannot render SDK message");
        }
        return context;
    }

    // ------------------------------------------------------------------
    // Extension handler chain
    // ------------------------------------------------------------------

    private CompletableFuture<Map<String, Object>> runBeforeSendHandlers(
            AgentCard agentCard, String message, Map<String, Object> presetMetadata) {
        Map<String, Object> metadata =
                presetMetadata != null ? new HashMap<>(presetMetadata) : new HashMap<>();
        List<String> extUris = A2ATransport.extractExtensionUris(agentCard);
        List<ExtensionHandler> handlers = extensionRegistry.getHandlersForExtensions(extUris);
        CompletableFuture<Map<String, Object>> future = CompletableFuture.completedFuture(metadata);
        for (ExtensionHandler handler : handlers) {
            future =
                    future.thenCompose(
                            m ->
                                    handler.beforeSend(
                                            agentCard,
                                            message,
                                            m,
                                            transport.getA2atClient(),
                                            controlPoint));
        }
        return future;
    }

    private CompletableFuture<SendMessageResult> runAfterReceiveHandlers(
            AgentCard agentCard, SendMessageResult result) {
        List<String> extUris = A2ATransport.extractExtensionUris(agentCard);
        List<ExtensionHandler> handlers = extensionRegistry.getHandlersForExtensions(extUris);
        CompletableFuture<SendMessageResult> future = CompletableFuture.completedFuture(result);
        for (ExtensionHandler handler : handlers) {
            future =
                    future.thenCompose(
                            r ->
                                    handler.afterReceive(
                                            agentCard,
                                            r,
                                            transport.getA2atClient(),
                                            controlPoint,
                                            eventCallback));
        }
        return future;
    }

    // ------------------------------------------------------------------
    // Intermediate event forwarding
    // ------------------------------------------------------------------

    private void forwardIntermediateEvent(ClientEvent event, String agentName) {
        if (event instanceof TaskUpdateEvent tue) {
            if (tue.getUpdateEvent() instanceof TaskStatusUpdateEvent sue) {
                String state = sue.status().state().name();
                StringBuilder statusText = new StringBuilder();
                A2ATransport.extractTextFromMessage(sue.status().message(), statusText);
                Map<String, Object> data = new HashMap<>();
                data.put("agent", agentName);
                data.put("state", state);
                data.put("is_final", sue.isFinal());
                if (!statusText.isEmpty()) data.put("text", statusText.toString());
                if (sue.metadata() != null && !sue.metadata().isEmpty())
                    data.put("metadata", sue.metadata());
                log.info(
                        "[EngineClient] Agent {} status update: {} (final={})",
                        agentName,
                        state,
                        sue.isFinal());
                emit(EventType.AGENT_STATUS_UPDATE, data);
            } else if (tue.getUpdateEvent()
                    instanceof org.a2aproject.sdk.spec.TaskArtifactUpdateEvent ae) {
                StringBuilder text = new StringBuilder();
                A2ATransport.extractTextFromArtifact(ae.artifact(), text);
                Map<String, Object> data = new HashMap<>();
                data.put("agent", agentName);
                data.put("artifact_id", ae.artifact().artifactId());
                data.put("artifact_name", ae.artifact().name());
                data.put("append", ae.append());
                data.put("last_chunk", ae.lastChunk());
                if (!text.isEmpty()) data.put("text", text.toString());
                // Business metadata belongs to the Artifact. Event metadata describes delivery
                // (chunking, tracing, etc.) and must not hide the protocol payload.
                Map<String, Object> artifactMetadata = ae.artifact().metadata();
                if (artifactMetadata != null && !artifactMetadata.isEmpty())
                    data.put("metadata", artifactMetadata);
                log.info(
                        "[EngineClient] Agent {} artifact update: {} ({})",
                        agentName,
                        ae.artifact().name(),
                        ae.artifact().artifactId());
                emit(EventType.AGENT_ARTIFACT_UPDATE, data);
            }
        } else if (event instanceof MessageEvent me) {
            Message msg = me.getMessage();
            StringBuilder text = new StringBuilder();
            A2ATransport.extractTextFromMessage(msg, text);
            Map<String, Object> data = new HashMap<>();
            data.put("agent", agentName);
            data.put("role", msg.role().name());
            if (!text.isEmpty()) data.put("text", text.toString());
            if (msg.metadata() != null && !msg.metadata().isEmpty()) {
                data.put("metadata", msg.metadata());
            }
            log.info("[EngineClient] Agent {} message event: {} chars", agentName, text.length());
            emit(EventType.AGENT_MESSAGE_EVENT, data);
        }
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    @Override
    public CompletableFuture<SendMessageResult> getTask(String agentName, String taskId) {
        if (agentName == null || agentName.isBlank() || taskId == null || taskId.isBlank()) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("agentName and taskId must not be blank"));
        }
        AgentCard agentCard = transport.getCard(agentName);
        if (agentCard == null) {
            return CompletableFuture.failedFuture(new RuntimeException("Agent not found: " + agentName));
        }
        return transport.getTask(agentCard, agentName, taskId);
    }

    @Override
    public CompletableFuture<SendMessageResult> cancelTask(String agentName, String taskId) {
        if (agentName == null || agentName.isBlank() || taskId == null || taskId.isBlank()) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("agentName and taskId must not be blank"));
        }
        AgentCard agentCard = transport.getCard(agentName);
        if (agentCard == null) {
            return CompletableFuture.failedFuture(new RuntimeException("Agent not found: " + agentName));
        }
        return transport.cancelTask(agentCard, agentName, taskId);
    }

    @Override
    public CompletableFuture<SendMessageResult> subscribeToTask(
            String agentName, String taskId,
            java.util.function.Consumer<java.util.Map<String, Object>> eventCallback) {
        if (agentName == null || agentName.isBlank() || taskId == null || taskId.isBlank()) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("agentName and taskId must not be blank"));
        }
        AgentCard agentCard = transport.getCard(agentName);
        if (agentCard == null) {
            return CompletableFuture.failedFuture(new RuntimeException("Agent not found: " + agentName));
        }
        return transport.subscribeToTask(agentCard, agentName, taskId,
                event -> {
                    forwardIntermediateEvent(event, agentName);
                    if (eventCallback != null) {
                        eventCallback.accept(ClientEventMapper.toMap(event, agentName));
                    }
                });
    }

    @Override
    public void close() {
        if (closeTransportOnClose && closed.compareAndSet(false, true)) {
            transport.close();
        }
    }
}
