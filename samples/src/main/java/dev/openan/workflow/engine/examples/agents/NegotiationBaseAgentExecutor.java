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
import net.openan.a2at.sdk.server.A2ATServer;
import net.openan.a2at.sdk.core.exception.A2ATErrorCodes;
import net.openan.a2at.sdk.core.validation.ContentValidationException;

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

import dev.openan.workflow.engine.examples.extension.AuthorizationPolicy;
import dev.openan.workflow.engine.examples.extension.NotificationPolicy;
import dev.openan.workflow.engine.examples.extension.PrePositionedExtensionHandler;
import dev.openan.workflow.engine.examples.extension.SdkSlotSchemaLoader;
import dev.openan.workflow.engine.client.A2ATExtension;
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
 * <p>Negotiation is triggered by the extension header or incomplete SDK-validated task data. A
 * configured A2A-T client/server is mandatory for protocol generation and validation; the sample
 * never manufactures protocol-shaped fallback text.
 */
public abstract class NegotiationBaseAgentExecutor extends BaseAgentExecutor {

    private static final Logger log = LoggerFactory.getLogger(NegotiationBaseAgentExecutor.class);
    // Pre-positioned extensions (Authorization-T / Notification-T) are handled
    // by a dedicated handler, keeping this class focused on Negotiation-T.
    private final PrePositionedExtensionHandler prePositionedHandler;
    private final BlockingQueue<String> notificationQueue = new LinkedBlockingQueue<>();
    private volatile A2ATClient a2atClient;

    protected NegotiationBaseAgentExecutor() {
        this(new PrePositionedExtensionHandler());
    }

    protected NegotiationBaseAgentExecutor(PrePositionedExtensionHandler prePositionedHandler) {
        this.prePositionedHandler = java.util.Objects.requireNonNull(prePositionedHandler);
    }

    /** The active SDK-validated Authorization-T whitelist, or null. */
    protected final AuthorizationPolicy getAuthorizationPolicy() {
        return prePositionedHandler.getAuthorizationPolicy();
    }

    /** The active Notification-T subscription, or null. */
    protected final NotificationPolicy getNotificationSubscription() {
        return prePositionedHandler.getNotificationSubscription();
    }

    protected void reportRecoveryResult(AgentEmitter emitter, String result) {
        String notifUri = A2ATExtension.NOTIFICATION_T.uri();
        Map<String, Object> notifMeta = new LinkedHashMap<>();
        notifMeta.put(notifUri, result);
        List<Part<?>> rParts = List.of(new TextPart(result));
        emitter.addArtifact(rParts, "recovery-result", "recovery-result", notifMeta, false, true);
        log.info("[{}] RECOVERY_REPORTED resultChars={}", getClass().getSimpleName(), result.length());
    }

    protected void pushNotificationResult(String result) {
        if (getNotificationSubscription() == null) {
            log.warn(
                    "[{}] Dropping recovery result because no validated Notification-T subscription is active",
                    getClass().getSimpleName());
            return;
        }
        if (!notificationQueue.offer(result)) {
            log.warn("[{}] Notification queue rejected a result", getClass().getSimpleName());
        }
    }

    /**
     * Resolve the A2A-T .env path; null means Negotiation-T cannot be served.
     */
    protected abstract String resolveEnvPath();

    private A2ATClient a2at() {
        if (a2atClient != null) {
            return a2atClient;
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
            throw new IllegalStateException(
                    "A2A-T client initialization failed for " + getClass().getSimpleName(), e);
        }
    }

    private volatile net.openan.a2at.sdk.server.A2ATServer a2atServer;

