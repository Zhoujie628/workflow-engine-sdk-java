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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Registry of A2A-T extension handlers.
 *
 * <p>The workflow engine only handles Task-T (task prompt generation) and Negotiation-T (auto
 * negotiation loop). Authorization-T and Notification-T are pre-positioning operations done once
 * before the workflow starts (see {@link WorkflowEngineClient#sendExtensionMessage}), so they are
 * NOT part of the workflow's extension handler chain.
 */
class ExtensionRegistry {

    private static final Logger log = LoggerFactory.getLogger(ExtensionRegistry.class);

    private final Map<String, ExtensionHandler> handlers = new LinkedHashMap<>();

    public ExtensionRegistry() {
        register(new TaskTHandler());
        register(new NegotiationTHandler());
    }

    /**
     * Creates a registry whose built-in Negotiation-T handler validates received propose messages
     * against the given business parameter schema. Use this instead of the no-arg constructor when
     * the caller's domain declares concrete negotiation parameters.
     *
     * @param negotiationParamSchema parameter JSON schema for the validate-and-fill pipeline; null
     *     keeps the default empty schema
     */
    public ExtensionRegistry(Map<String, Object> negotiationParamSchema) {
        register(new TaskTHandler());
        register(new NegotiationTHandler(negotiationParamSchema));
    }

    public void register(ExtensionHandler handler) {
        handlers.put(handler.extensionKeyword(), handler);
    }

    /**
     * Find handlers matching the given extension URIs.
     *
     * @param extensionUris list of extension URIs from the AgentCard
     * @return matched handlers (deduplicated)
     */
    public List<ExtensionHandler> getHandlersForExtensions(List<String> extensionUris) {
        List<ExtensionHandler> matched = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        if (extensionUris == null) {
            return matched;
        }
        for (String uri : extensionUris) {
            if (uri == null) {
                continue;
            }
            java.util.Set<String> pathSegments = extensionPathSegments(uri);
            for (Map.Entry<String, ExtensionHandler> entry : handlers.entrySet()) {
                String keyword = entry.getKey().toLowerCase(Locale.ROOT);
                if (pathSegments.contains(keyword)
                        && !seen.contains(entry.getKey())) {
                    matched.add(entry.getValue());
                    seen.add(entry.getKey());
                    break;
                }
            }
        }
        return matched;
    }

    private static java.util.Set<String> extensionPathSegments(String value) {
        try {
            String path = new URI(value).getPath();
            if (path == null || path.isBlank()) return java.util.Set.of();
            java.util.Set<String> segments = new java.util.HashSet<>();
            for (String segment : path.split("/")) {
                if (!segment.isBlank()) segments.add(segment.toLowerCase(Locale.ROOT));
            }
            return segments;
        } catch (URISyntaxException e) {
            log.warn("Ignoring malformed extension URI: {}", value);
            return java.util.Set.of();
        }
    }
}
