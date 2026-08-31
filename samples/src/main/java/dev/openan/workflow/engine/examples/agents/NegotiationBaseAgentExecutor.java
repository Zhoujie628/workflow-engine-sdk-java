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

import dev.openan.workflow.engine.client.A2ATExtension;
import dev.openan.workflow.engine.examples.extension.AuthorizationPolicy;
import dev.openan.workflow.engine.examples.extension.NotificationPolicy;
import dev.openan.workflow.engine.examples.extension.PrePositionedExtensionHandler;
import dev.openan.workflow.engine.examples.extension.SdkSlotSchemaLoader;
import dev.openan.workflow.engine.examples.negotiation.NegotiationUtils;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import net.openan.a2at.sdk.client.A2ATClient;
import net.openan.a2at.sdk.core.exception.ErrorCatalog;
import net.openan.a2at.sdk.core.model.FilledParamData;
import net.openan.a2at.sdk.core.model.StandardTemplates;
import net.openan.a2at.sdk.core.validation.ContentValidationException;
import org.a2aproject.sdk.server.agentexecution.RequestContext;
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

/**
 * Server-side negotiation base, mirroring the Python reference
 * (orchestration-center/samples/agents/negotiation_base_agent.py).
 *
 * <p>Every agent that declares the Negotiation-T extension MUST be able to receive and reply to
 * negotiation messages. This base implements that capability: on an incomplete task it starts an
 * information negotiation and replies with INPUT_REQUIRED carrying the negotiation context; on an
 * Accept reply it validates and consumes the filled parameters before diagnosis. Reject and Abort
 * are validated separately and end the task without executing diagnosis.
 *
 * <p>Negotiation is triggered by incomplete SDK-validated task data, not merely by extension
 * activation. A configured A2A-T client/server is mandatory for protocol generation and validation;
 * the sample never manufactures protocol-shaped fallback text.
 */
public abstract class NegotiationBaseAgentExecutor extends BaseAgentExecutor {

  private static final Logger log = LoggerFactory.getLogger(NegotiationBaseAgentExecutor.class);
  // Pre-positioned extensions (Authorization-T / Notification-T) are handled
  // by a dedicated handler, keeping this class focused on Negotiation-T.
  private final PrePositionedExtensionHandler prePositionedHandler;
  private final BlockingQueue<NotificationEvent> notificationQueue = new LinkedBlockingQueue<>();
  private final java.util.concurrent.ConcurrentMap<String, PendingNegotiation>
      pendingNegotiationTasks = new java.util.concurrent.ConcurrentHashMap<>();
  private volatile A2ATClient a2atClient;
  private volatile net.openan.a2at.sdk.server.A2ATServer a2atServer;

  protected NegotiationBaseAgentExecutor() {
    this(new PrePositionedExtensionHandler());
  }

  protected NegotiationBaseAgentExecutor(PrePositionedExtensionHandler prePositionedHandler) {
    this.prePositionedHandler = java.util.Objects.requireNonNull(prePositionedHandler);
  }

  private static Map<String, Object> endingReasonSchema() {
    return Map.of(
        "type",
        "object",
        "properties",
        Map.of(
            "reason",
            Map.of("type", "string", "minLength", 1, "description", "从拒绝说明或终止原因中提取实际原因，不臆造")),
        "required",
        List.of("reason"));
  }

  private static String requiredText(Map<String, Object> metadata, String key) {
    Object value = metadata == null ? null : metadata.get(key);
    if (!(value instanceof String text) || text.isBlank() || "null".equalsIgnoreCase(text.strip()))
      throw new IllegalArgumentException("Missing nonblank " + key);
    return text;
  }

  private static void requireTemplate(Map<String, Object> metadata, String expected) {
    if (!expected.equals(
        requiredText(
            metadata, net.openan.a2at.sdk.core.model.MetadataContent.TEMPLATE_URI_METADATA_KEY)))
      throw new IllegalArgumentException("Unexpected templateUri; expected " + expected);
  }

