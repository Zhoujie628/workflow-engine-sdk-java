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

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.client.MessageEvent;
import org.a2aproject.sdk.client.TaskEvent;
import org.a2aproject.sdk.client.TaskUpdateEvent;
import org.a2aproject.sdk.spec.MessageSendParams;
import org.a2aproject.sdk.spec.TaskArtifactUpdateEvent;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Logs A2A protocol messages for controlled protocol-level diagnostics. Uses a dedicated
 * "PROTOCOL" logger so output can be independently enabled or suppressed. Full payloads are only
 * emitted at DEBUG and body logging is opt-in because Task-T and extension metadata can contain
 * customer or network data.
 *
 * <p>Request side: serializes {@link MessageSendParams} to pretty-printed JSON. Sensitive headers
 * are redacted unless {@code WORKFLOW_ENGINE_PROTOCOL_INCLUDE_SENSITIVE_HEADERS=true} is explicitly
 * configured.
 *
 * <p>Response side: serializes each {@link ClientEvent} payload (Task, TaskStatusUpdateEvent,
 * TaskArtifactUpdateEvent, Message) to JSON.
 */
final class ProtocolLogger {

    private static final Logger log = LoggerFactory.getLogger("PROTOCOL");
    private static final String INCLUDE_SENSITIVE_HEADERS =
            "WORKFLOW_ENGINE_PROTOCOL_INCLUDE_SENSITIVE_HEADERS";
    private static final String INCLUDE_BODY = "WORKFLOW_ENGINE_PROTOCOL_INCLUDE_BODY";
    private static final String MAX_BODY_CHARS = "WORKFLOW_ENGINE_PROTOCOL_MAX_BODY_CHARS";
    private static final int DEFAULT_MAX_BODY_CHARS = 100_000;
    private static final Set<String> SENSITIVE_HEADERS =
            Set.of(
                    "authorization",
                    "proxy-authorization",
                    "cookie",
                    "set-cookie",
                    "x-api-key",
                    "api-key");
    private static final AtomicBoolean sensitiveWarningLogged = new AtomicBoolean();

    private static final ObjectMapper mapper =
            new ObjectMapper()
                    .enable(SerializationFeature.INDENT_OUTPUT)
                    .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
                    .setDefaultPropertyInclusion(JsonInclude.Include.NON_NULL)
                    .registerModule(
                            new SimpleModule()
                                    .addSerializer(
                                            OffsetDateTime.class, ToStringSerializer.instance));

    private ProtocolLogger() {}

    /**
     * Log the full request (headers + body) before sending to an agent.
     *
     * @param agentName target agent display name
     * @param endpoint agent URL
     * @param params the message send parameters (protocol body)
     * @param headers HTTP headers from ClientCallContext
     */
    static void logRequest(
            String agentName,
            String endpoint,
            MessageSendParams params,
            Map<String, String> headers) {
        if (!log.isDebugEnabled()) {
            return;
        }
        try {
            String bodyJson = formatBody(mapper.writeValueAsString(params));
            log.debug(
                    ">>> [{}] REQUEST to {}\n=== Headers ===\n{}\n=== Body ===\n{}",
                    agentName,
                    endpoint,
                    formatHeaders(headers),
                    bodyJson);
        } catch (Exception e) {
            log.warn(">>> [{}] Failed to serialize request: {}", agentName, e.getMessage());
        }
    }

    /**
     * Log each response event (full payload) received from an agent.
     *
     * @param agentName source agent display name
     * @param event the received client event
     */
    static void logResponseEvent(String agentName, ClientEvent event) {
        if (!log.isDebugEnabled()) {
            return;
        }
        try {
            Object payload = extractPayload(event);
            String eventType = event.getClass().getSimpleName();
            if (payload == null) {
                log.debug(
                        "<<< [{}] RESPONSE [{}]: (no serializable payload)", agentName, eventType);
                return;
            }
            String json = formatBody(mapper.writeValueAsString(payload));
            log.debug("<<< [{}] RESPONSE [{}]\n{}", agentName, eventType, json);
        } catch (Exception e) {
            log.warn("<<< [{}] Failed to serialize response event: {}", agentName, e.getMessage());
        }
    }

    /**
     * Extract the serializable protocol payload from a ClientEvent. Returns the inner SDK spec
     * object (Task, TaskStatusUpdateEvent, TaskArtifactUpdateEvent, or Message) rather than the
     * event wrapper.
     */
    private static Object extractPayload(ClientEvent event) {
        if (event instanceof TaskEvent te) {
            return te.getTask();
        }
        if (event instanceof TaskUpdateEvent tue) {
            if (tue.getUpdateEvent() instanceof TaskStatusUpdateEvent sue) {
                return sue;
            }
            if (tue.getUpdateEvent() instanceof TaskArtifactUpdateEvent ae) {
                return ae;
            }
            return tue.getTask();
        }
        if (event instanceof MessageEvent me) {
            return me.getMessage();
        }
        return null;
    }

    /** Format headers map as "Key: Value" lines for readable logging. */
    static String formatHeaders(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return "(none)";
        }
        boolean includeSensitive = booleanSetting(INCLUDE_SENSITIVE_HEADERS, false);
        if (includeSensitive && sensitiveWarningLogged.compareAndSet(false, true)) {
            log.warn(
                    "PROTOCOL sensitive-header logging is enabled; protect logs and disable it outside controlled troubleshooting");
        }
        StringBuilder sb = new StringBuilder();
        headers.forEach(
                (k, v) -> {
                    String normalized = k.toLowerCase(Locale.ROOT);
                    boolean sensitive =
                            SENSITIVE_HEADERS.contains(normalized)
                                    || normalized.contains("token")
                                    || normalized.contains("secret");
                    sb.append(k)
                            .append(": ")
                            .append(sensitive && !includeSensitive ? "***" : v)
                            .append("\n");
                });
        return sb.toString().trim();
    }

    private static String formatBody(String body) {
        if (!booleanSetting(INCLUDE_BODY, false)) return "(body logging disabled)";
        int maxChars = intSetting(MAX_BODY_CHARS, DEFAULT_MAX_BODY_CHARS);
        if (body.length() <= maxChars) return body;
        return body.substring(0, maxChars)
                + "\n... (truncated, originalChars="
                + body.length()
                + ")";
    }

    private static boolean booleanSetting(String name, boolean defaultValue) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : Boolean.parseBoolean(value);
    }

    private static int intSetting(String name, int defaultValue) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) value = System.getenv(name);
        if (value == null || value.isBlank()) return defaultValue;
        try {
            int parsed = Integer.parseInt(value);
            return parsed > 0 ? parsed : defaultValue;
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }
}
