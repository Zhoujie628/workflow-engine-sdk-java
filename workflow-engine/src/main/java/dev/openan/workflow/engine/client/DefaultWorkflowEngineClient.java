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
 * <p>One-shot pre-positioning sends (Authorization-T / Notification-T) are a separate concern and
 * live on {@link DefaultExtensionSender}.
 */
public class DefaultWorkflowEngineClient implements WorkflowEngineClient, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DefaultWorkflowEngineClient.class);

    private final A2ATransport transport;
    private final ExtensionRegistry extensionRegistry;
    private final int maxNegotiationRounds;
    private EventCallback eventCallback = new EventCallback();
    private ControlPoint controlPoint;

    public DefaultWorkflowEngineClient(
            A2ATransport transport,
            int maxNegotiationRounds,
            List<ExtensionHandler> customHandlers,
            Map<String, Object> negotiationParamSchema) {
        this.transport = transport;
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
        eventCallback.onEvent(type, data);
    }

    // ------------------------------------------------------------------
    // Auto-negotiation
    // ------------------------------------------------------------------
    @Override
    public CompletableFuture<SendMessageResult> sendMessage(
            String agentName, String message, String contextId, Map<String, Object> metadata) {
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
                            // template output when generation succeeded, the raw clarification on
                            // fallback. The same text also travels in metadata under the
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
     * negotiation context is available, the SDK state machine is also advanced so the follow-up
     * payload carries the next-round context. All failures degrade to the raw-clarification
     * fallback.
     */
    private CompletableFuture<Map<String, Object>> buildNegotiationFollowUpMeta(
            String agentName, Map<String, Object> negMeta, String clarification) {
        A2ATContentFacade content = transport.getContentFacade();
        if (content == null) {
            return CompletableFuture.completedFuture(buildFallbackMeta(clarification));
        }
        return CompletableFuture.supplyAsync(
                () -> {
                    try {
                        MetadataContent mc = renderFollowUpMessage(content, agentName, negMeta, clarification);
                        if (mc.promptText() != null && !mc.promptText().isEmpty()) {
                            return advanceStateMachine(content, agentName, negMeta, clarification, mc);
                        }
                    } catch (Exception e) {
                        log.warn(
                                "[Negotiation] SDK content generation failed for '{}': {}; using fallback",
                                agentName,
                                e.getMessage());
                    }
                    return buildFallbackMeta(clarification);
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
        String decision = clarification.strip().toLowerCase(java.util.Locale.ROOT);
        if (decision.startsWith("data:")) {
            // Deterministic fromData Accept: the JSON body carries the filled parameter map.
            return renderAcceptFromData(content, agentName, contentCtx, clarification.substring("data:".length()));
        }
        if (decision.startsWith("reject:")) {
            log.info("[Negotiation] SDK generateNegotiationRejectPrompt for '{}'", agentName);
            return content.generateRejectFromText(
                    clarification.substring("reject:".length()).strip(),
                    requireContext(contentCtx, agentName),
                    StandardTemplates.INFORMATION_NEGOTIATION_ACCEPT_REJECT);
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
                        StandardTemplates.INFORMATION_NEGOTIATION_ACCEPT_REJECT);
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
            String jsonBody) {
        requireContext(contentCtx, agentName);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> params =
                    new com.fasterxml.jackson.databind.ObjectMapper()
                            .readValue(jsonBody.strip(), Map.class);
            java.util.List<net.openan.a2at.sdk.negotiation.content.NegotiationItem> items =
                    new java.util.ArrayList<>();
            for (var entry : params.entrySet()) {
                items.add(
                        new net.openan.a2at.sdk.negotiation.content.NegotiationItem(
                                entry.getKey(), String.valueOf(entry.getValue())));
            }
            MetadataContent mc =
                    content.generateAcceptFromData(
                            new net.openan.a2at.sdk.negotiation.content.NegotiationEndingData(
                                    contentCtx,
                                    new net.openan.a2at.sdk.negotiation.content
                                            .InformationEndingContent(
                                            net.openan.a2at.sdk.negotiation.content
                                                    .NegotiationConclusion.ACCEPT,
                                            items)),
                            StandardTemplates.INFORMATION_NEGOTIATION_ACCEPT_REJECT);
            log.info(
                    "[Negotiation] SDK generateNegotiationAcceptPromptFromData: templateUri={}, items={}",
                    mc.templateUri(),
                    params.keySet());
            return mc;
        } catch (Exception e) {
            throw new IllegalStateException("fromData Accept rendering failed: " + e.getMessage(), e);
        }
    }

    /**
     * Builds the follow-up metadata from the rendered message and, when the session context is
     * available, advances the engine-held round so the follow-up carries the next-round (or
     * terminal) context alongside the message.
     *
     * <p>The content layer is stateless — session identity and round tracking stay with the
     * caller (SDK guide §1.10). The engine owns the context: parses it from the propose
     * metadata, advances via {@code nextRound()}, and re-serializes it for the wire.
     */
    private Map<String, Object> advanceStateMachine(
            A2ATContentFacade content,
            String agentName,
            Map<String, Object> negMeta,
            String clarification,
            MetadataContent mc) {
        Map<String, Object> meta = A2ATContentFacade.toMetadata(mc);
        net.openan.a2at.sdk.core.model.NegotiationContext ctx = extractContentContext(negMeta);
        if (ctx == null) {
            return meta;
        }
        String decision = clarification.strip().toLowerCase(java.util.Locale.ROOT);
        boolean terminal =
                decision.startsWith("reject:") || decision.startsWith("abort:");
        net.openan.a2at.sdk.core.model.NegotiationContext next =
                terminal ? ctx : ctx.nextRound();
        meta.put("negotiation_context", A2ATContentFacade.contextPayload(next));
        log.info(
                "[Negotiation] Context advanced for '{}' id={} round={} -> {}",
                agentName,
                next.id(),
                ctx.round(),
                terminal ? "terminal" : next.round());
        return meta;
    }

    /**
     * Extracts the negotiation session context (id/round/maxRounds) from the received
     * metadata. Primary source: the engine's {@code negotiation_context} key (the wire
     * serialization produced by {@link A2ATContentFacade#contextPayload}); fallback: the SDK
     * content-layer's {@code negotiationContext} key ({@code buildMetadataContent} embeds
     * {@code id} only — the missing rounds default). Returns null when neither is usable.
     */
    private static net.openan.a2at.sdk.core.model.NegotiationContext extractContentContext(
            Map<String, Object> negMeta) {
        Object stateful = negMeta.get(A2ATExtension.NEGOTIATION_CONTEXT_META_KEY);
        if (!(stateful instanceof Map<?, ?> stateMap)) {
            // The propose metadata's negotiation_context key (engine-external convention shared
            // with the samples; mirrors the SDK demo constants).
            stateful = negMeta.get("negotiation_context");
        }
        if (stateful instanceof Map<?, ?> stateMap2) {
            net.openan.a2at.sdk.core.model.NegotiationContext ctx =
                    A2ATContentFacade.contextFromMap(stateMap2);
            if (ctx != null) {
                return ctx;
            }
        }
        Object raw =
                negMeta.get(net.openan.a2at.sdk.core.model.MetadataContent.NEGOTIATION_CONTEXT_METADATA_KEY);
        if (raw instanceof Map<?, ?> contextMap) {
            Object id = contextMap.get("id");
            if (id instanceof String s) {
                try {
                    return new net.openan.a2at.sdk.core.model.NegotiationContext(
                            s,
                            1,
                            net.openan.a2at.sdk.core.model.NegotiationContext.DEFAULT_MAX_ROUNDS);
                } catch (IllegalArgumentException ignored) {
                    // fall through
                }
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

    private static Map<String, Object> buildFallbackMeta(String clarification) {
        Map<String, Object> meta = new HashMap<>();
        meta.put(A2ATExtension.NEGOTIATION_T.uri(), clarification);
        return meta;
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
                if (ae.metadata() != null && !ae.metadata().isEmpty())
                    data.put("metadata", ae.metadata());
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
        AgentCard agentCard = transport.getCard(agentName);
        if (agentCard == null) {
            return CompletableFuture.failedFuture(new RuntimeException("Agent not found: " + agentName));
        }
        return transport.getTask(agentCard, agentName, taskId);
    }

    @Override
    public CompletableFuture<SendMessageResult> cancelTask(String agentName, String taskId) {
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
        AgentCard agentCard = transport.getCard(agentName);
        if (agentCard == null) {
            return CompletableFuture.failedFuture(new RuntimeException("Agent not found: " + agentName));
        }
        return transport.subscribeToTask(agentCard, agentName, taskId,
                event -> forwardIntermediateEvent(event, agentName));
    }

    @Override
    public void close() {
        // Transport is owned by the caller; do not close it here.
    }
}