  /**
   * Parses the SDK-carried negotiation context for the validate APIs.
   *
   * <p>The latest SDK serializes {@code id}, {@code round}, and {@code maxRounds} under the
   * canonical {@code negotiationContext} metadata key. The content layer remains stateless; callers
   * own and advance this value.
   */
  protected static net.openan.a2at.sdk.core.model.NegotiationContext parseNegotiationContext(
      Map<String, Object> metadata) {
    if (metadata == null) {
      return null;
    }
    Object raw =
        metadata.get(
            net.openan.a2at.sdk.core.model.MetadataContent.NEGOTIATION_CONTEXT_METADATA_KEY);
    return raw instanceof Map<?, ?> contextMap
        ? dev.openan.workflow.engine.client.A2atMessages.contextOf(
            new dev.openan.workflow.engine.model.ReceivedMessage(
                new dev.openan.workflow.engine.model.MessageContent(
                    List.of(), metadata, java.util.Set.of()),
                Map.of(),
                List.of()))
        : null;
  }

  private static long elapsedMillis(long startedNanos) {
    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
  }

  /** The active SDK-validated Authorization-T whitelist, or null. */
  protected final AuthorizationPolicy getAuthorizationPolicy() {
    return prePositionedHandler.getAuthorizationPolicy();
  }

  /** The active Notification-T subscription, or null. */
  protected final NotificationPolicy getNotificationSubscription() {
    return prePositionedHandler.getNotificationSubscription();
  }

  /**
   * Enqueues one business-recovery notification for the independent Notification-T stream. The
   * artifact part remains the protocol-defined event summary; the full structured prompt is carried
   * under the Notification-T metadata URI.
   */
  protected void pushRecoveryNotification(String artifactName, String content) {
    if (artifactName == null || artifactName.isBlank()) {
      throw new IllegalArgumentException("Notification artifact name must not be blank");
    }
    if (content == null || content.isBlank()) {
      throw new IllegalArgumentException("Notification content must not be blank");
    }
    if (getNotificationSubscription() == null) {
      log.warn(
          "[{}] Dropping {} because no validated Notification-T subscription is active",
          getClass().getSimpleName(),
          artifactName);
      return;
    }
    if (!notificationQueue.offer(new NotificationEvent(artifactName, content))) {
      log.warn("[{}] Notification queue rejected {}", getClass().getSimpleName(), artifactName);
    }
  }

  protected final void pushRecoveryPlan(String content) {
    pushRecoveryNotification("recovery-plan", content);
  }

  protected final void pushRecoveryResult(String content) {
    pushRecoveryNotification("recovery-result", content);
  }

  /** Resolve the A2A-T .env path; null means Negotiation-T cannot be served. */
  protected abstract String resolveEnvPath();

  private synchronized A2ATClient a2at() {
    if (a2atClient != null) {
      return a2atClient;
    }
    String env = resolveEnvPath();
    if (env == null || env.isBlank()) {
      throw new IllegalStateException(
          "A2A-T SDK configuration is required for OMC content validation");
    }
    try {
      a2atClient =
          dev.openan.workflow.engine.examples.util.A2ATInitialization.create(
              () -> new A2ATClient(Path.of(env)));
      log.info("[{}] A2ATClient ready for negotiation", getClass().getSimpleName());
      return a2atClient;
    } catch (Exception e) {
      throw new IllegalStateException(
          "A2A-T client initialization failed for " + getClass().getSimpleName(), e);
    }
  }

