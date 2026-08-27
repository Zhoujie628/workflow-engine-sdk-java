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
import dev.openan.workflow.engine.model.NegotiationDecision;
import dev.openan.workflow.engine.model.NegotiationRequest;
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
        NegotiationRequest request = toNegotiationRequest(agentName, negText, negMeta);
        CompletableFuture<NegotiationDecision> decisionFuture =
                controlPoint != null
                        ? controlPoint.onNegotiation(request)
                        : CompletableFuture.completedFuture(
                                NegotiationDecision.acceptText(
                                        "Please proceed with the original task using available information."));
        return decisionFuture.thenCompose(
                decision ->
                        decision == null
                                ? failWithoutDecision(agentName, result, round)
                                : continueNegotiationRound(
                                        agentCard,
                                        agentName,
                                        originalMessage,
                                        contextId,
                                        result,
                                        negMeta,
                                        decision,
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
     * Terminal path when the control point produces no decision: negotiation failed, the
     * agent's reply stands as the final response.
     */
    private CompletableFuture<SendMessageResult> failWithoutDecision(
            String agentName, SendMessageResult result, int round) {
        emit(
                EventType.NEGOTIATION_FAILED,
                Map.of("agent", agentName, "round", round, "reason", "no decision"));
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
                        NegotiationDecision.abortData(
                                "negotiation round budget exhausted ("
                                        + maxNegotiationRounds
                                        + ")"))
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
     * One negotiation round with a typed decision: renders the follow-up metadata via the SDK
     * content layer, sends it, and recurses into the next round.
     */
    private CompletableFuture<SendMessageResult> continueNegotiationRound(
            AgentCard agentCard,
            String agentName,
            String originalMessage,
            String contextId,
            SendMessageResult result,
            Map<String, Object> negMeta,
            NegotiationDecision decision,
            int round) {
        log.info(
                "[Negotiation] Decision for '{}' round {}: {} via {}",
                agentName,
                round,
                decision.action(),
                decision.input().getClass().getSimpleName());
        emit(
                EventType.NEGOTIATION_RESOLVED,
                Map.of("agent", agentName, "round", round, "decision", decision.action().name()));
        String taskId = result.getTask() != null ? result.getTask().id() : null;
        return buildNegotiationFollowUpMeta(agentName, negMeta, decision)
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
                                            : throwMissingNegotiationContent(agentName);
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
     * <p>The decision's action and input type select the exact current SDK method. The typed ending
     * message preserves the received stateless session/round context. Rendering failures are fatal:
     * raw business input is never disguised as protocol metadata.
     */
    private CompletableFuture<Map<String, Object>> buildNegotiationFollowUpMeta(
            String agentName,
            Map<String, Object> negMeta,
            NegotiationDecision decision) {
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
                        MetadataContent mc =
                                renderFollowUpMessage(content, agentName, negMeta, decision);
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
     * Renders the follow-up through the SDK method selected by the typed business decision.
     */
    private MetadataContent renderFollowUpMessage(
            A2ATContentFacade content,
            String agentName,
            Map<String, Object> negMeta,
            NegotiationDecision decision) {
        net.openan.a2at.sdk.core.model.NegotiationContext contentCtx =
                extractContentContext(negMeta);
        net.openan.a2at.sdk.core.model.TemplateUri endingTemplate =
                endingTemplateFor(negMeta);
        var context = requireContext(contentCtx, agentName);
        if (decision.input() instanceof NegotiationDecision.NaturalLanguage natural) {
            return renderFromText(content, decision.action(), natural.text(), context, endingTemplate);
        }
        NegotiationDecision.StructuredData structured =
                (NegotiationDecision.StructuredData) decision.input();
        return renderFromData(
                content, decision.action(), structured.values(), context, endingTemplate);
    }

    /**
     * Renders one natural-language decision through the corresponding SDK fromText method.
     */
    private MetadataContent renderFromText(
            A2ATContentFacade content,
            NegotiationDecision.Action action,
            String text,
            net.openan.a2at.sdk.core.model.NegotiationContext contentCtx,
            net.openan.a2at.sdk.core.model.TemplateUri endingTemplate) {
        return switch (action) {
            case ACCEPT -> content.generateAcceptFromText(text, contentCtx, endingTemplate);
            case REJECT -> content.generateRejectFromText(text, contentCtx, endingTemplate);
            case ABORT -> content.generateAbortFromText(text, contentCtx);
        };
    }

    /** Renders one deterministic typed decision through the corresponding SDK fromData method. */
    private MetadataContent renderFromData(
            A2ATContentFacade content,
            NegotiationDecision.Action action,
            Map<String, String> values,
            net.openan.a2at.sdk.core.model.NegotiationContext contentCtx,
            net.openan.a2at.sdk.core.model.TemplateUri endingTemplate) {
        if (action == NegotiationDecision.Action.ABORT) {
            String reason = requireOnlyField(values, "terminationReason", "Abort");
            return content.generateAbortFromData(
                    new net.openan.a2at.sdk.negotiation.content.NegotiationAbortData(
                            contentCtx,
                            new net.openan.a2at.sdk.negotiation.content.NegotiationAbortContent(
                                    reason)));
        }
        var conclusion =
                action == NegotiationDecision.Action.ACCEPT
                        ? net.openan.a2at.sdk.negotiation.content.NegotiationConclusion.ACCEPT
                        : net.openan.a2at.sdk.negotiation.content.NegotiationConclusion.REJECT;
        var endingContent = buildEndingContent(endingTemplate, values, conclusion);
        var data =
                new net.openan.a2at.sdk.negotiation.content.NegotiationEndingData(
                        contentCtx, endingContent);
        return action == NegotiationDecision.Action.ACCEPT
                ? content.generateAcceptFromData(data, endingTemplate)
                : content.generateRejectFromData(data, endingTemplate);
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
            buildEndingContent(
                    net.openan.a2at.sdk.core.model.TemplateUri endingTemplate,
                    Map<String, String> data,
                    net.openan.a2at.sdk.negotiation.content.NegotiationConclusion conclusion) {
        if (StandardTemplates.INFORMATION_NEGOTIATION_ACCEPT_REJECT.equals(endingTemplate)) {
            var items = data.entrySet().stream()
                    .map(entry -> new net.openan.a2at.sdk.negotiation.content.NegotiationItem(
                            entry.getKey(), entry.getValue()))
                    .toList();
            return new net.openan.a2at.sdk.negotiation.content.InformationEndingContent(
                    conclusion, items);
        }
        if (StandardTemplates.TARGET_NEGOTIATION_ACCEPT_REJECT.equals(endingTemplate)) {
            return new net.openan.a2at.sdk.negotiation.content.TargetEndingContent(
                    conclusion,
                    requireOnlyField(data, "confirmedIntent", actionName(conclusion)),
                    null);
        }
        if (StandardTemplates.FEASIBILITY_NEGOTIATION_ACCEPT_REJECT.equals(endingTemplate)) {
            return new net.openan.a2at.sdk.negotiation.content.FeasibilityEndingContent(
                    conclusion,
                    requireOnlyField(data, "feasibilitySummary", actionName(conclusion)));
        }
        throw new IllegalArgumentException(
                "Unsupported Negotiation-T ending template: " + endingTemplate.uri());
    }

    private static String actionName(
            net.openan.a2at.sdk.negotiation.content.NegotiationConclusion conclusion) {
        return conclusion == net.openan.a2at.sdk.negotiation.content.NegotiationConclusion.ACCEPT
                ? "Accept"
                : "Reject";
    }

    private static String throwMissingNegotiationContent(String agentName) {
        throw new IllegalStateException(
                "A2A-T SDK returned no rendered negotiation content for '" + agentName + "'");
    }

    private static String requireOnlyField(
            Map<String, String> data, String field, String action) {
        if (data.size() != 1 || !data.containsKey(field)) {
            throw new IllegalArgumentException(
                    "fromData " + action + " requires exactly the field '" + field + "'");
        }
        return data.get(field);
    }

    private static NegotiationRequest toNegotiationRequest(
            String agentName, String concern, Map<String, Object> metadata) {
        var context =
                requireContext(
                        extractContentContext(metadata), agentName);
        Object rawTemplate =
                metadata.get(
                        net.openan.a2at.sdk.core.model.MetadataContent.TEMPLATE_URI_METADATA_KEY);
        return new NegotiationRequest(
                agentName,
                concern,
                context.id(),
                context.round(),
                context.maxRounds(),
                context.performative(),
                negotiationKind(rawTemplate),
                rawTemplate instanceof String text ? text : "",
                metadata);
    }

    private static NegotiationRequest.Kind negotiationKind(Object rawTemplate) {
        String uri = rawTemplate instanceof String text ? text : "";
        if (StandardTemplates.INFORMATION_NEGOTIATION_PROPOSE.uri().equals(uri)) {
            return NegotiationRequest.Kind.INFORMATION;
        }
        if (StandardTemplates.TARGET_NEGOTIATION_PROPOSE.uri().equals(uri)) {
            return NegotiationRequest.Kind.TARGET;
        }
        if (StandardTemplates.FEASIBILITY_NEGOTIATION_PROPOSE.uri().equals(uri)) {
            return NegotiationRequest.Kind.FEASIBILITY;
        }
        throw new IllegalArgumentException(
                "Missing or unsupported Negotiation-T propose templateUri: " + rawTemplate);
    }

    /**
     * Extracts the negotiation session context (id/round/maxRounds/performative) from the received
     * metadata. The latest SDK's canonical {@code negotiationContext} entry carries all four
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