    /** Server-side SDK facade for task-prompt validation; null when unavailable. */
    protected net.openan.a2at.sdk.server.A2ATServer a2atServer() {
        if (a2atServer != null) {
            return a2atServer;
        }
        String env = resolveEnvPath();
        if (env == null || env.isBlank()) {
            return null;
        }
        try {
            a2atServer = new net.openan.a2at.sdk.server.A2ATServer(Path.of(env));
            return a2atServer;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "A2A-T server initialization failed for " + getClass().getSimpleName(), e);
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
        return SdkSlotSchemaLoader.loadConfigured(taskTemplateUri());
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
                if (A2ATExtension.NOTIFICATION_T.uri().equals(prePositionedExt)) {
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
        String notifUri = A2ATExtension.NOTIFICATION_T.uri();
        Map<String, Object> requestMetadata = ctx.getMessage().metadata();
        Object promptValue = requestMetadata.get(notifUri);
        String prompt = promptValue instanceof String text ? text : String.valueOf(promptValue);
        Object templateUri = requestMetadata.get(
                net.openan.a2at.sdk.core.model.MetadataContent.TEMPLATE_URI_METADATA_KEY);
        try {
            prePositionedHandler.acceptNotification(prompt, templateUri, agentTag);
        } catch (Exception validationError) {
            log.warn(
                    "[{}] Notification-T rejected: {}",
                    agentTag,
                    validationError.getMessage());
            emitter.fail(buildStatusMessage(
                    contextId, taskId, "Notification-T validation failed"));
            return;
        }
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
            long heartbeatSeconds = Math.max(
                    1L, Long.getLong("a2at.notification.heartbeat.seconds", 30L));
            String result = notificationQueue.poll(heartbeatSeconds, TimeUnit.SECONDS);
            if (result == null) {
                emitStatus(
                        emitter,
                        TaskState.TASK_STATE_WORKING,
                        contextId,
                        taskId,
                        "Notification-T heartbeat",
                        Map.of(
                                "notificationHeartbeat", true,
                                "timestamp", java.time.Instant.now().toString()));
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
            runBusinessAndComplete(ctx, emitter, input);
        }
    }

    private boolean isNegotiationRequested(RequestContext ctx) {
        ServerCallContext callContext = ctx.getCallContext();
        if (callContext == null) return false;
        String negUri = NegotiationUtils.NEGOTIATION_T_URI;
        return callContext.isExtensionRequested(negUri)
                || callContext.getRequestedExtensions().contains(negUri);
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
        if (client != null) {
            try {
                FilledParamData filled = client.validateAcceptPromptAndDataFilling(
                        cleanInput, negotiationCtx, buildNegotiationParamSchema(),
                        StandardTemplates.INFORMATION_NEGOTIATION_ACCEPT_REJECT);
                if (filled != null && filled.data() != null && !filled.data().isEmpty()) {
                    log.info("[{}] Negotiation params validated and filled: {} keys",
                            getClass().getSimpleName(), filled.data().keySet());
                }
            } catch (Exception e) {
                log.warn("[{}] validateAcceptPromptAndDataFilling rejected the follow-up: {}",
                        getClass().getSimpleName(), e.getMessage());
                emitter.fail(buildStatusMessage(
                        ctx.getContextId(),
                        ctx.getTaskId(),
                        "Negotiation-T Accept validation failed"));
                return;
            }
        }
        log.info("[{}] Follow-up received, re-executing business", getClass().getSimpleName());
        runBusinessAndComplete(ctx, emitter, cleanInput);
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
        net.openan.a2at.sdk.core.model.NegotiationContext negotiationContext =
                new net.openan.a2at.sdk.core.model.NegotiationContext(
                        java.util.UUID.randomUUID().toString(),
                        1,
                        net.openan.a2at.sdk.core.model.NegotiationContext.DEFAULT_MAX_ROUNDS,
                        net.openan.a2at.sdk.core.model.NegotiationPerformative.PROPOSE);
        Map<String, Object> metadata = renderProposeMetadata(negotiationContext, input);
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
                "[{}] Requested negotiation via INPUT_REQUIRED status message (context=id/round/maxRounds/performative)",
                getClass().getSimpleName());
    }

    /**
     * Renders the propose text through the SDK content engine: the subclass's
     * {@link #proposeTemplateUri()} picks the template, {@link #buildProposeContent(String)}
     * supplies the typed content, and the SDK renders deterministically (no LLM). Missing SDK
     * configuration, missing typed content, or rendering failure rejects the task.
     */
    private Map<String, Object> renderProposeMetadata(
            net.openan.a2at.sdk.core.model.NegotiationContext negotiationContext,
            String input) {
        A2ATClient client = a2at();
        net.openan.a2at.sdk.negotiation.content.NegotiationProposeContent content =
                buildProposeContent(input);
        if (client == null) {
            throw new IllegalStateException("A2A-T client is required for Negotiation-T propose");
        }
        if (content == null) {
            throw new IllegalStateException("Typed Negotiation-T propose content is required");
        }
        net.openan.a2at.sdk.core.model.MetadataContent mc =
                client.generateNegotiationProposePromptFromData(
                        new net.openan.a2at.sdk.negotiation.content.NegotiationProposeData(
                                negotiationContext, content),
                        proposeTemplateUri());
        if (mc == null || mc.promptText() == null || mc.promptText().isEmpty()) {
            throw new IllegalStateException("A2A-T SDK returned empty Negotiation-T propose");
        }
        log.info(
                "[{}] SDK-rendered propose via {}: {} chars",
                getClass().getSimpleName(),
                mc.templateUri(),
                mc.promptText().length());
        return new LinkedHashMap<>(mc.buildMetadataContent());
    }

