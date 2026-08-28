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

package dev.openan.workflow.engine.model;

import net.openan.a2at.sdk.core.model.NegotiationPerformative;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable business view of an INPUT_REQUIRED Negotiation-T request.
 *
 * @param agentName agent requesting negotiation
 * @param concern rendered concern or question
 * @param sessionId negotiation session identifier
 * @param round current negotiation round
 * @param maxRounds negotiated round limit
 * @param performative communicative intent carried by the received context
 * @param kind semantic negotiation kind inferred from the current SDK template
 * @param templateUri received propose template URI
 * @param parameters immutable business parameters extracted by the SDK validate-and-fill pipeline
 * @param metadata immutable received metadata for application-specific inspection
 */
public record NegotiationRequest(
        String agentName,
        String concern,
        String sessionId,
        int round,
        int maxRounds,
        NegotiationPerformative performative,
        Kind kind,
        String templateUri,
        Map<String, Object> parameters,
        Map<String, Object> metadata) {

    /** Negotiation domains supported by the current A2A-T SDK. */
    public enum Kind {
        INFORMATION,
        TARGET,
        FEASIBILITY
    }

    /** Validates and defensively copies the request. */
    public NegotiationRequest {
        if (agentName == null || agentName.isBlank()) {
            throw new IllegalArgumentException("Negotiation agentName must not be blank");
        }
        concern = concern == null ? "" : concern;
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("Negotiation sessionId must not be blank");
        }
        if (round < 1 || maxRounds < 1 || round > maxRounds) {
            throw new IllegalArgumentException(
                    "Negotiation rounds must satisfy 1 <= round <= maxRounds");
        }
        performative = Objects.requireNonNull(performative, "Negotiation performative is required");
        if (performative != NegotiationPerformative.PROPOSE) {
            throw new IllegalArgumentException(
                    "Negotiation callback requires a PROPOSE performative, got " + performative);
        }
        if (kind == null) {
            throw new IllegalArgumentException("Negotiation kind is required");
        }
        if (templateUri == null || templateUri.isBlank()) {
            throw new IllegalArgumentException("Negotiation templateUri must not be blank");
        }
        parameters = immutableCopy(parameters);
        metadata = immutableCopy(metadata);
    }

    private static Map<String, Object> immutableCopy(Map<String, Object> values) {
        return values == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }
}