  /** Server-side SDK facade; initialization failure is not a validation bypass. */
  protected synchronized net.openan.a2at.sdk.server.A2ATServer a2atServer() {
    if (a2atServer != null) {
      return a2atServer;
    }
    String env = resolveEnvPath();
    if (env == null || env.isBlank()) {
      throw new IllegalStateException(
          "A2A-T SDK configuration is required for OMC content validation");
    }
    try {
      a2atServer =
          dev.openan.workflow.engine.examples.util.A2ATInitialization.create(
              () -> new net.openan.a2at.sdk.server.A2ATServer(Path.of(env)));
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
   * Parameter JSON Schema for the Task-T validate-and-fill pipeline: the task parameters this agent
   * requires. A blank/missing slot (or validation rejection) triggers Negotiation-T.
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
      if (taskTPrompt instanceof String taskTText && !taskTText.isBlank()) {
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
          prePositionedHandler.handle(ctx, emitter, prePositionedExt, getClass().getSimpleName());
        }
      } else if (isNegotiationReply(ctx)) {
        handleFollowUp(ctx, emitter);
      } else {
        handleNewTask(ctx, emitter);
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
      pendingNegotiationTasks.remove(taskId);
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
   * Handle a Notification-T subscription: send "subscribed" ack, then block on a queue to keep the
   * SSE stream open. Recovery results pushed via pushNotificationResult are forwarded through this
   * stream with the Notification-T URI in artifact metadata. The stream stays open until the client
   * disconnects or the agent is shut down.
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
    Object templateUri =
        requestMetadata.get(
            net.openan.a2at.sdk.core.model.MetadataContent.TEMPLATE_URI_METADATA_KEY);
    try {
      prePositionedHandler.acceptNotification(prompt, templateUri, agentTag);
    } catch (Exception validationError) {
      log.warn("[{}] Notification-T rejected: {}", agentTag, validationError.getMessage());
      emitter.fail(buildStatusMessage(contextId, taskId, "Notification-T validation failed"));
      return;
    }
    log.info(
        "[{}] NOTIFICATION_SUBSCRIBED taskId={}, contextId={}, action=keep-stream-open",
        agentTag,
        taskId,
        contextId);
    List<Part<?>> ackParts = List.of(new TextPart("订阅成功，启动业务抢通事件上报任务"));
    Map<String, Object> subscriptionMetadata = Map.of(notifUri, "## 订阅结果\n订阅结果：成功");
    emitter.addArtifact(
        ackParts, "subscription", agentTag + " subscription", subscriptionMetadata, false, true);
    emitStatus(
        emitter,
        TaskState.TASK_STATE_WORKING,
        contextId,
        taskId,
        "订阅成功，启动业务抢通事件上报任务",
        subscriptionMetadata);
    while (!Thread.currentThread().isInterrupted()) {
      long heartbeatSeconds =
          Math.max(1L, Long.getLong("a2at.notification.heartbeat.seconds", 30L));
      NotificationEvent event = notificationQueue.poll(heartbeatSeconds, TimeUnit.SECONDS);
      if (event == null) {
        emitStatus(
            emitter,
            TaskState.TASK_STATE_WORKING,
            contextId,
            taskId,
            "Notification-T heartbeat",
            Map.of("notificationHeartbeat", true, "timestamp", java.time.Instant.now().toString()));
        continue;
      }
      log.info(
          "[{}] NOTIFICATION_PUSH taskId={}, contextId={}, artifactName={}, contentChars={}",
          agentTag,
          taskId,
          contextId,
          event.artifactName(),
          event.content().length());
      Map<String, Object> notifMeta = new LinkedHashMap<>();
      notifMeta.put(notifUri, event.content());
      List<Part<?>> resultParts = List.of(new TextPart("业务抢通事件"));
      emitter.addArtifact(
          resultParts, event.artifactName(), event.artifactName(), notifMeta, false, true);
    }
  }

  /** New task: consume SDK-filled data, not the incoming protocol text. */
  private void handleNewTask(RequestContext ctx, AgentEmitter emitter) {
    requireTemplate(ctx.getMessage().metadata(), taskTemplateUri().uri());
    String input = requiredText(ctx.getMessage().metadata(), A2ATExtension.TASK_T.uri());
    Map<String, Object> data;
    try {
      FilledParamData filled =
          a2atServer()
              .validateTaskPromptAndDataFilling(
                  input, buildTaskParamSchema(), taskTemplateUri().uri());
      data =
          SpnTaskInput.selected(
              java.util.Objects.requireNonNull(filled, "Task validator returned null").data());
    } catch (ContentValidationException error) {
      if (!ErrorCatalog.NEGOTIATION_SEMANTIC_REJECTED.getCode().equals(error.getCode())
          && !ErrorCatalog.SLOT_RULE_VIOLATION.getCode().equals(error.getCode())) throw error;
      // A rejected prompt supplies no trusted partial data. Ask for the complete business fields.
      data = Map.of();
    }
    List<String> missing = invalidTaskFields(data);
    if (!missing.isEmpty()) {
      requestNegotiation(ctx, emitter, data, missing);
    } else {
      log.info("[{}] Parameters sufficient, skipping negotiation", getClass().getSimpleName());
      runBusinessAndComplete(ctx, emitter, new SpnTaskInput(data));
    }
  }

  private boolean isNegotiationReply(RequestContext ctx) {
    Map<String, Object> meta = ctx.getMessage() != null ? ctx.getMessage().metadata() : null;
    return NegotiationUtils.hasNegotiationMetadata(meta) && !NegotiationUtils.hasTaskMetadata(meta);
  }

  /** Replies are read from Negotiation-T metadata, validated by phase, then consumed once. */
  private void handleFollowUp(RequestContext ctx, AgentEmitter emitter) {
    Map<String, Object> metadata = ctx.getMessage().metadata();
    var context =
        java.util.Objects.requireNonNull(
            parseNegotiationContext(metadata), "Missing negotiationContext");
    PendingNegotiation pending = pendingNegotiationTasks.get(ctx.getTaskId());
    if (pending == null
        || !java.util.Objects.equals(pending.a2aContextId(), ctx.getContextId())
        || !pending.context().id().equals(context.id())
        || pending.context().round() != context.round()
        || pending.context().maxRounds() != context.maxRounds()) {
      throw new IllegalArgumentException("No matching pending task/negotiation context");
    }
    String prompt = requiredText(metadata, A2ATExtension.NEGOTIATION_T.uri());
    boolean abort =
        context.performative() == net.openan.a2at.sdk.core.model.NegotiationPerformative.ABORT;
    requireTemplate(
        metadata,
        abort
            ? StandardTemplates.NEGOTIATION_ABORT.uri()
            : StandardTemplates.INFORMATION_NEGOTIATION_ACCEPT_REJECT.uri());
    if (!pendingNegotiationTasks.remove(ctx.getTaskId(), pending))
      throw new IllegalStateException("Negotiation reply already consumed");
    switch (context.performative()) {
      case ACCEPT -> acceptReply(ctx, emitter, prompt, context, pending);
      case REJECT -> {
        var filled =
            a2at()
                .validateRejectPromptAndDataFilling(
                    prompt,
                    context,
                    endingReasonSchema(),
                    StandardTemplates.INFORMATION_NEGOTIATION_ACCEPT_REJECT.uri());
        endNegotiation(ctx, emitter, "REJECT", filled);
      }
      case ABORT -> {
        var filled =
            a2at()
                .validateAbortPromptAndDataFilling(
                    prompt,
                    context,
                    endingReasonSchema(),
                    StandardTemplates.NEGOTIATION_ABORT.uri());
        endNegotiation(ctx, emitter, "ABORT", filled);
      }
      default -> throw new IllegalArgumentException("Expected Accept, Reject or Abort reply");
    }
  }

  private void acceptReply(
      RequestContext ctx,
      AgentEmitter emitter,
      String prompt,
      net.openan.a2at.sdk.core.model.NegotiationContext context,
      PendingNegotiation pending) {
    FilledParamData filled =
        a2at()
            .validateAcceptPromptAndDataFilling(
                prompt,
                context,
                replySchema(pending.requestedFields()),
                StandardTemplates.INFORMATION_NEGOTIATION_ACCEPT_REJECT.uri());
    Map<String, Object> merged = new LinkedHashMap<>(pending.validatedData());
    for (String field : pending.requestedFields())
      merged.put(field, requiredText(filled.data(), field));
    if (!invalidTaskFields(merged).isEmpty())
      throw new IllegalArgumentException(
          "Negotiation Accept did not resolve required business fields");
    SpnTaskInput input = new SpnTaskInput(merged);
    log.info(
        "[{}] NEGOTIATION_APPLIED taskId={}, port={}, fields={}",
        getClass().getSimpleName(),
        ctx.getTaskId(),
        input.accessPort(),
        pending.requestedFields());
    runBusinessAndComplete(ctx, emitter, input);
  }

  private void endNegotiation(
      RequestContext ctx, AgentEmitter emitter, String phase, FilledParamData filled) {
    String reason = requiredText(filled.data(), "reason");
    log.info(
        "[{}] NEGOTIATION_ENDED taskId={}, performative={}, action=no-diagnosis",
        getClass().getSimpleName(),
        ctx.getTaskId(),
        phase);
    emitter.fail(
        buildStatusMessage(
            ctx.getContextId(), ctx.getTaskId(), "Negotiation-T " + phase + ": " + reason));
  }

  private Map<String, Object> replySchema(List<String> fields) {
    Map<String, Object> properties = new LinkedHashMap<>();
    for (String field : fields)
      properties.put(
          field,
          Map.of(
              "type",
              "string",
              "minLength",
              1,
              "description",
              field.equals("任务对象")
                  ? "当前地市的任务对象，必须包含实际接入端口名称"
                  : "完整投诉上下文，必须包含投诉分类及OSS侧事件流水号，保留其他已有信息"));
    return Map.of("type", "object", "properties", properties, "required", fields);
  }

  /** Ask for only unresolved business fields; both cities use the same SDK generation path. */
  private void requestNegotiation(
      RequestContext ctx, AgentEmitter emitter, Map<String, Object> data, List<String> missing) {
    var context =
        new net.openan.a2at.sdk.core.model.NegotiationContext(
            java.util.UUID.randomUUID().toString(),
            1,
            net.openan.a2at.sdk.core.model.NegotiationContext.DEFAULT_MAX_ROUNDS,
            net.openan.a2at.sdk.core.model.NegotiationPerformative.PROPOSE);
    var items =
        missing.stream()
            .map(
                field ->
                    new net.openan.a2at.sdk.negotiation.content.NegotiationItem(
                        field,
                        field.equals("任务对象")
                            ? "请提供本地市实际接入端口名称，不能使用其他地市的端口"
                            : "请提供投诉分类及本次投诉的完整上下文，保留事件流水号"))
            .toList();
    var generated =
        a2at()
            .generateNegotiationProposePromptFromData(
                new net.openan.a2at.sdk.negotiation.content.NegotiationProposeData(
                    context,
                    new net.openan.a2at.sdk.negotiation.content.InformationProposeContent(
                        items, "AND：所列字段均为本次诊断必需，必须全部提供")),
                StandardTemplates.INFORMATION_NEGOTIATION_PROPOSE.uri());
    PendingNegotiation pending =
        new PendingNegotiation(ctx.getContextId(), context, Map.copyOf(data), List.copyOf(missing));
    if (pendingNegotiationTasks.putIfAbsent(ctx.getTaskId(), pending) != null)
      throw new IllegalStateException("Task already waiting for negotiation");
    emitter.requiresInput(
        Message.builder()
            .messageId(java.util.UUID.randomUUID().toString())
            .contextId(ctx.getContextId())
            .taskId(ctx.getTaskId())
            .role(Message.Role.ROLE_AGENT)
            .parts(List.of(new TextPart("存在信息缺失，请补充信息")))
            .metadata(generated.buildMetadataContent())
            .extensions(List.of(A2ATExtension.NEGOTIATION_T.uri()))
            .build());
    log.info(
        "[{}] Requested negotiation via INPUT_REQUIRED fields={}",
        getClass().getSimpleName(),
        missing);
  }

  /** Sample-specific semantic checks after SDK filling; no raw protocol text is inspected. */
  protected List<String> invalidTaskFields(Map<String, Object> data) {
    return SpnTaskInput.invalidFields(data);
  }

  /** Run business logic, emit artifact, and complete the task. */
  private void runBusinessAndComplete(
      RequestContext ctx, AgentEmitter emitter, SpnTaskInput input) {
    String taskId = ctx.getTaskId();
    String contextId = ctx.getContextId();
    long started = System.nanoTime();
    log.info(
        "[{}] BUSINESS_START taskId={}, contextId={}, inputChars={}",
        getClass().getSimpleName(),
        taskId,
        contextId,
        input.diagnosisInput().length());
    String response = executeBusiness(ctx, emitter, input);
    Map<String, Object> metadata = buildResponseMetadata(ctx, response);
    List<Part<?>> parts = List.of(new TextPart(response));
    emitter.addArtifact(parts, "result", buildArtifactName(), metadata, false, true);
    emitStatus(emitter, TaskState.TASK_STATE_COMPLETED, contextId, taskId, "Completed", metadata);
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
    TaskStatus status = new TaskStatus(state, buildStatusMessage(contextId, taskId, text), null);
    TaskStatusUpdateEvent event =
        TaskStatusUpdateEvent.builder()
            .taskId(taskId)
            .contextId(contextId)
            .status(status)
            .metadata(metadata)
            .build();
    emitter.emitEvent(event);
  }

  /**
   * Run the agent's actual business logic; return the response text. May emit intermediate events.
   */
  protected abstract String executeBusiness(
      RequestContext ctx, AgentEmitter emitter, SpnTaskInput input);

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

  @Override
  public void cancel(RequestContext ctx, AgentEmitter emitter) throws A2AError {
    pendingNegotiationTasks.remove(ctx.getTaskId());
    emitter.cancel();
  }

  private record NotificationEvent(String artifactName, String content) {
    private NotificationEvent {
      java.util.Objects.requireNonNull(artifactName, "artifactName");
      java.util.Objects.requireNonNull(content, "content");
    }
  }

  private record PendingNegotiation(
      String a2aContextId,
      net.openan.a2at.sdk.core.model.NegotiationContext context,
      Map<String, Object> validatedData,
      List<String> requestedFields) {}
}
