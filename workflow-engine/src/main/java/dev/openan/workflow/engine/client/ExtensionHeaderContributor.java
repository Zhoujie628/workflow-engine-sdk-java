/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.openan.workflow.engine.client;

import org.a2aproject.sdk.client.transport.spi.interceptors.ClientCallContext;
import org.a2aproject.sdk.client.transport.spi.interceptors.PayloadAndHeaders;
import org.a2aproject.sdk.spec.AgentCard;

import java.util.HashMap;
import java.util.Map;

/** Adds headers required by extensions declared by the target AgentCard. */
final class ExtensionHeaderContributor implements HeaderContributor {
    private final AgentAuthManager authManager;

    ExtensionHeaderContributor(AgentAuthManager authManager) {
        this.authManager = authManager;
    }

    @Override
    public Map<String, String> contribute(
            AgentCard agentCard,
            String agentName,
            Map<String, Object> messageMetadata,
            Map<String, String> currentHeaders) {
        ExtensionInterceptor interceptor = authManager.buildExtensionInterceptor(agentCard);
        if (interceptor == null) {
            return Map.of();
        }
        Map<String, String> headers = new HashMap<>(currentHeaders);
        ClientCallContext interceptContext = new ClientCallContext(new HashMap<>(), headers);
        PayloadAndHeaders payloadAndHeaders =
                interceptor.intercept(
                        "message/send", messageMetadata, headers, agentCard, interceptContext);
        return payloadAndHeaders.getHeaders();
    }
}
