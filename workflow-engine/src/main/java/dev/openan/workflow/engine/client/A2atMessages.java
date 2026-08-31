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

package dev.openan.workflow.engine.client;

import dev.openan.workflow.engine.model.MessageContent;
import dev.openan.workflow.engine.model.ReceivedMessage;
import net.openan.a2at.sdk.core.model.MetadataContent;
import net.openan.a2at.sdk.core.model.NegotiationContext;
import net.openan.a2at.sdk.core.model.NegotiationPerformative;
import org.a2aproject.sdk.spec.Part;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Thin A2A-T core conversion. Does not generate, validate or interpret business content. */
public final class A2atMessages {
    private A2atMessages() {}

    /** Preserves SDK metadata and activates only this generated extension; parts stay explicit. */
    public static MessageContent from(MetadataContent generated, List<Part<?>> parts) {
        Objects.requireNonNull(generated, "generated");
        return new MessageContent(parts, generated.buildMetadataContent(), Set.of(generated.extensionUri()));
    }

    /** Reads the canonical context, rejecting conflicting contexts instead of flattening metadata. */
    public static NegotiationContext contextOf(ReceivedMessage received) {
        Objects.requireNonNull(received, "received");
        List<Map<String, Object>> layers = new ArrayList<>();
        if (received.message() != null) layers.add(received.message().metadata());
        layers.add(received.taskMetadata());
        received.artifacts().forEach(a -> { if (a.metadata() != null) layers.add(a.metadata()); });
        NegotiationContext context = null;
        for (Map<String, Object> layer : layers) {
            if (!layer.containsKey(MetadataContent.NEGOTIATION_CONTEXT_METADATA_KEY)) continue;
            NegotiationContext candidate = contextOf(layer);
            if (context != null && !context.equals(candidate)) {
                throw new IllegalArgumentException("Conflicting negotiation contexts in response");
            }
            context = candidate;
        }
        if (context == null) throw new IllegalArgumentException("Missing negotiationContext");
        return context;
    }

    /** Reads canonical wire fields only, without coercing missing or fractional rounds. */
    static NegotiationContext contextOf(Map<String, Object> metadata) {
        Object raw = metadata.get(MetadataContent.NEGOTIATION_CONTEXT_METADATA_KEY);
        if (!(raw instanceof Map<?, ?> fields)
                || !(fields.get("id") instanceof String id)
                || !(fields.get("performative") instanceof String action)) {
            throw new IllegalArgumentException("Invalid negotiationContext");
        }
        return new NegotiationContext(id, integer(fields.get("round")), integer(fields.get("maxRounds")),
                NegotiationPerformative.tryParse(action)
                        .orElseThrow(() -> new IllegalArgumentException("Invalid negotiation performative")));
    }

    private static int integer(Object value) {
        if (!(value instanceof Number)) throw new IllegalArgumentException("Negotiation round must be an integer");
        try {
            return new BigDecimal(value.toString()).intValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            throw new IllegalArgumentException("Negotiation round must be an integer", e);
        }
    }
}
