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
import dev.openan.workflow.engine.examples.testsupport.OfflineA2ATLlmClient;
import dev.openan.workflow.engine.examples.workbench.WorkbenchControlPoint;
import dev.openan.workflow.engine.examples.server.JdkHttpA2AServer;
import dev.openan.workflow.engine.examples.server.OmcAgentLauncher;
import dev.openan.workflow.engine.model.SendMessageResult;
import dev.openan.workflow.engine.model.MessageContent;
import dev.openan.workflow.engine.model.ReceivedMessage;
import dev.openan.workflow.engine.client.A2atMessages;
import dev.openan.workflow.engine.client.A2ATExtension;
import dev.openan.workflow.engine.examples.agents.SpnTaskInput;
import dev.openan.workflow.engine.examples.demo.SpnCasePrompts;
import net.openan.a2at.sdk.client.A2ATClient;
import net.openan.a2at.sdk.core.model.*;
import net.openan.a2at.sdk.negotiation.content.*;

import org.a2aproject.sdk.spec.AgentCard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
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
    private A2ATransport transport;
    private int port;
    private String sdkEnvPath;
    private AgentCard agentCard;
    private SpnTaskInput diagnosed;
    private int diagnosisCount;

    private class RecordingCity1 extends SpnDomainAgentCity1Executor {
        @Override protected String executeBusiness(
                org.a2aproject.sdk.server.agentexecution.RequestContext ctx,
                org.a2aproject.sdk.server.tasks.AgentEmitter emitter, SpnTaskInput input) {
            diagnosed = input;
            diagnosisCount++;
            return super.executeBusiness(ctx, emitter, input);
        }
    }

    @BeforeAll
    static void installOfflineSdkProvider() {
        OfflineA2ATLlmClient.install();
    }

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
        // Exercise real A2A-T client/server pipelines through the offline structured provider.
        sdkEnvPath = OfflineA2ATLlmClient.envPath();
        System.setProperty("a2at.env.path", sdkEnvPath);
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
                new OmcAgentLauncher().startFromCard(card, new RecordingCity1());
        agentCard = mapper.convertValue(card, AgentCard.class);
        Thread.sleep(500);
        transport =
                new A2ATransport(
                        List.of(mapper.convertValue(card, AgentCard.class)),
                        null,
                        WorkflowEngineClientConfig.builder()
                                .sslVerify(false)

                                .build());
        client = new DefaultWorkflowEngineClient(transport);
        client.setControlPoint(new WorkbenchControlPoint(sdkEnvPath));
    }

    private dev.openan.workflow.engine.model.TaskRequest request() {
        return dev.openan.workflow.engine.model.TaskRequest.builder().agentName(AGENT_NAME)
                .executionId("test").taskId("logical-task").stepName("diagnosis_city1").instruction("diagnose SPN fault")
                .input(dev.openan.workflow.engine.model.BusinessInput.data(
                        dev.openan.workflow.engine.examples.demo.SpnCasePrompts.privateLineComplaintData())).build();
    }

    @AfterEach
    void tearDown() {
        if (client != null) client.close();
        if (transport != null) transport.close();
        if (server != null) server.close();
        System.clearProperty("a2at.env.path");
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
        SendMessageResult result = client.dispatch(request(), new WorkbenchControlPoint(sdkEnvPath).onTask(request()).join(), new WorkbenchControlPoint(sdkEnvPath)).join();
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
     * Negotiation path: incomplete Task-T data starts negotiation (propose + INPUT_REQUIRED); the engine's
     * auto-loop must observe the interruption, ask the (default) control point for a
     * clarification, send the follow-up, and complete with the business result. Guards against
     * the SSE stream hanging open on INPUT_REQUIRED (A2A interrupted states are not final).
     */
    @Test
    void testNegotiationAutoLoopCompletes() {
        // Task-T validation rejects the blank object; no artificial Negotiation-T key is needed.
        String taskPrompt =
                "## 任务类型(Task Type)\n传输专线业务投诉诊断\n\n"
                        + "## 任务对象(Task Object)\n接入端口名称：\n\n"
                        + "## 任务上下文(Task Context)\n1. 投诉分类：\"专线质差\"";
        Map<String, Object> metadata = new java.util.LinkedHashMap<>();
        metadata.put(
                "https://projects.tmforum.org/a2aproject/telecommunication/extensions/Task-T/v1",
                taskPrompt);
        metadata.put(MetadataContent.TEMPLATE_URI_METADATA_KEY, StandardTemplates.PRIVATE_LINE_COMPLAINT.uri());
        SendMessageResult result =
                client
                        .dispatch(request(), new dev.openan.workflow.engine.model.MessageContent(
                                List.of(new org.a2aproject.sdk.spec.TextPart("diagnose SPN fault")), metadata,
                                java.util.Set.of(A2ATExtension.TASK_T.uri())), new WorkbenchControlPoint(sdkEnvPath))
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


    private A2ATClient contentSdk() {
        return dev.openan.workflow.engine.examples.util.A2ATInitialization.create(
                () -> new A2ATClient(java.nio.file.Path.of(sdkEnvPath)));
    }

    private SendMessageResult startIncomplete() {
        var content = new MessageContent(List.of(new org.a2aproject.sdk.spec.TextPart("diagnose")),
                SpnCasePrompts.taskTMetadata(SpnCasePrompts.privateLineComplaintPromptBlankObject()),
                java.util.Set.of(A2ATExtension.TASK_T.uri()));
        var result = transport.send(agentCard, AGENT_NAME, content, java.util.UUID.randomUUID().toString(), null, null).join();
        assertEquals("TASK_STATE_INPUT_REQUIRED", result.getTaskState());
        assertEquals(0, diagnosisCount);
        return result;
    }

    private NegotiationContext negotiationContext(SendMessageResult pending) {
        return pending.getReceivedMessages().stream().map(A2atMessages::contextOf)
                .filter(java.util.Objects::nonNull).findFirst().orElseThrow();
    }

    private MetadataContent accept(NegotiationContext context, Map<String, Object> data) {
        return contentSdk().generateNegotiationAcceptPromptFromData(new NegotiationEndingData(context,
                new InformationEndingContent(NegotiationConclusion.ACCEPT, data.entrySet().stream()
                        .map(e -> new NegotiationItem(e.getKey(), e.getValue().toString())).toList())),
                StandardTemplates.INFORMATION_NEGOTIATION_ACCEPT_REJECT.uri());
    }

    private SendMessageResult followUp(SendMessageResult pending, MessageContent content) {
        return transport.send(agentCard, AGENT_NAME, content, pending.getTask().contextId(),
                pending.getTask().id(), null).orTimeout(30, java.util.concurrent.TimeUnit.SECONDS).join();
    }

    @Test void acceptUsesValidatedMetadataAndPreservesActualIncidentNotPartsSummary() {
        var pending = startIncomplete();
        var data = new java.util.LinkedHashMap<>(SpnCasePrompts.privateLineComplaintData());
        data.put("任务上下文", data.get("任务上下文").toString().replace("event-id-20260511-09013", "incident-actual-42"));
        var reply = A2atMessages.from(accept(negotiationContext(pending), data),
                List.of(new org.a2aproject.sdk.spec.TextPart("misleading summary P999-wrong")));
        var result = followUp(pending, reply);
        assertEquals("TASK_STATE_COMPLETED", result.getTaskState());
        assertEquals(1, diagnosisCount);
        assertEquals("incident-actual-42", diagnosed.incidentId());
        assertEquals(data, diagnosed.parameters());
        assertFalse(diagnosed.diagnosisInput().contains("misleading summary"));
    }

    @Test void normalTaskUsesFilledInputWithoutNegotiation() {
        var data = new java.util.LinkedHashMap<>(SpnCasePrompts.privateLineComplaintData());
        data.put("任务上下文", data.get("任务上下文").toString().replace("event-id-20260511-09013", "incident-normal-19"));
        var generated = contentSdk().generateTaskPromptFromDataWithSchema(data,
                SpnCasePrompts.privateLineComplaintSchema(), StandardTemplates.PRIVATE_LINE_COMPLAINT.uri());
        var result = transport.send(agentCard, AGENT_NAME, A2atMessages.from(generated, List.of(new org.a2aproject.sdk.spec.TextPart("business summary"))),
                java.util.UUID.randomUUID().toString(), null, null).join();
        assertEquals("TASK_STATE_COMPLETED", result.getTaskState());
        assertEquals(data, diagnosed.parameters());
        assertEquals("incident-normal-19", diagnosed.incidentId());
        assertEquals(1, diagnosisCount);
    }


    @Test void unknownPortNegotiatesOnlyObjectAndKeepsValidatedContext() {
        var original = new java.util.LinkedHashMap<>(SpnCasePrompts.privateLineComplaintDataUnknownPort());
        original.put("任务上下文", original.get("任务上下文").toString().replace("event-id-20260511-09013", "incident-preserved-73"));
        var generated = contentSdk().generateTaskPromptFromDataWithSchema(original,
                SpnCasePrompts.privateLineComplaintSchema(), StandardTemplates.PRIVATE_LINE_COMPLAINT.uri());
        var pending = transport.send(agentCard, AGENT_NAME, A2atMessages.from(generated,
                List.of(new org.a2aproject.sdk.spec.TextPart("diagnose unknown port"))),
                java.util.UUID.randomUUID().toString(), null, null).join();
        assertEquals("TASK_STATE_INPUT_REQUIRED", pending.getTaskState());
        var reply = accept(negotiationContext(pending),
                Map.of("任务对象", SpnCasePrompts.privateLineComplaintData().get("任务对象")));
        var result = followUp(pending, A2atMessages.from(reply, List.of(new org.a2aproject.sdk.spec.TextPart("correct port"))));
        assertEquals("TASK_STATE_COMPLETED", result.getTaskState());
        assertEquals(original.get("任务上下文"), diagnosed.parameters().get("任务上下文"));
        assertEquals("incident-preserved-73", diagnosed.incidentId());
        assertEquals(1, diagnosisCount);
    }

    @Test void taskPartsCannotReplaceMissingFormalTaskMetadata() {
        var generated = contentSdk().generateTaskPromptFromDataWithSchema(SpnCasePrompts.privateLineComplaintData(),
                SpnCasePrompts.privateLineComplaintSchema(), StandardTemplates.PRIVATE_LINE_COMPLAINT.uri());
        var metadata = new java.util.LinkedHashMap<>(generated.buildMetadataContent());
        metadata.remove(A2ATExtension.TASK_T.uri());
        var content = new MessageContent(List.of(new org.a2aproject.sdk.spec.TextPart(generated.promptText())),
                metadata, java.util.Set.of(A2ATExtension.TASK_T.uri()));
        var result = transport.send(agentCard, AGENT_NAME, content,
                java.util.UUID.randomUUID().toString(), null, null).join();
        assertEquals("TASK_STATE_FAILED", result.getTaskState());
        assertEquals(0, diagnosisCount);
    }

    @Test void partsCannotRescueInvalidFormalNegotiationBody() {
        var pending = startIncomplete();
        var generated = accept(negotiationContext(pending), SpnCasePrompts.privateLineComplaintData());
        var metadata = new java.util.LinkedHashMap<>(generated.buildMetadataContent());
        metadata.put(A2ATExtension.NEGOTIATION_T.uri(), "补充诊断信息");
        var result = followUp(pending, new MessageContent(
                List.of(new org.a2aproject.sdk.spec.TextPart(generated.promptText())), metadata,
                java.util.Set.of(A2ATExtension.NEGOTIATION_T.uri())));
        assertEquals("TASK_STATE_FAILED", result.getTaskState());
        assertEquals(0, diagnosisCount);
    }

    @Test void incompleteAcceptDoesNotDiagnose() {
        var pending = startIncomplete();
        var generated = accept(negotiationContext(pending), Map.of("任务对象", "接入端口名称：P781-珠江新城-PTN7900-23-TPA1EG24-17"));
        var result = followUp(pending, A2atMessages.from(generated, List.of(new org.a2aproject.sdk.spec.TextPart("business summary"))));
        assertEquals("TASK_STATE_FAILED", result.getTaskState());
        assertEquals(0, diagnosisCount);
    }

    @Test void rejectIsValidatedAndEndsWithoutDiagnosis() {
        var pending = startIncomplete();
        var generated = contentSdk().generateNegotiationRejectPromptFromData(
                new NegotiationEndingData(negotiationContext(pending),
                        new InformationEndingContent(NegotiationConclusion.REJECT,
                                List.of(new NegotiationItem("拒绝原因", "当前调用方无权提供该端口资料")))),
                StandardTemplates.INFORMATION_NEGOTIATION_ACCEPT_REJECT.uri());
        var result = followUp(pending, A2atMessages.from(generated, List.of(new org.a2aproject.sdk.spec.TextPart("business summary"))));
        assertEquals("TASK_STATE_FAILED", result.getTaskState());
        assertTrue(result.getText().contains("Negotiation-T REJECT"), result.getText());
        assertEquals(0, diagnosisCount);
    }

    @Test void abortIsValidatedAndEndsWithoutDiagnosis() {
        var pending = startIncomplete();
        var generated = contentSdk().generateNegotiationAbortPromptFromData(
                new NegotiationAbortData(negotiationContext(pending), new NegotiationAbortContent("业务取消本次诊断")),
                StandardTemplates.NEGOTIATION_ABORT.uri());
        var result = followUp(pending, A2atMessages.from(generated, List.of(new org.a2aproject.sdk.spec.TextPart("business summary"))));
        assertEquals("TASK_STATE_FAILED", result.getTaskState());
        assertTrue(result.getText().contains("Negotiation-T ABORT"), result.getText());
        assertEquals(0, diagnosisCount);
    }

    @Test void replyFromAnotherNegotiationDoesNotDiagnose() {
        var pending = startIncomplete();
        var context = negotiationContext(pending);
        var other = new NegotiationContext(java.util.UUID.randomUUID().toString(), context.round(),
                context.maxRounds(), context.performative());
        var result = followUp(pending, A2atMessages.from(accept(other, SpnCasePrompts.privateLineComplaintData()), List.of(new org.a2aproject.sdk.spec.TextPart("reply"))));
        assertEquals("TASK_STATE_FAILED", result.getTaskState());
        assertEquals(0, diagnosisCount);
    }

    @Test
    void taskRouteParserDecodesExactlyOnePathSegment() {
        assertEquals("task id+/city", JdkHttpA2AServer.taskIdFromPath("/tasks/task%20id%2B%2Fcity", null));
        assertEquals("task+id", JdkHttpA2AServer.taskIdFromPath("/tasks/task+id:cancel", ":cancel"));
        assertNull(JdkHttpA2AServer.taskIdFromPath("/other/task", null));
        assertNull(JdkHttpA2AServer.taskIdFromPath("/tasks/a/b", null));
        assertNull(JdkHttpA2AServer.taskIdFromPath("/tasks/id:subscribe", ":cancel"));
    }
}
