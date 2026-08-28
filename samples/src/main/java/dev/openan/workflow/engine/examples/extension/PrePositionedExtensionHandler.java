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
package dev.openan.workflow.engine.examples.extension;

import org.a2aproject.sdk.server.agentexecution.RequestContext;
import org.a2aproject.sdk.server.tasks.AgentEmitter;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;

import net.openan.a2at.sdk.core.model.MetadataContent;
import net.openan.a2at.sdk.server.A2ATServer;
import net.openan.a2at.sdk.core.model.A2ATConfig;
import net.openan.a2at.sdk.core.model.FilledParamData;
import net.openan.a2at.sdk.core.model.StandardTemplates;
import net.openan.a2at.sdk.core.model.TemplateUri;
import net.openan.a2at.sdk.core.validation.ContentValidationException;
import java.nio.file.Path;
import java.time.Instant;
import org.a2aproject.sdk.spec.TextPart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import dev.openan.workflow.engine.examples.agents.BaseAgentExecutor;
import dev.openan.workflow.engine.client.A2ATExtension;
import dev.openan.workflow.engine.examples.util.EnvResolver;
/**
 * Handles independent Authorization-T / Notification-T messages on the agent server side.
 *
 * <p>This is a <b>separate concern</b> from Negotiation-T. Pre-positioned extensions are sent by
 * the workbench independently from workflow execution (via {@link
 * dev.openan.workflow.engine.client.ExtensionSender}) to establish whitelists and subscriptions. When an
 * agent receives one, it must:
 *
 * <ol>
 *   <li>Store the payload for later use during business execution
 *   <li>Return the protocol-defined structured response artifact under the same extension URI
 *   <li>Complete the task immediately (no negotiation, no business logic)
 * </ol>
 *
 * <p>Extracted from {@code NegotiationBaseAgentExecutor} so that the latter only carries
 * Negotiation-T responsibility.
 */
public class PrePositionedExtensionHandler {
    private static final Logger log = LoggerFactory.getLogger(PrePositionedExtensionHandler.class);
    private final AuthorizationPromptValidator authorizationValidator;
    private final NotificationPromptValidator notificationValidator;
    private volatile AuthorizationPolicy authorizationPolicy;
    private volatile String authorizationPolicyId;
    private volatile Instant authorizationCreatedAt;
    private volatile Instant authorizationModifiedAt;
    private volatile NotificationPolicy notificationSubscription;
    private volatile A2ATServer a2atServer;
    private volatile String validationLanguage = "zh-CN";

    /** Validation seam for deterministic integration tests; production uses {@link A2ATServer}. */
    @FunctionalInterface
    public interface AuthorizationPromptValidator {
        FilledParamData validate(String prompt, Map<String, Object> schema, TemplateUri templateUri);
    }

    /** Validation seam for deterministic Notification-T integration tests. */
    @FunctionalInterface
    public interface NotificationPromptValidator {
        FilledParamData validate(String prompt, Map<String, Object> schema, TemplateUri templateUri);
    }

    public PrePositionedExtensionHandler() {
        this(null, null);
    }

    public PrePositionedExtensionHandler(AuthorizationPromptValidator authorizationValidator) {
        this(authorizationValidator, null);
    }

    public PrePositionedExtensionHandler(
            AuthorizationPromptValidator authorizationValidator,
            NotificationPromptValidator notificationValidator) {
        this.authorizationValidator = authorizationValidator;
        this.notificationValidator = notificationValidator;
    }

