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

    /**
     * Metadata key carrying the negotiation session context map ({@code id} / {@code round} /
     * {@code maxRounds} / {@code performative}).
     *
     * <p>Propose replies must carry this key so the client-side engine can parse the session
     * context and advance rounds itself — the content layer is stateless and the caller owns
     * the session state (SDK guide §1.10).
     */
    public static final String NEGOTIATION_CONTEXT_KEY =
            net.openan.a2at.sdk.core.model.MetadataContent.NEGOTIATION_CONTEXT_METADATA_KEY;

}
