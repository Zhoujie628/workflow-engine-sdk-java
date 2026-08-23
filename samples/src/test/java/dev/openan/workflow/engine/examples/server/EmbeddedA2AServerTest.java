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

package dev.openan.workflow.engine.examples.server;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.openan.workflow.engine.client.A2ATransport;
import dev.openan.workflow.engine.client.AgentCardJacksonModule;
import dev.openan.workflow.engine.client.DefaultWorkflowEngineClient;
import dev.openan.workflow.engine.client.WorkflowEngineClientConfig;
import dev.openan.workflow.engine.examples.agents.SpnDomainAgentCity1Executor;
import dev.openan.workflow.engine.examples.server.JdkHttpA2AServer;
import dev.openan.workflow.engine.examples.server.OmcAgentLauncher;
import dev.openan.workflow.engine.model.SendMessageResult;

import org.a2aproject.sdk.spec.AgentCard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

/**
 * Integration test: start an EmbeddedA2AServer, send a message via DefaultWorkflowEngineClient (the
 * real A2A client), and verify the response text is extracted correctly from the SSE stream.
 */
class EmbeddedA2AServerTest {

    private static final ObjectMapper mapper =
            new ObjectMapper().registerModule(new AgentCardJacksonModule());
    private static final String AGENT_NAME = "Test Agent";
    private JdkHttpA2AServer server;
    private DefaultWorkflowEngineClient client;
    private int port;

    private static String extractExtensionValue(SendMessageResult result, String extKeyword) {
        Map<String, Object> meta = result.getMetadata();
        if (meta == null) return null;
        for (String key : meta.keySet()) {
            if (key.contains(extKeyword)) {
                Object v = meta.get(key);
                return v instanceof String s ? s : String.valueOf(v);
            }
        }
        return null;
    }

    private static javax.net.ssl.SSLContext createTrustAllSslContext() throws Exception {
        javax.net.ssl.SSLContext ctx = javax.net.ssl.SSLContext.getInstance("TLS");
        ctx.init(
                null,
                new javax.net.ssl.TrustManager[] {
                    new javax.net.ssl.X509TrustManager() {
                        public void checkClientTrusted(
                                java.security.cert.X509Certificate[] chain, String authType) {}

                        public void checkServerTrusted(
                                java.security.cert.X509Certificate[] chain, String authType) {}

                        public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                            return new java.security.cert.X509Certificate[0];
                        }
                    }
                },
                null);
        return ctx;
    }

    @BeforeEach
    void setUp() throws Exception {
        // Unit tests must stay deterministic and offline: disable LLM calls so
        // agents fall back to their hardcoded diagnostic text (asserted below).
        System.setProperty("a2at.llm.disabled", "true");
        port = 28000 + (int) (Math.random() * 1000);
        Map<String, Object> card =
                Map.of(
                        "name", AGENT_NAME,
                        "description", "test",
                        "provider", Map.of("organization", "test", "url", ""),
                        "version", "1.0.0",
                        "capabilities",
                                Map.of(
                                        "streaming",
                                        true,
                                        "pushNotifications",
                                        false,
                                        "extendedAgentCard",
                                        false,
                                        "extensions",
                                        List.of(
                                                Map.of(
                                                        "uri",
                                                        "https://projects.tmforum.org/a2aproject"
                                                                + "/telecommunication/extensions"
                                                                + "/Negotiation-T/v1",
                                                        "description",
                                                        "Negotiation-T test extension",
                                                        "required",
                                                        false))),
                        "defaultInputModes", List.of("text/plain"),
                        "defaultOutputModes", List.of("text/plain"),
                        "skills",
                                List.of(
                                        Map.of(
                                                "id",
                                                "test",
                                                "name",
                                                "test",
                                                "description",
                                                "test",
                                                "tags",
                                                List.of())),
                        "supportedInterfaces",
                                List.of(
                                        Map.of(
                                                "protocolBinding",
                                                "HTTP+JSON",
                                                "protocolVersion",
                                                "1.0",
                                                "url",
                                                "https://127.0.0.1:" + port,
                                                "tenant",
                                                "")));
        server =
                new OmcAgentLauncher().startFromCard(card, new SpnDomainAgentCity1Executor());
        Thread.sleep(500);
        A2ATransport transport =
                new A2ATransport(
                        List.of(mapper.convertValue(card, AgentCard.class)),
                        null,
                        WorkflowEngineClientConfig.builder().sslVerify(false).build());
        client = new DefaultWorkflowEngineClient(transport);
    }

    @AfterEach
    void tearDown() {
        if (client != null) client.close();
        if (server != null) server.close();
    }

    @Test
    void testGetAgentCard() throws Exception {
        HttpClient http = HttpClient.newBuilder().sslContext(createTrustAllSslContext()).build();
        HttpRequest req =
                HttpRequest.newBuilder()
                        .uri(URI.create("https://127.0.0.1:" + port + "/"))
                        .GET()
                        .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode());
        Map<String, Object> card = mapper.readValue(resp.body(), Map.class);
        assertEquals(AGENT_NAME, card.get("name"));
    }

    @Test
    void testSendMessage() throws Exception {
        SendMessageResult result = client.sendMessage(AGENT_NAME, "diagnose SPN fault").join();
        assertNotNull(result);
        assertFalse(
                result.getText().isEmpty(),
                "Response text should not be empty, got: " + result.getText());
        // A2A-T extension content is in metadata (Task-T/v1), not in parts.text
        String taskMeta = extractExtensionValue(result, "Task-T");
        assertNotNull(taskMeta, "Task-T metadata should be present in response");
        assertTrue(
                taskMeta.contains("诊断结果"),
                "Diagnosis metadata should contain structured diagnosis result, got: " + taskMeta);
    }

    /**
     * Negotiation path: when the message metadata carries a Task-T prompt plus the Negotiation-T
     * extension key, the agent starts a negotiation (propose + INPUT_REQUIRED); the engine's
     * auto-loop must observe the interruption, ask the (default) control point for a
     * clarification, send the follow-up, and complete with the business result. Guards against
     * the SSE stream hanging open on INPUT_REQUIRED (A2A interrupted states are not final).
     */
    @Test
    void testNegotiationAutoLoopCompletes() {
        // Task-T prompt with a blank task object so server-side validation would also flag it;
        // the Negotiation-T key activates the extension on the A2A-Extensions header.
        String taskPrompt =
                "## 任务类型(Task Type)\n传输专线业务投诉诊断\n\n"
                        + "## 任务对象(Task Object)\n接入端口名称：\n\n"
                        + "## 任务上下文(Task Context)\n1. 投诉分类：\"专线质差\"";
        Map<String, Object> metadata = new java.util.LinkedHashMap<>();
        metadata.put(
                "https://projects.tmforum.org/a2aproject/telecommunication/extensions/Task-T/v1",
                taskPrompt);
        metadata.put(
                "https://projects.tmforum.org/a2aproject/telecommunication/extensions/Negotiation-T/v1",
                "");
        SendMessageResult result =
                client
                        .sendMessage(AGENT_NAME, "diagnose SPN fault", null, metadata)
                        .orTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                        .join();
        assertNotNull(result);
        assertFalse(
                result.getText().isEmpty(),
                "Negotiation round must end with the business response, got: " + result.getText());
        assertTrue(
                result.getText().contains("诊断结果"),
                "Final response must be the completed diagnosis, got: " + result.getText());
    }
}
