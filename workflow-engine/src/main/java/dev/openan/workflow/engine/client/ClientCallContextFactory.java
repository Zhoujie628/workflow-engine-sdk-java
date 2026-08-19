/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.openan.workflow.engine.client;

import org.a2aproject.sdk.client.transport.spi.interceptors.ClientCallContext;
import org.a2aproject.sdk.spec.AgentCard;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Builds the final outbound call context from independent header contributors. */
final class ClientCallContextFactory {
    private final List<HeaderContributor> contributors;

    ClientCallContextFactory(HeaderContributor... contributors) {
        this.contributors = List.of(contributors);
    }

    ClientCallContext create(
            AgentCard agentCard, String agentName, Map<String, Object> messageMetadata) {
        Map<String, String> headers = new HashMap<>();
        for (HeaderContributor contributor : contributors) {
            Map<String, String> contributed =
                    contributor.contribute(agentCard, agentName, messageMetadata, headers);
            mergeHeaders(agentName, headers, contributed);
        }
        return new ClientCallContext(new HashMap<>(), headers);
    }

    static void mergeHeaders(
            String agentName,
            Map<String, String> destination,
            Map<String, String> additionalHeaders) {
        for (Map.Entry<String, String> entry : additionalHeaders.entrySet()) {
            String existingKey =
                    destination.keySet().stream()
                            .filter(key -> key.equalsIgnoreCase(entry.getKey()))
                            .findFirst()
                            .orElse(null);
            String existing = existingKey != null ? destination.get(existingKey) : null;
            if (existing != null && !existing.equals(entry.getValue())) {
                throw new SecurityException(
                        "Authentication header conflict for agent "
                                + agentName
                                + ": "
                                + entry.getKey());
            }
            if (existingKey == null) {
                destination.put(entry.getKey(), entry.getValue());
            }
        }
    }
}