    /** Run business logic, emit artifact, and complete the task. */
    private void runBusinessAndComplete(
            RequestContext ctx, AgentEmitter emitter, String input) {
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
     * The propose template this agent's negotiation starts with. The template URI encodes the
     * negotiation type (information / target / feasibility) in its URI segment and selects the
     * typed content model expected from {@link #buildProposeContent(String)}. Default:
     * information negotiation (missing parameters). Override for target negotiation (intent
     * alignment) or feasibility negotiation (whether the request can be fulfilled).
     */
    protected net.openan.a2at.sdk.core.model.TemplateUri proposeTemplateUri() {
        return StandardTemplates.INFORMATION_NEGOTIATION_PROPOSE;
    }

    /**
     * Typed propose content rendered by the SDK content engine. The default returns {@code null}
     * to signal "no typed content"; subclasses override together with {@link
     * #proposeTemplateUri()}:
     *
     * <ul>
     *   <li>INFORMATION - {@code InformationProposeContent(items, relationship)}
     *   <li>TARGET - {@code TargetProposeContent(description, intent, alignment, clarification)}
     *   <li>FEASIBILITY - {@code FeasibilityProposeContent(description, action, ...)}
     * </ul>
     *
     * @param input the task input text
     * @return typed content; null rejects negotiation because raw fallbacks are not protocol-safe
     */
    protected net.openan.a2at.sdk.negotiation.content.NegotiationProposeContent buildProposeContent(
            String input) {
        return null;
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
                log.info("[{}] Task validation returned no data; negotiation needed",
                        getClass().getSimpleName());
                return true;
            }
            Object properties = buildTaskParamSchema().get("properties");
            java.util.Set<String> expectedFields =
                    properties instanceof Map<?, ?> propertyMap
                            ? propertyMap.keySet().stream().map(String::valueOf)
                                    .collect(java.util.stream.Collectors.toCollection(
                                            java.util.LinkedHashSet::new))
                            : data.keySet();
            for (String field : expectedFields) {
                Object value = data.get(field);
                if (value == null || (value instanceof String s && s.isBlank())) {
                    log.info("[{}] Task param '{}' is blank; negotiation needed",
                            getClass().getSimpleName(), field);
                    return true;
                }
            }
            return false;
        } catch (ContentValidationException e) {
            if (!A2ATErrorCodes.VALIDATION_SEMANTIC_REJECTED.equals(e.getCode())
                    && !A2ATErrorCodes.SLOT_VALIDATION_ERROR.equals(e.getCode())) {
                throw e;
            }
            // A semantic/slot rejection is resolvable through Negotiation-T. Infrastructure and
            // configuration failures are propagated instead of misreported as missing data.
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

    /**
     * Parameter JSON Schema for the SDK validate-and-fill pipeline: the business parameters this
     * agent expects the negotiation follow-up to carry. Mirrors the typed information-propose
     * items returned by {@link #buildProposeContent(String)}.
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
     * Parses the SDK-carried negotiation context for the validate APIs.
     *
     * <p>The latest SDK serializes {@code id}, {@code round}, and {@code maxRounds} under the
     * canonical {@code negotiationContext} metadata key. The content layer remains stateless;
     * callers own and advance this value.
     */
    protected static net.openan.a2at.sdk.core.model.NegotiationContext parseNegotiationContext(
            Map<String, Object> metadata) {
        if (metadata == null) {
            return null;
        }
        Object raw = metadata.get(
                net.openan.a2at.sdk.core.model.MetadataContent.NEGOTIATION_CONTEXT_METADATA_KEY);
        return raw instanceof Map<?, ?> contextMap
                ? dev.openan.workflow.engine.client.A2ATContentFacade.contextFromMap(contextMap)
                : null;
    }

    private static long elapsedMillis(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }

    @Override
    public void cancel(RequestContext ctx, AgentEmitter emitter) throws A2AError {
        emitter.cancel();
    }
}
