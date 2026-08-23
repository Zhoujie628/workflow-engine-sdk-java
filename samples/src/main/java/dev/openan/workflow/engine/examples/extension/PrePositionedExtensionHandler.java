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
import net.openan.a2at.sdk.server.model.PromptComplianceResult;
import java.nio.file.Path;
import org.a2aproject.sdk.spec.TextPart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.openan.workflow.engine.examples.agents.BaseAgentExecutor;
import dev.openan.workflow.engine.examples.agents.NegotiationBaseAgentExecutor;
/**
 * Handles pre-positioned Authorization-T / Notification-T messages on the agent server side.
 *
 * <p>This is a <b>separate concern</b> from Negotiation-T. Pre-positioned extensions are sent by
 * the orchestrator <i>before</i> the workflow starts (via {@code
 * WorkflowEngineClient.sendExtensionMessage}) to establish whitelists and subscriptions. When an
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
    private volatile String authorizationPolicy;
    private volatile String notificationSubscription;
    private volatile A2ATServer a2atServer;

    private A2ATServer a2atServer() {
        if (a2atServer != null) return a2atServer;
        if (Boolean.getBoolean("a2at.llm.disabled")) return null;
        String env = System.getProperty("A2AT_ENV_PATH", System.getenv("A2AT_ENV_PATH"));
        if (env == null || env.isBlank()) return null;
        try {
            a2atServer = new A2ATServer(Path.of(env));
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
        for (String key : msg.metadata().keySet()) {
            if (key.contains("Authorization-T")) {
                return "Authorization-T";
            }
            if (key.contains("Notification-T")) {
                return "Notification-T";
            }
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

    /** The pre-positioned Authorization-T whitelist policy text, or null. */
    public String getAuthorizationPolicy() {
        return authorizationPolicy;
    }

    /** The pre-positioned Notification-T subscription text, or null. */
    public String getNotificationSubscription() {
        return notificationSubscription;
    }

    /**
     * Parameter JSON Schema for the Authorization-T validate-and-fill pipeline: the whitelist
     * policy fields the agent wants extracted from a rendered authorization prompt.
     */
    private static Map<String, Object> buildAuthParamSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("授权策略的操作类型", Map.of("type", "string"));
        properties.put("动网操作的授权策略列表", Map.of("type", "string"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        return schema;
    }

    /**
     * Parameter JSON Schema for the Notification-T validate-and-fill pipeline: the subscription
     * fields extracted from a rendered subscribe-incident prompt.
     */
    private static Map<String, Object> buildNotificationParamSchema() {
        // Slots of the SDK's Notification-T service-recovery template (订阅条件 + 上报通知数据格式).
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("订阅条件", Map.of("type", "string"));
        properties.put("上报通知数据格式", Map.of("type", "string"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        return schema;
    }

    /**
     * Handle a pre-positioned message: store the payload, emit an ACK artifact, and complete the
     * task.
     *
     * @param agentTag short agent class name for logging
     */
    public void handle(
            RequestContext ctx, AgentEmitter emitter, String extKeyword, String agentTag) {
        String taskId = ctx.getTaskId();
        String contextId = ctx.getContextId();
        String payloadText =
                ctx.getMessage().metadata().entrySet().stream()
                        .filter(e -> e.getKey().contains(extKeyword))
                        .map(Map.Entry::getValue)
                        .findFirst()
                        .map(v -> v instanceof String s ? s : String.valueOf(v))
                        .orElse("");
        // Extract templateUri from metadata if present (sent by SDK prompt generation)
        String templateUri = null;
        if (ctx.getMessage().metadata() != null) {
            Object tu = ctx.getMessage().metadata().get(MetadataContent.TEMPLATE_URI_METADATA_KEY);
            if (tu instanceof String s) templateUri = s;
        }
        if (extKeyword.contains("Authorization")) {
            authorizationPolicy = payloadText;
            // Full validate-and-fill pipeline: rule gate + LLM semantic validation + param
            // extraction, falling back to the lightweight compliance check.
            A2ATServer server = a2atServer();
            if (server != null) {
                try {
                    net.openan.a2at.sdk.core.model.FilledParamData filled =
                            server.validateAuthPromptAndDataFilling(
                                    payloadText,
                                    buildAuthParamSchema(),
                                    net.openan.a2at.sdk.core.model.StandardTemplates.AUTHORIZATION_POLICY_MANAGEMENT);
                    log.info(
                            "[{}] Authorization-T validateAuthPromptAndDataFilling passed, templateUri={}, params={}",
                            agentTag,
                            templateUri,
                            filled.data() != null ? filled.data().keySet() : java.util.Set.of());
                } catch (Exception ve) {
                    log.warn(
                            "[{}] Authorization-T validate-and-fill rejected ({}); falling back to compliance check",
                            agentTag,
                            ve.getMessage());
                    try {
                        PromptComplianceResult result = server.checkTaskPrompt(payloadText);
                        if (result.success()) {
                            log.info("[{}] Authorization-T prompt compliance check passed, templateUri={}",
                                    agentTag, templateUri);
                        } else {
                            log.warn("[{}] Authorization-T prompt compliance check failed: code={}, message={}",
                                    agentTag,
                                    result.failure() != null ? result.failure().code() : "unknown",
                                    result.failure() != null ? result.failure().message() : "unknown");
                        }
                    } catch (Exception e) {
                        log.warn("[{}] Authorization-T compliance check error: {}", agentTag, e.getMessage());
                    }
                }
            }
        } else if (extKeyword.contains("Notification")) {
            notificationSubscription = payloadText;
            A2ATServer server = a2atServer();
            if (server != null) {
                try {
                    net.openan.a2at.sdk.core.model.FilledParamData filled =
                            server.validateNotificationPromptAndDataFilling(
                                    payloadText,
                                    buildNotificationParamSchema(),
                                    net.openan.a2at.sdk.core.model.StandardTemplates
                                            .SERVICE_RECOVERY);
                    log.info(
                            "[{}] Notification-T validateNotificationPromptAndDataFilling"
                                    + " passed, templateUri={}, params={}",
                            agentTag,
                            templateUri,
                            filled.data() != null ? filled.data().keySet() : java.util.Set.of());
                } catch (Exception ve) {
                    log.warn(
                            "[{}] Notification-T validate-and-fill rejected ({}); keeping payload as subscription",
                            agentTag,
                            ve.getMessage());
                }
            } else {
                log.info("[{}] Notification-T received, templateUri={}", agentTag, templateUri);
            }
        }
        log.info(
                "[{}] Pre-positioned {} received, payload length={}",
                agentTag,
                extKeyword,
                payloadText.length());
        String ackText = extKeyword + " pre-positioning acknowledged";
        List<Part<?>> parts = List.of(new TextPart(ackText));
        emitter.addArtifact(parts, "result", agentTag + " ack", Map.of(), false, true);
        emitStatus(
                emitter,
                TaskState.TASK_STATE_COMPLETED,
                contextId,
                taskId,
                extKeyword + " pre-positioned successfully",
                Map.of());
        emitter.complete(BaseAgentExecutor.buildStatusMessage(contextId, taskId, "Completed"));
        log.info("[{}] {} pre-positioning completed", agentTag, extKeyword);
    }
}
