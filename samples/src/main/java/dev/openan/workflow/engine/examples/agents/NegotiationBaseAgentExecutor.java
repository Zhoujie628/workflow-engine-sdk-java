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

package dev.openan.workflow.engine.examples.agents;

import net.openan.a2at.sdk.client.A2ATClient;
import net.openan.a2at.sdk.core.model.FilledParamData;
import net.openan.a2at.sdk.core.model.StandardTemplates;
import net.openan.a2at.sdk.negotiation.types.model.NegotiationType;
import net.openan.a2at.sdk.server.A2ATServer;

import org.a2aproject.sdk.server.agentexecution.RequestContext;
import org.a2aproject.sdk.server.ServerCallContext;
import org.a2aproject.sdk.server.tasks.AgentEmitter;
import org.a2aproject.sdk.spec.A2AError;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;
import org.a2aproject.sdk.spec.TextPart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import dev.openan.workflow.engine.examples.extension.PrePositionedExtensionHandler;
import dev.openan.workflow.engine.examples.negotiation.NegotiationUtils;
/**
 * Server-side negotiation base, mirroring the Python reference
 * (orchestration-center/samples/agents/negotiation_base_agent.py).
 *
 * <p>Every agent that declares the Negotiation-T extension MUST be able to receive and reply to
 * negotiation messages. This base implements that capability: on a new task it starts a fulfillment
 * negotiation and replies with INPUT_REQUIRED carrying the negotiation context in task metadata; on
 * a Negotiation-T reply (metadata has Negotiation-T key, no Task-T key) it re-executes the business and completes.
 *
 * <p>Negotiation triggers on every new task (deterministic), so the demo exercises the full
 * Negotiation-T round-trip each run. When the A2A-T .env is absent or LLM is disabled, the
 * negotiation context falls back to a minimal in-process context and the business text falls back
 * to the subclass default.
 */
public abstract class NegotiationBaseAgentExecutor extends BaseAgentExecutor {

    private static final Logger log = LoggerFactory.getLogger(NegotiationBaseAgentExecutor.class);
    // Pre-positioned extensions (Authorization-T / Notification-T) are handled
    // by a dedicated handler, keeping this class focused on Negotiation-T.
    private final PrePositionedExtensionHandler prePositionedHandler =
            new PrePositionedExtensionHandler();
    private final BlockingQueue<String> notificationQueue = new LinkedBlockingQueue<>();
    private volatile A2ATClient a2atClient;

    /** The pre-positioned Authorization-T whitelist policy text, or null. */
    protected final String getAuthorizationPolicy() {
        return prePositionedHandler.getAuthorizationPolicy();
    }

    /** The pre-positioned Notification-T subscription text, or null. */
    protected final String getNotificationSubscription() {
        return prePositionedHandler.getNotificationSubscription();
    }

    protected void reportRecoveryResult(AgentEmitter emitter, String result) {
        String notifUri = "https://projects.tmforum.org/a2aproject/telecommunication/extensions/Notification-T/v1";
        Map<String, Object> notifMeta = new LinkedHashMap<>();
        notifMeta.put(notifUri, result);
        List<Part<?>> rParts = List.of(new TextPart(result));
        emitter.addArtifact(rParts, "recovery-result", "recovery-result", notifMeta, false, true);
        log.info("[{}] RECOVERY_REPORTED resultChars={}", getClass().getSimpleName(), result.length());
    }

    protected void pushNotificationResult(String result) {
        if (!notificationQueue.offer(result)) {
            log.warn("[{}] Notification queue rejected a result", getClass().getSimpleName());
        }
    }

    /**
     * Resolve the A2A-T .env path; null disables A2ATClient (negotiation still works with fallback
     * context).
     */
    protected abstract String resolveEnvPath();

