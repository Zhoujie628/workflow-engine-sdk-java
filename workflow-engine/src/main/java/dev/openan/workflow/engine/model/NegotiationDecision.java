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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Strongly typed business decision for one Negotiation-T round.
 *
 * <p>Text decisions use the SDK's fromText APIs. Structured decisions use its deterministic typed
 * fromData APIs. This replaces control strings such as {@code data:}, {@code reject:}, and {@code
 * abort:}.
 *
 * @param action protocol outcome selected by the business
 * @param input natural-language or structured decision input
 */
public record NegotiationDecision(Action action, Input input) {

    /** Supported terminal decisions for a received proposal. */
    public enum Action {
        ACCEPT,
        REJECT,
        ABORT
    }

    /** Creates an Accept decision from natural language. */
    public static NegotiationDecision acceptText(String text) {
        return new NegotiationDecision(Action.ACCEPT, new NaturalLanguage(text));
    }

    /** Creates an Accept decision from deterministic structured values. */
    public static NegotiationDecision acceptData(Map<String, ?> values) {
        return new NegotiationDecision(Action.ACCEPT, new StructuredData(normalize(values)));
    }

    /** Creates a Reject decision from natural language. */
    public static NegotiationDecision rejectText(String text) {
        return new NegotiationDecision(Action.REJECT, new NaturalLanguage(text));
    }

    /** Creates a Reject decision from deterministic structured values. */
    public static NegotiationDecision rejectData(Map<String, ?> values) {
        return new NegotiationDecision(Action.REJECT, new StructuredData(normalize(values)));
    }

    /** Creates an Abort decision whose reason is extracted by the SDK from natural language. */
    public static NegotiationDecision abortText(String text) {
        return new NegotiationDecision(Action.ABORT, new NaturalLanguage(text));
    }

    /** Creates a deterministic typed Abort decision. */
    public static NegotiationDecision abortData(String terminationReason) {
        return new NegotiationDecision(
                Action.ABORT,
                new StructuredData(normalize(Map.of("terminationReason", terminationReason))));
    }

    /** Validates the selected action and input. */
    public NegotiationDecision {
        action = Objects.requireNonNull(action, "Negotiation action is required");
        input = Objects.requireNonNull(input, "Negotiation input is required");
    }

    /** Supported business input forms for negotiation content generation. */
    public sealed interface Input permits NaturalLanguage, StructuredData {}

    /** Natural-language decision input. */
    public record NaturalLanguage(String text) implements Input {
        /** Rejects blank decision text before invoking the SDK. */
        public NaturalLanguage {
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException("Negotiation text must not be blank");
            }
        }
    }

    /** Structured decision values for the SDK typed fromData path. */
    public record StructuredData(Map<String, String> values) implements Input {
        /** Validates names and values and creates an immutable copy. */
        public StructuredData {
            Objects.requireNonNull(values, "Negotiation structured values are required");
            if (values.isEmpty()) {
                throw new IllegalArgumentException("Negotiation structured values must not be empty");
            }
            values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
        }
    }

    private static Map<String, String> normalize(Map<String, ?> values) {
        Objects.requireNonNull(values, "Negotiation structured values are required");
        Map<String, String> copy = new LinkedHashMap<>();
        values.forEach(
                (name, rawValue) -> {
                    String normalizedName = name == null ? "" : name.strip();
                    String normalizedValue = rawValue == null ? "" : rawValue.toString().strip();
                    if (normalizedName.isEmpty()
                            || normalizedValue.isEmpty()
                            || "null".equalsIgnoreCase(normalizedValue)) {
                        throw new IllegalArgumentException(
                                "Negotiation values require non-blank names and values");
                    }
                    copy.put(normalizedName, normalizedValue);
                });
        return copy;
    }
}
