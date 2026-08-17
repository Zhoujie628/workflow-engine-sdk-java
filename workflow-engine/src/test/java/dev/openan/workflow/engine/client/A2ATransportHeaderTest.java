/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.openan.workflow.engine.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.client.transport.spi.interceptors.ClientCallContext;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.MessageSendParams;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

class A2ATransportHeaderTest {

    private static final String TASK_T_URI =
            "https://projects.tmforum.org/a2aproject/telecommunication/extensions/Task-T/v1";

    @Test
    void forwardsAuthorizationAndActiveExtensionHeadersToRuntime() throws Exception {
        AtomicReference<Map<String, String>> capturedHeaders = new AtomicReference<>();
        A2AJavaClientRuntime runtime = new CapturingRuntime(capturedHeaders);
        AgentCard card = agentCard();
        WorkflowEngineClientConfig config =
                WorkflowEngineClientConfig.builder()
                        .authProvider(
                                (agentName, ignored, headers) ->
                                        headers.put("Authorization", "Bearer test-token"))
                        .build();

        try (A2ATransport transport = new A2ATransport(List.of(card), runtime, config)) {
            transport.send(
                            card,
                            card.name(),
                            "diagnose",
                            "context-1",
                            Map.of(TASK_T_URI, "structured task"),
                            null)
                    .join();
        }

        assertEquals("Bearer test-token", capturedHeaders.get().get("Authorization"));
        assertEquals(TASK_T_URI, capturedHeaders.get().get("A2A-Extensions"));
    }

    private static AgentCard agentCard() throws Exception {
        String json =
                """
                {
                  "name": "Test Agent",
                  "description": "test",
                  "version": "1.0",
                  "capabilities": {
                    "streaming": false,
                    "extensions": [
                      {"uri": "%s", "required": false}
                    ]
                  },
                  "defaultInputModes": ["text/plain"],
                  "defaultOutputModes": ["text/plain"],
                  "skills": [],
                  "supportedInterfaces": [
                    {
                      "protocolBinding": "HTTP+JSON",
                      "protocolVersion": "1.0",
                      "url": "https://agent.example.test/a2a/json",
                      "tenant": ""
                    }
                  ]
                }
                """
                        .formatted(TASK_T_URI);
        return new ObjectMapper()
                .registerModule(new AgentCardJacksonModule())
                .readValue(json, AgentCard.class);
    }

    private record CapturingRuntime(AtomicReference<Map<String, String>> capturedHeaders)
            implements A2AJavaClientRuntime {

        @Override
        public Iterable<ClientEvent> sendMessage(
                AgentCard agentCard,
                MessageSendParams params,
                ClientCallContext callContext,
                Consumer<ClientEvent> eventSink,
                Consumer<String> logSink) {
            capturedHeaders.set(Map.copyOf(callContext.getHeaders()));
            return List.of();
        }

        @Override
        public void close() {}
    }
}
