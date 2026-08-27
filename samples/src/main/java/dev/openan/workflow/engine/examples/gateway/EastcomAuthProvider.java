/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.examples.gateway;

import dev.openan.workflow.engine.client.AuthProvider;

import org.a2aproject.sdk.spec.AgentCard;

import java.util.Map;
import java.util.Objects;

/** Injects an OMC bearer token obtained through the Eastcom instruction-platform SDK. */
final class EastcomAuthProvider implements AuthProvider {

    @FunctionalInterface
    interface TokenService {
        String getOrRefresh(String agentName);
    }

    private final TokenService tokenService;
    private final String requestHeader;
    private final String scheme;

    EastcomAuthProvider(TokenService tokenService, String requestHeader, String scheme) {
        this.tokenService = Objects.requireNonNull(tokenService, "tokenService");
        this.requestHeader = requireText(requestHeader, "requestHeader");
        this.scheme = scheme == null ? "" : scheme.trim();
    }

    @Override
    public void applyAuth(
            String agentName, AgentCard agentCard, Map<String, String> headers) {
        Objects.requireNonNull(headers, "headers");
        String token = requireText(tokenService.getOrRefresh(agentName), "token");
        String value = scheme.isEmpty() ? token : scheme + " " + stripScheme(token, scheme);
        Map.Entry<String, String> existing = findHeader(headers, requestHeader);
        if (existing != null && !existing.getValue().equals(value)) {
            throw new SecurityException(
                    "Eastcom OMC authentication conflicts with an existing " + requestHeader);
        }
        headers.put(existing == null ? requestHeader : existing.getKey(), value);
    }

    private static String stripScheme(String token, String scheme) {
        String trimmed = token.trim();
        String prefix = scheme + " ";
        return trimmed.regionMatches(true, 0, prefix, 0, prefix.length())
                ? trimmed.substring(prefix.length()).trim()
                : trimmed;
    }

    private static Map.Entry<String, String> findHeader(
            Map<String, String> headers, String name) {
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (name.equalsIgnoreCase(entry.getKey())) {
                return entry;
            }
        }
        return null;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
