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

package dev.openan.workflow.engine.examples.negotiation;

import dev.openan.workflow.engine.client.A2ATExtension;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Negotiation-T helpers using <b>only standard A2A-T protocol fields</b> (spec §5.1
 * Message.role and §6 metadata-key conventions). No custom markers, no internal-only
 * keys, no value-content inspection.
 */
public final class NegotiationUtils {

    /** Standard Negotiation-T extension URI (spec §2). */
    public static final String NEGOTIATION_T_URI = A2ATExtension.NEGOTIATION_T.uri();

    /** Standard Task-T extension URI (spec §2). */
    public static final String TASK_PROMPT_KEY = A2ATExtension.TASK_T.uri();

    private NegotiationUtils() {}

    /**
     * Whether metadata carries the Negotiation-T extension key (key-level check). Used by both
     * client and server to detect negotiation messages generically.
     */
    public static boolean hasNegotiationMetadata(Map<String, Object> metadata) {
        if (metadata == null) return false;
        return metadata.containsKey(NEGOTIATION_T_URI);
    }

    /**
     * Whether metadata carries the Task-T extension key (key-level check). Distinguishes a new
     * diagnostic task (has Task-T) from a negotiation reply (only Negotiation-T).
     */
    public static boolean hasTaskMetadata(Map<String, Object> metadata) {
        if (metadata == null) return false;
        return metadata.containsKey(TASK_PROMPT_KEY);
    }

    /** Build the Negotiation-T metadata for an OMC-side Message(ROLE_AGENT) propose. */
    public static Map<String, Object> negotiationResponseMetadata(String negotiationText) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (negotiationText != null && !negotiationText.isEmpty()) {
            metadata.put(NEGOTIATION_T_URI, negotiationText);
        }
        return metadata;
    }

    /**
     * Metadata key carrying the JSON-serialised negotiation context map, matching the SDK demo
     * convention ({@code NegotiationPayloadMapper.contextPayload} shape: {@code negotiationType}
     * / {@code negotiationId} / {@code round} / {@code status}, all string or number values).
     *
     * <p>Propose replies must carry this key so the client-side engine can hand a well-formed
     * context to {@code receiveNegotiation} / {@code continueNegotiation}; without it the SDK
     * state machine rejects the payload ("Negotiation context field must be a string").
     */
    public static final String NEGOTIATION_CONTEXT_KEY = "negotiation_context";

    /**
     * Builds Negotiation-T propose metadata carrying the stateful context payload: the rendered
     * propose text under the extension URI plus the {@code negotiation_context} key holding the
     * SDK {@code startNegotiation} payload's context map.
     *
     * @param negotiationText rendered propose text
     * @param startPayload the {@code startNegotiation} return payload (context extracted via its
     *     Negotiation-T data key); may be null for the fallback path (context omitted)
     */
    public static Map<String, Object> negotiationResponseMetadata(
            String negotiationText, Map<String, Object> startPayload) {
        Map<String, Object> metadata = negotiationResponseMetadata(negotiationText);
        Map<String, Object> contextMap = extractStartContext(startPayload);
        if (contextMap != null && !contextMap.isEmpty()) {
            metadata.put(NEGOTIATION_CONTEXT_KEY, contextMap);
        }
        return metadata;
    }

    /** Extracts the context map from a startNegotiation payload; null when absent. */
    private static Map<String, Object> extractStartContext(Map<String, Object> startPayload) {
        if (startPayload == null) {
            return null;
        }
        // startNegotiation payload shape: { Negotiation-T URI: {message, negotiationType,
        // negotiationId, round, status, extra}, facts? } — the context fields sit in the
        // Negotiation-T entry itself, minus the message key.
        for (var entry : startPayload.entrySet()) {
            String key = entry.getKey();
            if (key.contains("Negotiation-T")
                    && !key.contains("DATA-NEGOTIATION-T")
                    && entry.getValue() instanceof Map<?, ?> data) {
                @SuppressWarnings("unchecked")
                Map<String, Object> typed = new LinkedHashMap<>((Map<String, Object>) data);
                typed.remove("message");
                if (typed.containsKey("negotiationType")) {
                    return typed;
                }
            }
        }
        return null;
    }
}