    private A2ATClient a2at() {
        if (a2atClient != null) {
            return a2atClient;
        }
        if (Boolean.getBoolean("a2at.llm.disabled")) {
            return null;
        }
        String env = resolveEnvPath();
        if (env == null || env.isBlank()) {
            return null;
        }
        try {
            a2atClient = new A2ATClient(Path.of(env));
            log.info("[{}] A2ATClient ready for negotiation", getClass().getSimpleName());
            return a2atClient;
        } catch (Exception e) {
            log.warn(
                    "[{}] A2ATClient init failed, negotiation will use fallback context: {}",
                    getClass().getSimpleName(),
                    e.getMessage());
            return null;
        }
    }

    private volatile net.openan.a2at.sdk.server.A2ATServer a2atServer;

    /** Server-side SDK facade for task-prompt validation; null when unavailable. */
    protected net.openan.a2at.sdk.server.A2ATServer a2atServer() {
        if (a2atServer != null) {
            return a2atServer;
        }
        if (Boolean.getBoolean("a2at.llm.disabled")) {
            return null;
        }
        String env = resolveEnvPath();
        if (env == null || env.isBlank()) {
            return null;
        }
        try {
            a2atServer = new net.openan.a2at.sdk.server.A2ATServer(Path.of(env));
            return a2atServer;
        } catch (Exception e) {
            log.warn(
                    "[{}] A2ATServer init failed, task validation disabled: {}",
                    getClass().getSimpleName(),
                    e.getMessage());
            return null;
        }
    }

    /**
     * The Task-T template this agent validates incoming task prompts against. Default: the
     * private-line complaint scenario.
     */
    protected net.openan.a2at.sdk.core.model.TemplateUri taskTemplateUri() {
        return StandardTemplates.PRIVATE_LINE_COMPLAINT;
    }

    /**
     * Parameter JSON Schema for the Task-T validate-and-fill pipeline: the task parameters this
     * agent requires. A blank/missing slot (or validation rejection) triggers Negotiation-T.
     */
    protected Map<String, Object> buildTaskParamSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("任务对象", Map.of("type", "string"));
        properties.put("任务上下文", Map.of("type", "string"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("任务上下文"));
        return schema;
    }

