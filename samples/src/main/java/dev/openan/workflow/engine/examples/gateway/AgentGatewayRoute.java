/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.examples.gateway;

import org.a2aproject.sdk.spec.AgentInterface;
import org.a2aproject.sdk.util.Utils;

import java.net.URI;

/** Immutable routing result used by the Eastcom gateway adapter. */
public record AgentGatewayRoute(String ne, AgentInterface agentInterface) {
    public AgentGatewayRoute {
        if (ne == null || ne.isBlank()) {
            throw new IllegalArgumentException("Gateway NE must not be blank");
        }
        if (agentInterface == null) {
            throw new IllegalArgumentException("Agent interface must not be null");
        }
    }

    public static AgentGatewayRoute fromTargetUrl(String ne, String targetUrl) {
        return new AgentGatewayRoute(ne, new AgentInterface("HTTP+JSON", targetUrl));
    }

    public String uriPath() {
        return normalizePath(URI.create(agentInterface.url()).getRawPath());
    }

    public boolean https() {
        return "https".equalsIgnoreCase(URI.create(agentInterface.url()).getScheme());
    }

    /** Builds the A2A REST operation path with the request or AgentInterface tenant. */
    public String messagePath(String requestTenant, boolean streaming) {
        String baseUrl = Utils.buildBaseUrl(agentInterface, requestTenant);
        String basePath = normalizePath(URI.create(baseUrl).getRawPath());
        String withoutTrailingSlash =
                basePath.endsWith("/") && basePath.length() > 1
                        ? basePath.substring(0, basePath.length() - 1)
                        : basePath;
        return withoutTrailingSlash + (streaming ? "/message:stream" : "/message:send");
    }

    private static String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }
        return path.startsWith("/") ? path : "/" + path;
    }
}
