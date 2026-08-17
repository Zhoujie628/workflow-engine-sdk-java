/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.client;

import org.a2aproject.sdk.spec.AgentCard;

/**
 * Optional capability for runtimes whose transport session spans multiple A2A requests.
 *
 * <p>An A2A negotiation uses a second HTTP/SSE request, but remains part of the same logical
 * conversation. Implementations can use this lifecycle callback to keep an authenticated gateway
 * session alive across all negotiation rounds and release it only after the complete conversation.
 */
public interface ConversationScopedA2AJavaClientRuntime {

    /** Release resources retained for the completed logical conversation. */
    void closeConversation(AgentCard agentCard, String contextId);
}
