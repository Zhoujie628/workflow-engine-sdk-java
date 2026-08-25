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
 *   <li>Acknowledge receipt with a short artifact
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
        if (A2ATExtension.AUTHORIZATION_T.uri().equals(extKeyword)) {
            try {
                FilledParamData filled = validateAuthorization(payloadText, templateUri);
                applyAuthorization(filled.data(), templateUri, agentTag);
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
        String ackText = extKeyword + " operation acknowledged";
        List<Part<?>> parts = List.of(new TextPart(ackText));
        emitter.addArtifact(parts, "result", agentTag + " ack", Map.of(), false, true);
        emitStatus(
                emitter,
                TaskState.TASK_STATE_COMPLETED,
                contextId,
                taskId,
                extKeyword + " operation applied successfully",
                Map.of());
        emitter.complete(BaseAgentExecutor.buildStatusMessage(contextId, taskId, "Completed"));
        log.info("[{}] {} operation completed", agentTag, extKeyword);
    }

    synchronized void applyAuthorization(
            Map<String, Object> validatedData, Object templateUri, String agentTag) {
        String operation = AuthorizationPolicy.operationFromValidated(validatedData);
        switch (operation) {
            case AuthorizationPolicy.ADD, AuthorizationPolicy.MODIFY -> {
                AuthorizationPolicy candidate = AuthorizationPolicy.fromValidated(validatedData);
                authorizationPolicy = candidate;
                log.info(
                        "[{}] Authorization-T accepted, templateUri={}, operation={}, rules={}",
                        agentTag,
                        templateUri,
                        operation,
                        candidate.rules().size());
            }
            case AuthorizationPolicy.DELETE -> {
                // This sample models one active whitelist per OMC agent. A production callback
                // should use the validated selector/list to delete matching persisted policies.
                AuthorizationPolicy.requirePolicyList(validatedData);
                authorizationPolicy = null;
                log.info(
                        "[{}] Authorization-T accepted, templateUri={}, operation={}, active policy cleared",
                        agentTag,
                        templateUri,
                        operation);
            }
            case AuthorizationPolicy.QUERY ->
                    // Query is side-effect free. The current sample returns the normal protocol ACK;
                    // an integration callback can render its persisted policies in the artifact.
                    log.info(
                            "[{}] Authorization-T accepted, templateUri={}, operation={}, activeRules={}",
                            agentTag,
                            templateUri,
                            operation,
                            authorizationPolicy == null ? 0 : authorizationPolicy.rules().size());
            default -> throw new IllegalArgumentException(
                    "Unsupported Authorization-T operation: " + operation);
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
            filled = server.validateAuthPromptAndDataFilling(
                    prompt,
                    validationSchema(StandardTemplates.AUTHORIZATION_POLICY_MANAGEMENT),
                    StandardTemplates.AUTHORIZATION_POLICY_MANAGEMENT);
        }
        requireExtractedSectionsMatch(
                prompt,
                filled.data(),
                AuthorizationPolicy.OPERATION_TYPE_FIELD,
                AuthorizationPolicy.POLICY_LIST_FIELD);
        return filled;
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
        String actual = null;
        if (actualValue instanceof TemplateUri template) {
            actual = template.uri();
        } else if (actualValue instanceof String text) {
            actual = text;
        } else if (actualValue instanceof Map<?, ?> map) {
            Object uri = map.get("uri");
            if (uri instanceof String text) {
                actual = text;
            } else {
                Object extension = map.get("extensionName");
                Object segments = map.get("pathSegments");
                Object version = map.get("templateVersion");
                if (extension instanceof String extensionName
                        && segments instanceof List<?> pathSegments
                        && version instanceof String templateVersion) {
                    actual = String.join(
                            "/",
                            java.util.stream.Stream.concat(
                                            java.util.stream.Stream.of(extensionName),
                                            java.util.stream.Stream.concat(
                                                    pathSegments.stream().map(String::valueOf),
                                                    java.util.stream.Stream.of(templateVersion)))
                                    .toList());
                }
            }
        }
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