    private synchronized A2ATServer a2atServer() {
        if (a2atServer != null) return a2atServer;
        String env = EnvResolver.resolveEnvPath();
        if (env == null || env.isBlank()) return null;
        try {
            Path envPath = Path.of(env);
            validationLanguage = A2ATConfig.load(envPath).prompt().language();
            a2atServer = new A2ATServer(envPath);
            return a2atServer;
        } catch (Exception e) {
            log.warn("A2ATServer init failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Detect whether the incoming message carries an Authorization-T or Notification-T extension in
     * its metadata. Returns the extension keyword ("Authorization-T" or "Notification-T"), or null
     * for a normal task.
     */
    public static String detect(RequestContext ctx) {
        Message msg = ctx.getMessage();
        if (msg == null || msg.metadata() == null) {
            return null;
        }
        if (msg.metadata().containsKey(A2ATExtension.AUTHORIZATION_T.uri())) {
            return A2ATExtension.AUTHORIZATION_T.uri();
        }
        if (msg.metadata().containsKey(A2ATExtension.NOTIFICATION_T.uri())) {
            return A2ATExtension.NOTIFICATION_T.uri();
        }
        return null;
    }

    private static void emitStatus(
            AgentEmitter emitter,
            TaskState state,
            String contextId,
            String taskId,
            String text,
            Map<String, Object> metadata) {
        TaskStatus status =
                new TaskStatus(
                        state, BaseAgentExecutor.buildStatusMessage(contextId, taskId, text), null);
        TaskStatusUpdateEvent event =
                TaskStatusUpdateEvent.builder()
                        .taskId(taskId)
                        .contextId(contextId)
                        .status(status)
                        .metadata(metadata)
                        .build();
        emitter.emitEvent(event);
    }

    /** The SDK-validated Authorization-T whitelist, or null when none has been accepted. */
    public AuthorizationPolicy getAuthorizationPolicy() {
        return authorizationPolicy;
    }

    /** The SDK-validated Notification-T subscription, or null. */
    public NotificationPolicy getNotificationSubscription() {
        return notificationSubscription;
    }

    private Map<String, Object> validationSchema(TemplateUri templateUri) {
        return SdkSlotSchemaLoader.load(templateUri, validationLanguage);
    }

    /**
     * Handle an independent extension message: store the payload, emit an ACK artifact, and complete the
     * task.
     *
     * @param agentTag short agent class name for logging
     */
    public void handle(
            RequestContext ctx, AgentEmitter emitter, String extKeyword, String agentTag) {
        String taskId = ctx.getTaskId();
        String contextId = ctx.getContextId();
        Object payload = ctx.getMessage().metadata().get(extKeyword);
        if (!(payload instanceof String payloadText) || payloadText.isBlank()) {
            reject(emitter, contextId, taskId, "Extension payload must be a non-empty string");
            return;
        }
        // Extract templateUri from metadata if present (sent by SDK prompt generation)
        Object templateUri = null;
        if (ctx.getMessage().metadata() != null) {
            templateUri = ctx.getMessage().metadata().get(MetadataContent.TEMPLATE_URI_METADATA_KEY);
        }
        AuthorizationOperationResult operationResult;
        if (A2ATExtension.AUTHORIZATION_T.uri().equals(extKeyword)) {
            try {
                FilledParamData filled = validateAuthorization(payloadText, templateUri);
                operationResult = applyAuthorization(filled.data(), templateUri, agentTag);
            } catch (Exception validationError) {
                log.warn(
                        "[{}] Authorization-T rejected; existing policy unchanged: {}",
                        agentTag,
                        validationFailureSummary(validationError));
                reject(emitter, contextId, taskId, "Authorization-T validation failed");
                return;
            }
        } else if (A2ATExtension.NOTIFICATION_T.uri().equals(extKeyword)) {
            try {
                acceptNotification(payloadText, templateUri, agentTag);
                operationResult =
                        new AuthorizationOperationResult(
                                "订阅成功，启动业务抢通事件上报任务",
                                "## 订阅结果\n订阅结果：成功");
            } catch (Exception validationError) {
                log.warn(
                        "[{}] Notification-T rejected; existing subscription unchanged: {}",
                        agentTag,
                        validationFailureSummary(validationError));
                reject(emitter, contextId, taskId, "Notification-T validation failed");
                return;
            }
        } else {
            reject(emitter, contextId, taskId, "Unsupported A2A-T extension URI");
            return;
        }
        log.info(
                "[{}] Pre-positioned {} received, payload length={}",
                agentTag,
                extKeyword,
                payloadText.length());
        List<Part<?>> parts = List.of(new TextPart(operationResult.text()));
        Map<String, Object> responseMetadata =
                Map.of(extKeyword, operationResult.metadataText());
        emitter.addArtifact(
                parts, "result", agentTag + " ack", responseMetadata, false, true);
        emitStatus(
                emitter,
                TaskState.TASK_STATE_COMPLETED,
                contextId,
                taskId,
                operationResult.text(),
                responseMetadata);
        emitter.complete(BaseAgentExecutor.buildStatusMessage(contextId, taskId, "Completed"));
        log.info("[{}] {} operation completed", agentTag, extKeyword);
    }

    synchronized AuthorizationOperationResult applyAuthorization(
            Map<String, Object> validatedData, Object templateUri, String agentTag) {
        String operation = AuthorizationPolicy.operationFromValidated(validatedData);
        switch (operation) {
            case AuthorizationPolicy.ADD -> {
                AuthorizationPolicy candidate = AuthorizationPolicy.fromValidated(validatedData);
                Instant now = Instant.now();
                authorizationPolicy = candidate;
                // The demo models the single policy shown in the scenario specification. A real
                // integration callback should replace this with its persisted policy identifier.
                authorizationPolicyId = "7d8c7b00-3c8c-4f8e-9b1e-9b17b6a3e5c3";
                authorizationCreatedAt = now;
                authorizationModifiedAt = now;
                log.info(
                        "[{}] Authorization-T accepted, templateUri={}, operation={}, rules={}",
                        agentTag,
                        templateUri,
                        operation,
                        candidate.rules().size());
                return authorizationResult("新增动网操作的授权成功", candidate);
            }
            case AuthorizationPolicy.MODIFY -> throw new UnsupportedOperationException(
                    "The SPN sample has no persisted policy identifiers; Authorization-T modify "
                            + "must be implemented by the integrating policy store");
            case AuthorizationPolicy.DELETE -> {
                String selector = AuthorizationPolicy.requirePolicyList(validatedData);
                if (authorizationPolicy == null || !selector.equals(authorizationPolicyId)) {
                    throw new IllegalArgumentException(
                            "Authorization policy identifier does not match the active policy");
                }
                authorizationPolicy = null;
                authorizationPolicyId = null;
                authorizationCreatedAt = null;
                authorizationModifiedAt = null;
                log.info(
                        "[{}] Authorization-T accepted, templateUri={}, operation={}, active policy cleared",
                        agentTag,
                        templateUri,
                        operation);
                return new AuthorizationOperationResult(
                        "删除动网操作的授权成功",
                        "## 授权操作执行结果\n授权操作执行结果：成功");
            }
            case AuthorizationPolicy.QUERY -> {
                log.info(
                        "[{}] Authorization-T accepted, templateUri={}, operation={}, activeRules={}",
                        agentTag,
                        templateUri,
                        operation,
                        authorizationPolicy == null ? 0 : authorizationPolicy.rules().size());
                return authorizationResult(
                        "符合查询条件的动网操作的授权策略列表", authorizationPolicy);
            }
            default -> throw new IllegalArgumentException(
                    "Unsupported Authorization-T operation: " + operation);
        }
    }

    private AuthorizationOperationResult authorizationResult(
            String text, AuthorizationPolicy policy) {
        StringBuilder metadata =
                new StringBuilder("## 授权操作执行结果\n授权操作执行结果：成功");
        metadata.append("\n\n## 动网操作的授权策略列表\n");
        if (policy == null) {
            metadata.append("（无匹配授权策略）");
        } else {
            int index = 1;
            for (AuthorizationPolicy.Rule rule : policy.rules()) {
                metadata.append("- 动网操作的授权策略").append(index++).append('\n');
                metadata.append("  - 动网操作的授权策略标识：")
                        .append(authorizationPolicyId)
                        .append('\n');
                metadata.append("  - 动网操作的业务场景：")
                        .append(rule.businessScenario())
                        .append('\n');
                metadata.append("  - 动网操作的处置类型：")
                        .append(rule.disposalType())
                        .append('\n');
                metadata.append("  - 动网操作名称：").append(rule.actionName()).append('\n');
                metadata.append("  - 有效期：")
                        .append(formatValidity(rule))
                        .append('\n');
                metadata.append("  - 创建时间：")
                        .append(authorizationCreatedAt)
                        .append('\n');
                metadata.append("  - 最后修改时间：")
                        .append(authorizationModifiedAt);
            }
        }
        return new AuthorizationOperationResult(text, metadata.toString());
    }

    private static String formatValidity(AuthorizationPolicy.Rule rule) {
        if (java.time.LocalDate.MIN.equals(rule.validFrom())
                && java.time.LocalDate.MAX.equals(rule.validUntil())) {
            return "永久生效";
        }
        return rule.validFrom() + " ~ " + rule.validUntil();
    }

    record AuthorizationOperationResult(String text, String metadataText) {
        AuthorizationOperationResult {
            Objects.requireNonNull(text, "Authorization response text");
            Objects.requireNonNull(metadataText, "Authorization response metadata");
        }
    }

    private FilledParamData validateAuthorization(String prompt, Object receivedTemplateUri) {
        FilledParamData filled;
        if (authorizationValidator != null) {
            filled = Objects.requireNonNull(
                    authorizationValidator.validate(
                            prompt,
                            validationSchema(StandardTemplates.AUTHORIZATION_POLICY_MANAGEMENT),
                            StandardTemplates.AUTHORIZATION_POLICY_MANAGEMENT),
                    "Authorization validator returned null");
        } else {
            requireTemplateUri(
                    receivedTemplateUri, StandardTemplates.AUTHORIZATION_POLICY_MANAGEMENT);
            A2ATServer server = a2atServer();
            if (server == null) {
                throw new IllegalStateException("A2A-T server validator is unavailable");
            }
            try {
                filled = validateAuthorizationWithSdk(server, prompt);
            } catch (ContentValidationException firstFailure) {
                // The current SDK validation pipeline may use an external LLM even for a canonical
                // prompt rendered from structured data. Retry only the side-effect-free validation
                // stage once; business state is not touched until a validated result is returned.
                log.warn(
                        "Authorization-T SDK validation failed once; retrying the same canonical prompt: {}",
                        validationFailureSummary(firstFailure));
                filled = validateAuthorizationWithSdk(server, prompt);
            }
        }
        requireExtractedSectionsMatch(
                prompt,
                filled.data(),
                AuthorizationPolicy.OPERATION_TYPE_FIELD,
                AuthorizationPolicy.POLICY_LIST_FIELD);
        return filled;
    }

    private FilledParamData validateAuthorizationWithSdk(A2ATServer server, String prompt) {
        return server.validateAuthPromptAndDataFilling(
                prompt,
                validationSchema(StandardTemplates.AUTHORIZATION_POLICY_MANAGEMENT),
                StandardTemplates.AUTHORIZATION_POLICY_MANAGEMENT);
    }

    /** Validates and stores a Notification-T subscription before the server opens its SSE loop. */
    public NotificationPolicy acceptNotification(
            String prompt, Object receivedTemplateUri, String agentTag) {
        FilledParamData filled;
        if (notificationValidator != null) {
            filled = Objects.requireNonNull(
                    notificationValidator.validate(
                            prompt,
                            validationSchema(StandardTemplates.SERVICE_RECOVERY),
                            StandardTemplates.SERVICE_RECOVERY),
                    "Notification validator returned null");
        } else {
            requireTemplateUri(receivedTemplateUri, StandardTemplates.SERVICE_RECOVERY);
            A2ATServer server = a2atServer();
            if (server == null) {
                throw new IllegalStateException("A2A-T server validator is unavailable");
            }
            filled = server.validateNotificationPromptAndDataFilling(
                    prompt,
                    validationSchema(StandardTemplates.SERVICE_RECOVERY),
                    StandardTemplates.SERVICE_RECOVERY);
        }
        requireExtractedSectionsMatch(
                prompt,
                filled.data(),
                NotificationPolicy.CONDITION_FIELD,
                NotificationPolicy.REPORT_FORMAT_FIELD);
        NotificationPolicy candidate = NotificationPolicy.fromValidated(filled.data());
        notificationSubscription = candidate;
        log.info(
                "[{}] Notification-T accepted, templateUri={}, conditionChars={}, reportFormatChars={}",
                agentTag,
                receivedTemplateUri,
                candidate.condition().length(),
                candidate.reportFormat().length());
        return candidate;
    }

    static void requireExtractedSectionsMatch(
            String renderedPrompt, Map<String, Object> extractedData, String... slotNames) {
        Objects.requireNonNull(renderedPrompt, "Rendered A2A-T prompt is required");
        Objects.requireNonNull(extractedData, "SDK extracted parameters are required");
        for (String slotName : slotNames) {
            String renderedValue = sectionValue(renderedPrompt, slotName);
            Object extractedValue = extractedData.get(slotName);
            String extractedText = extractedValue instanceof String text ? text : "";
            if (!normalizeSlotValue(renderedValue).equals(normalizeSlotValue(extractedText))) {
                throw new IllegalArgumentException(
                        "SDK extracted value does not match rendered prompt section: " + slotName);
            }
        }
    }

    private static String sectionValue(String prompt, String slotName) {
        String[] lines = prompt.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        String expectedHeading = "## " + slotName;
        for (int index = 0; index < lines.length; index++) {
            if (!expectedHeading.equals(lines[index].strip())) {
                continue;
            }
            StringBuilder value = new StringBuilder();
            for (int valueIndex = index + 1; valueIndex < lines.length; valueIndex++) {
                String line = lines[valueIndex];
                if (line.strip().startsWith("## ")) {
                    break;
                }
                if (!line.isBlank()) {
                    if (!value.isEmpty()) {
                        value.append('\n');
                    }
                    value.append(line.strip());
                }
            }
            return value.toString();
        }
        throw new IllegalArgumentException(
                "Rendered A2A-T prompt is missing section: "
                        + slotName
                        + "; headings="
                        + java.util.Arrays.stream(lines)
                                .map(String::strip)
                                .filter(line -> line.startsWith("## "))
                                .toList());
    }

    private static String normalizeSlotValue(String value) {
        return value == null ? "" : value.strip().replaceAll("\\s+", " ");
    }

    private static void requireTemplateUri(Object actualValue, TemplateUri expected) {
        String actual = actualValue instanceof String text && !text.isBlank() ? text : null;
        if (!expected.uri().equals(actual)) {
            throw new IllegalArgumentException(
                    "Unexpected template URI: expected=" + expected.uri() + ", actual=" + actual);
        }
    }

    private static String validationFailureSummary(Exception error) {
        if (error instanceof ContentValidationException validationError) {
            return "code="
                    + validationError.getCode()
                    + ", slotErrors="
                    + validationError.errors()
                    + ", partialParamKeys="
                    + validationError.params().keySet();
        }
        return error.getClass().getSimpleName() + ": " + error.getMessage();
    }

    private static void reject(
            AgentEmitter emitter, String contextId, String taskId, String publicMessage) {
        emitStatus(
                emitter,
                TaskState.TASK_STATE_FAILED,
                contextId,
                taskId,
                publicMessage,
                Map.of());
        emitter.fail(BaseAgentExecutor.buildStatusMessage(contextId, taskId, publicMessage));
    }
}