    @Override
    public void execute(RequestContext ctx, AgentEmitter emitter) throws A2AError {
        String taskId = ctx.getTaskId();
        String contextId = ctx.getContextId();
        long started = System.nanoTime();
        String input = extractText(ctx.getMessage());
        // Read Task-T prompt from message metadata if present (mirrors Python SDK)
        if (ctx.getMessage() != null && ctx.getMessage().metadata() != null) {
            Object taskTPrompt = ctx.getMessage().metadata().get(NegotiationUtils.TASK_PROMPT_KEY);
            if (taskTPrompt instanceof String taskTText
                    && taskTText.length() > input.length()) {
                log.info("[{}] Using Task-T prompt from message metadata", getClass().getSimpleName());
                input = taskTText;
            }
        }
        log.info(
                "[{}] TASK_START taskId={}, contextId={}, inputChars={}, followUp={}, "
                        + "prePositionedExtension={}",
                getClass().getSimpleName(),
                taskId,
                contextId,
                input.length(),
                isNegotiationReply(ctx),
                PrePositionedExtensionHandler.detect(ctx));
        try {
            String prePositionedExt = PrePositionedExtensionHandler.detect(ctx);
            if (prePositionedExt != null) {
                if (prePositionedExt.contains("Notification")) {
                    handleNotificationSubscription(ctx, emitter);
                } else {
                    prePositionedHandler.handle(
                            ctx, emitter, prePositionedExt, getClass().getSimpleName());
                }
            } else if (isNegotiationReply(ctx)) {
                handleFollowUp(ctx, emitter, input);
            } else {
                handleNewTask(ctx, emitter, input);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.info(
                    "[{}] TASK_INTERRUPTED taskId={}, contextId={}, elapsedMs={}, reason=shutdown",
                    getClass().getSimpleName(),
                    taskId,
                    contextId,
                    elapsedMillis(started));
        } catch (Exception e) {
            log.error(
                    "[{}] TASK_FAILED taskId={}, contextId={}, elapsedMs={}, errorType={}, message={}",
                    getClass().getSimpleName(),
                    taskId,
                    contextId,
                    elapsedMillis(started),
                    e.getClass().getSimpleName(),
                    e.getMessage(),
                    e);
            emitter.fail(buildStatusMessage(contextId, taskId, "Failed: " + e.getMessage()));
        }
    }

    /**
     * Handle a Notification-T subscription: send "subscribed" ack, then block on a queue to keep
     * the SSE stream open. Recovery results pushed via pushNotificationResult are forwarded through
     * this stream with the Notification-T URI in artifact metadata. The stream stays open until the
     * client disconnects or the agent is shut down.
     */
    private void handleNotificationSubscription(RequestContext ctx, AgentEmitter emitter)
            throws InterruptedException {
        String taskId = ctx.getTaskId();
        String contextId = ctx.getContextId();
        String agentTag = getClass().getSimpleName();
        String notifUri =
                "https://projects.tmforum.org/a2aproject/telecommunication/extensions/Notification-T/v1";
        log.info(
                "[{}] NOTIFICATION_SUBSCRIBED taskId={}, contextId={}, action=keep-stream-open",
                agentTag,
                taskId,
                contextId);
        List<Part<?>> ackParts = List.of(new TextPart("订阅成功，启动业务抢通事件上报任务"));
        emitter.addArtifact(
                ackParts, "subscription", agentTag + " subscription", Map.of(), false, true);
        emitStatus(
                emitter,
                TaskState.TASK_STATE_WORKING,
                contextId,
                taskId,
                "订阅成功，启动业务抢通事件上报任务",
                Map.of());
        while (!Thread.currentThread().isInterrupted()) {
            String result = notificationQueue.poll(30, TimeUnit.SECONDS);
            if (result == null) {
                continue;
            }
            log.info(
                    "[{}] NOTIFICATION_PUSH taskId={}, contextId={}, resultChars={}",
                    agentTag,
                    taskId,
                    contextId,
                    result.length());
            Map<String, Object> notifMeta = new LinkedHashMap<>();
            notifMeta.put(notifUri, result);
            List<Part<?>> resultParts = List.of(new TextPart(result));
            emitter.addArtifact(
                    resultParts, "recovery-result", "recovery-result", notifMeta, false, true);
        }
    }

    /** New task: start negotiation and request input. Business runs on the follow-up. */
    private void handleNewTask(RequestContext ctx, AgentEmitter emitter, String input) {
        if (isNegotiationRequested(ctx) || needsNegotiation(input)) {
            requestNegotiation(ctx, emitter, input);
        } else {
            log.info(
                    "[{}] Parameters sufficient, skipping negotiation", getClass().getSimpleName());
            runBusinessAndComplete(ctx, emitter, input, null);
        }
    }

    private boolean isNegotiationRequested(RequestContext ctx) {
        ServerCallContext callContext = ctx.getCallContext();
        if (callContext == null) return false;
        String negUri = NegotiationUtils.NEGOTIATION_T_URI;
        if (callContext.isExtensionRequested(negUri)) return true;
        for (String ext : callContext.getRequestedExtensions()) {
            if (ext.toUpperCase(java.util.Locale.ROOT).contains("NEGOTIATION-T")) return true;
        }
        return false;
    }

    private boolean isNegotiationReply(RequestContext ctx) {
        Map<String, Object> meta = ctx.getMessage() != null ? ctx.getMessage().metadata() : null;
        return NegotiationUtils.hasNegotiationMetadata(meta) && !NegotiationUtils.hasTaskMetadata(meta);
    }

    /**
     * Follow-up: validate the negotiation reply, then re-execute the business with the Accept
     * text attached to the result artifact metadata (the a2a-java SDK closes the SSE stream
     * right after delivering a standalone agent Message, so the Accept must not be sent as one
     * before the final artifact).
     */
    private void handleFollowUp(RequestContext ctx, AgentEmitter emitter, String input) {
        String cleanInput = input;
        A2ATClient client = a2at();
        Map<String, Object> meta =
                ctx.getMessage() != null ? ctx.getMessage().metadata() : null;
        net.openan.a2at.sdk.core.model.NegotiationContext negotiationCtx =
                parseNegotiationContext(meta);
        String acceptText = null;
        if (client != null) {
            try {
                FilledParamData filled = client.validateProposePromptAndDataFilling(
                        cleanInput, negotiationCtx, buildNegotiationParamSchema(),
                        StandardTemplates.INFORMATION_NEGOTIATION_PROPOSE);
                if (filled != null && filled.data() != null && !filled.data().isEmpty()) {
                    log.info("[{}] Negotiation params validated and filled: {} keys",
                            getClass().getSimpleName(), filled.data().keySet());
                }
            } catch (Exception e) {
                log.warn("[{}] validateProposePromptAndDataFilling failed: {}",
                        getClass().getSimpleName(), e.getMessage());
            }
            acceptText = renderAcceptText(client, negotiationCtx);
        }
        log.info("[{}] Follow-up received, re-executing business", getClass().getSimpleName());
        runBusinessAndComplete(ctx, emitter, cleanInput, acceptText);
    }

    /**
     * Renders the Negotiation-T Accept for the received propose. Rendering failures are logged
     * only (null return); the business proceeds regardless.
     */
    private String renderAcceptText(
            A2ATClient client, net.openan.a2at.sdk.core.model.NegotiationContext negotiationCtx) {
        try {
            net.openan.a2at.sdk.core.model.NegotiationContext acceptCtx =
                    negotiationCtx != null
                            ? negotiationCtx
                            : new net.openan.a2at.sdk.core.model.NegotiationContext(
                                    java.util.UUID.randomUUID().toString(),
                                    1,
                                    net.openan.a2at.sdk.core.model.NegotiationContext
                                            .DEFAULT_MAX_ROUNDS);
            net.openan.a2at.sdk.core.model.MetadataContent mc =
                    client.generateNegotiationAcceptPromptFromText(
                            "同意补充所缺参数，信息已完整",
                            acceptCtx,
                            StandardTemplates.INFORMATION_NEGOTIATION_ACCEPT_REJECT);
            if (mc != null && mc.promptText() != null && !mc.promptText().isEmpty()) {
                log.info(
                        "[{}] Negotiation Accept rendered via {}",
                        getClass().getSimpleName(),
                        mc.templateUri());
                return mc.promptText();
            }
        } catch (Exception e) {
            log.warn(
                    "[{}] Accept rendering failed: {}",
                    getClass().getSimpleName(),
                    e.getMessage());
        }
        return null;
    }

    /**
     * Sends the Negotiation-T propose as an INPUT_REQUIRED status with the propose text and
     * metadata attached to the status message.
     *
     * <p>An earlier version sent a standalone Message(ROLE_AGENT) followed by {@code
     * requiresInput()}; the a2a-java SDK closes the SSE stream right after delivering a Message
     * (per spec §3.1.2 a Message is the complete response), so the queued INPUT_REQUIRED status
     * was never delivered and the client hung waiting for a terminal event. Carrying the propose
     * inside the status message keeps the stream semantics intact: INPUT_REQUIRED is an
     * interrupted (non-final) state, the client's runtime treats it as terminal for the
     * sendMessage call, and the engine's negotiation auto-loop picks the propose text up from
     * the merged response metadata.
     */
    private void requestNegotiation(RequestContext ctx, AgentEmitter emitter, String input) {
        String taskId = ctx.getTaskId();
        String contextId = ctx.getContextId();
        String negId = java.util.UUID.randomUUID().toString();
        String negText = renderProposeText(negId, input);
        // Combine the content layer with the stateful SDK runtime per the official demo
        // pattern: startNegotiation produces the transport context payload, whose context
        // map travels in the propose metadata so the client-side engine can advance the
        // state machine (receiveNegotiation/continueNegotiation) with a well-formed context.
        Map<String, Object> startPayload = startStatefulNegotiation(negText);
        Map<String, Object> metadata =
                NegotiationUtils.negotiationResponseMetadata(negText, startPayload);
        Message proposeMessage =
                Message.builder()
                        .messageId(java.util.UUID.randomUUID().toString())
                        .contextId(contextId)
                        .taskId(taskId)
                        .role(Message.Role.ROLE_AGENT)
                        .parts(List.of(new TextPart("存在信息缺失，请补充信息")))
                        .metadata(metadata)
                        .build();
        emitter.requiresInput(proposeMessage);
        log.info(
                "[{}] Requested negotiation via INPUT_REQUIRED status message (context={})",
                getClass().getSimpleName(),
                startPayload != null ? "stateful" : "fallback");
    }

    /**
     * Starts the SDK negotiation state machine for a propose: returns the {@code
     * startNegotiation} transport payload when the server facade is available, else null (the
     * propose then goes out without a context and the client falls back to direct extraction).
     */
    private Map<String, Object> startStatefulNegotiation(String negText) {
        A2ATServer server = a2atServer();
        if (server == null) {
            return null;
        }
        try {
            return server.startNegotiation(negotiationType(), negText, Map.of());
        } catch (Exception e) {
            log.warn(
                    "[{}] startNegotiation failed ({}); propose goes without SDK context",
                    getClass().getSimpleName(),
                    e.getMessage());
            return null;
        }
    }

    /**
     * Renders the propose text through the SDK content engine when available: the subclass's
     * {@link #negotiationType()} picks the template, {@link #buildProposeContent(String)} supplies
     * the typed content, and the SDK renders deterministically (no LLM). Falls back to
     * {@link #defaultNegotiationText()} when the SDK client is unavailable or rendering fails.
     */
    private String renderProposeText(String negotiationId, String input) {
        A2ATClient client = a2at();
        net.openan.a2at.sdk.negotiation.content.NegotiationProposeContent content =
                buildProposeContent(input);
        if (client == null || content == null) {
            return defaultNegotiationText().replace("PLACEHOLDER_NEGOTIATION_ID", negotiationId);
        }
        try {
            net.openan.a2at.sdk.core.model.NegotiationContext contentCtx =
                    new net.openan.a2at.sdk.core.model.NegotiationContext(
                            negotiationId,
                            1,
                            net.openan.a2at.sdk.core.model.NegotiationContext.DEFAULT_MAX_ROUNDS);
            net.openan.a2at.sdk.core.model.MetadataContent mc =
                    client.generateNegotiationProposePromptFromData(
                            new net.openan.a2at.sdk.negotiation.content.NegotiationProposeData(
                                    contentCtx, content),
                            proposeTemplateUri());
            if (mc != null && mc.promptText() != null && !mc.promptText().isEmpty()) {
                log.info(
                        "[{}] SDK-rendered propose via {}: {} chars",
                        getClass().getSimpleName(),
                        mc.templateUri(),
                        mc.promptText().length());
                return mc.promptText();
            }
        } catch (Exception e) {
            log.warn(
                    "[{}] SDK propose rendering failed ({}); using default negotiation text",
                    getClass().getSimpleName(),
                    e.getMessage());
        }
        return defaultNegotiationText().replace("PLACEHOLDER_NEGOTIATION_ID", negotiationId);
    }

    /** Run business logic, emit artifact, and complete the task. */
    private void runBusinessAndComplete(
            RequestContext ctx, AgentEmitter emitter, String input, String acceptText) {
        String taskId = ctx.getTaskId();
        String contextId = ctx.getContextId();
        long started = System.nanoTime();
        log.info(
                "[{}] BUSINESS_START taskId={}, contextId={}, inputChars={}",
                getClass().getSimpleName(),
                taskId,
                contextId,
                input != null ? input.length() : 0);
        String response = executeBusiness(ctx, emitter, input);
        Map<String, Object> metadata = buildResponseMetadata(ctx, response);
        if (acceptText != null && !acceptText.isEmpty()) {
            // Negotiation Accept rides with the result artifact (not as a standalone agent
            // Message, which would close the SSE stream before the final artifact). A dedicated
            // key — NOT the Negotiation-T extension URI, which the engine treats as the
            // "negotiation needed" trigger signal and would loop on.
            metadata.put("negotiation_accept", acceptText);
        }
        List<Part<?>> parts = List.of(new TextPart(response));
        emitter.addArtifact(parts, "result", buildArtifactName(), metadata, false, true);
        emitStatus(
                emitter, TaskState.TASK_STATE_COMPLETED, contextId, taskId, "Completed", metadata);
        emitter.complete(buildStatusMessage(contextId, taskId, "Completed"));
        log.info(
                "[{}] BUSINESS_DONE taskId={}, contextId={}, responseChars={}, elapsedMs={}",
                getClass().getSimpleName(),
                taskId,
                contextId,
                response != null ? response.length() : 0,
                elapsedMillis(started));
    }

    private void emitStatus(
            AgentEmitter emitter,
            TaskState state,
            String contextId,
            String taskId,
            String text,
            Map<String, Object> metadata) {
        TaskStatus status =
                new TaskStatus(state, buildStatusMessage(contextId, taskId, text), null);
        TaskStatusUpdateEvent event =
                TaskStatusUpdateEvent.builder()
                        .taskId(taskId)
                        .contextId(contextId)
                        .status(status)
                        .metadata(metadata)
                        .build();
        emitter.emitEvent(event);
    }

    // ---- subclass extension points ----

    /**
     * The negotiation type this agent starts. Selects the SDK propose template and the typed
     * content model expected from {@link #buildProposeContent(String)}. Default: information
     * negotiation (missing parameters). Override for {@code TARGET} (intent alignment) or {@code
     * FEASIBILITY} (whether the request can be fulfilled).
     */
    protected NegotiationType negotiationType() {
        return NegotiationType.INFORMATION;
    }

    /**
     * Typed propose content rendered by the SDK content engine. The default returns {@code null}
     * to signal "no typed content"; subclasses override together with {@link
     * #negotiationType()}:
     *
     * <ul>
     *   <li>INFORMATION - {@code InformationProposeContent(items, relationship)}
     *   <li>TARGET - {@code TargetProposeContent(description, intent, alignment, clarification)}
     *   <li>FEASIBILITY - {@code FeasibilityProposeContent(description, action, ...)}
     * </ul>
     *
     * @param input the task input text
     * @return typed content, or null to fall back to {@link #defaultNegotiationText()}
     */
    protected net.openan.a2at.sdk.negotiation.content.NegotiationProposeContent buildProposeContent(
            String input) {
        return null;
    }

    /** Resolves the SDK propose template URI for {@link #negotiationType()}. */
    protected net.openan.a2at.sdk.core.model.TemplateUri proposeTemplateUri() {
        return switch (negotiationType()) {
            case TARGET -> StandardTemplates.TARGET_NEGOTIATION_PROPOSE;
            case FEASIBILITY -> StandardTemplates.FEASIBILITY_NEGOTIATION_PROPOSE;
            case INFORMATION -> StandardTemplates.INFORMATION_NEGOTIATION_PROPOSE;
        };
    }

    /**
     * Whether the incoming task parameters are incomplete/wrong and require a Negotiation-T round
     * before proceeding.
     *
     * <p>Default: SDK-driven check — the task prompt runs through the server-side validate-and-fill
     * pipeline ({@code A2ATServer.validateTaskPromptAndDataFilling}) against {@link
     * #taskTemplateUri()} with {@link #buildTaskParamSchema()}; a validation failure or any blank
     * required slot triggers negotiation. Override for a domain-specific heuristic.
     */
    protected boolean needsNegotiation(String input) {
        A2ATServer server = a2atServer();
        if (server == null) {
            return false;
        }
        try {
            FilledParamData filled = server.validateTaskPromptAndDataFilling(
                    input, buildTaskParamSchema(), taskTemplateUri());
            Map<String, Object> data = filled != null ? filled.data() : null;
            if (data == null) {
                return false;
            }
            for (Map.Entry<String, Object> entry : data.entrySet()) {
                Object value = entry.getValue();
                if (value == null || (value instanceof String s && s.isBlank())) {
                    log.info("[{}] Task param '{}' is blank; negotiation needed",
                            getClass().getSimpleName(), entry.getKey());
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            // Validation rejected (semantic/rule failure) means the prompt is not usable as-is.
            log.info("[{}] Task validation failed ({}); negotiation needed",
                    getClass().getSimpleName(), e.getMessage());
            return true;
        }
    }

    /**
     * Run the agent's actual business logic; return the response text. May emit intermediate
     * events.
     */
    protected abstract String executeBusiness(
            RequestContext ctx, AgentEmitter emitter, String input);

    /** Build the task metadata for the completed task (e.g. Authorization-T / Notification-T). */
    protected Map<String, Object> buildResponseMetadata(RequestContext ctx, String response) {
        return new LinkedHashMap<>();
    }

    /** Short human-readable summary for the artifact parts. */
    protected String buildResultSummary() {
        return "专线业务投诉诊断任务诊断结果消息";
    }

    /** Artifact display name. Default: subclass simple name + " result". */
    protected String buildArtifactName() {
        return getClass().getSimpleName() + " result";
    }

    /** Default negotiation text shown when A2ATClient is unavailable. */
    protected String defaultNegotiationText() {
        // Mirrors the SDK information-propose template without the context section (the SDK
        // carries id/round/maxRounds in the negotiationContext metadata key since 2026-08).
        return "## 信息协商\n请根据<所需信息项>补充相关内容。\n\n"
                + "## 所需信息项\n1. 接入端口名称，举例：P533-珠江旧城-PTN3900-23-TPA1EG24-1\n"
                + "2. 投诉分类，举例：专线质差\n"
                + "缺失项之间的关系：以上参数均为必选，缺少无法启动诊断";
    }

    /**
     * Parameter JSON Schema for the SDK validate-and-fill pipeline: the business parameters this
     * agent expects the negotiation follow-up to carry. Mirrors the missing-info items of
     * {@link #defaultNegotiationText()}.
     */
    protected Map<String, Object> buildNegotiationParamSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("接入端口名称", Map.of("type", "string"));
        properties.put("投诉分类", Map.of("type", "string"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        return schema;
    }

    /** Default negotiation concern. */
    protected String defaultNegotiationConcern() {
        return "参数缺失，需协商补充";
    }

    /**
     * Parses the SDK-carried negotiation context ({@code negotiationContext} metadata key) from
     * the incoming message metadata. Returns null when absent or malformed; the SDK validate
     * APIs treat a null context as not-a-negotiation-message.
     */
    protected static net.openan.a2at.sdk.core.model.NegotiationContext parseNegotiationContext(
            Map<String, Object> metadata) {
        if (metadata == null) {
            return null;
        }
        Object raw =
                metadata.get(
                        net.openan.a2at.sdk.core.model.MetadataContent.NEGOTIATION_CONTEXT_METADATA_KEY);
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
            log.debug(
                    "[{}] Malformed negotiationContext metadata: {}",
                    NegotiationBaseAgentExecutor.class.getSimpleName(),
                    e.getMessage());
        }
        return null;
    }

    private static long elapsedMillis(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }

    @Override
    public void cancel(RequestContext ctx, AgentEmitter emitter) throws A2AError {
        emitter.cancel();
    }
}
