/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.openan.workflow.engine.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;

import net.openan.a2at.sdk.core.model.MetadataContent;
import net.openan.a2at.sdk.core.model.StandardTemplates;
import net.openan.a2at.sdk.llm.LLMClient;
import net.openan.a2at.sdk.llm.LLMClientConfig;
import net.openan.a2at.sdk.llm.LLMClientFactory;
import net.openan.a2at.sdk.llm.LLMResponse;

import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.client.transport.spi.interceptors.ClientCallContext;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.MessageSendParams;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

class ExtensionSenderTest {

    private static final String TEST_PROVIDER = "workflow-engine-test";

    @BeforeAll
    static void registerMockProvider() {
        if (!LLMClientFactory.availableProviders().contains(TEST_PROVIDER)) {
            LLMClientFactory.register(TEST_PROVIDER, TestLlmClient.class);
        }
    }

    @Test
    void notificationCallbackMethodIsAnInterfaceContract() throws Exception {
        Method method =
                ExtensionSender.class.getMethod(
                        "sendNotification",
                        String.class,
                        String.class,
                        String.class,
                        java.util.function.Consumer.class);

        assertTrue(Modifier.isAbstract(method.getModifiers()));
    }

    @Test
    void authorizationFromDataUsesRealSdkMetadataRendering() throws Exception {
        AtomicReference<MessageSendParams> captured = new AtomicReference<>();
        A2ATransport transport = transport(captured);
        try {
            new DefaultExtensionSender(transport)
                    .sendExtensionMessageFromData(
                            "Test Agent",
                            "下发授权策略",
                            Map.of(
                                    "授权策略的操作类型", "新增授权策略",
                                    "动网操作的授权策略列表", "业务投诉诊断/业务抢通/隧道调优/2026-06-01~2030-06-18"),
                            schema("授权策略的操作类型", "动网操作的授权策略列表"),
                            A2ATExtension.AUTHORIZATION_T)
                    .join();

            Map<String, Object> metadata = captured.get().message().metadata();
            assertEquals(
                    StandardTemplates.AUTHORIZATION_POLICY_MANAGEMENT.uri(),
                    metadata.get(MetadataContent.TEMPLATE_URI_METADATA_KEY));
            assertTrue(
                    String.valueOf(metadata.get(A2ATExtension.AUTHORIZATION_T.uri()))
                            .contains("新增授权策略"));
        } finally {
            transport.close();
        }
    }

    @Test
    void notificationFromDataReturnsLifecycleHandleAndSdkMetadata() throws Exception {
        AtomicReference<MessageSendParams> captured = new AtomicReference<>();
        A2ATransport transport = transport(captured);
        try {
            NotificationSubscription subscription =
                    new DefaultExtensionSender(transport)
                            .openNotificationFromData(
                                    "Test Agent",
                                    "订阅抢通结果",
                                    Map.of(
                                            "订阅条件", "",
                                            "上报通知数据格式", "业务抢通方案执行状态、任务流水号、执行结果"),
                                    schema("订阅条件", "上报通知数据格式"),
                                    ignored -> {})
                            .join();
            subscription.acknowledgement().join();
            subscription.completion().join();

            assertNotNull(captured.get());
            Map<String, Object> metadata = captured.get().message().metadata();
            assertEquals(
                    StandardTemplates.SERVICE_RECOVERY.uri(),
                    metadata.get(MetadataContent.TEMPLATE_URI_METADATA_KEY));
            assertTrue(metadata.containsKey(A2ATExtension.NOTIFICATION_T.uri()));
        } finally {
            transport.close();
        }
    }

    @Test
    void taskFromDataUsesRealSdkMetadataPipeline() throws Exception {
        AtomicReference<MessageSendParams> captured = new AtomicReference<>();
        A2ATransport transport = transport(captured);
        try {
            new DefaultWorkflowEngineClient(transport)
                    .sendMessageFromData(
                            "Test Agent",
                            "创建诊断任务",
                            Map.of(
                                    "任务对象", "接入端口名称：P781-test",
                                    "任务上下文", "投诉分类：专线质差"),
                            schema("任务对象", "任务上下文"),
                            StandardTemplates.PRIVATE_LINE_COMPLAINT)
                    .join();

            Map<String, Object> metadata = captured.get().message().metadata();
            assertEquals(
                    StandardTemplates.PRIVATE_LINE_COMPLAINT.uri(),
                    metadata.get(MetadataContent.TEMPLATE_URI_METADATA_KEY));
            assertTrue(metadata.containsKey(A2ATExtension.TASK_T.uri()));
        } finally {
            transport.close();
        }
    }

    @Test
    void naturalLanguageExtensionWithoutSdkDoesNotForgeProtocolMetadata() throws Exception {
        AtomicReference<MessageSendParams> captured = new AtomicReference<>();
        A2ATransport transport =
                new A2ATransport(
                        List.of(agentCard()),
                        new CapturingRuntime(captured),
                        WorkflowEngineClientConfig.builder().build());
        try {
            CompletionException error =
                    assertThrows(
                            CompletionException.class,
                            () ->
                                    new DefaultExtensionSender(transport)
                                            .sendAuthorization(
                                                    "Test Agent",
                                                    "authorize",
                                                    "新增业务抢通白名单")
                                            .join());

            assertTrue(error.getCause().getMessage().contains("SDK prompt generation failed"));
            assertNull(captured.get());
        } finally {
            transport.close();
        }
    }

    private static Map<String, Object> schema(String... fields) {
        Map<String, Object> properties = new java.util.LinkedHashMap<>();
        for (String field : fields) {
            properties.put(field, Map.of("type", "string", "description", field));
        }
        return Map.of("type", "object", "properties", properties);
    }

    private static A2ATransport transport(AtomicReference<MessageSendParams> captured)
            throws Exception {
        java.net.URL env =
                ExtensionSenderTest.class.getClassLoader().getResource("a2at-mock.env");
        if (env == null) {
            throw new IllegalStateException("Missing test resource a2at-mock.env");
        }
        WorkflowEngineClientConfig config =
                WorkflowEngineClientConfig.builder()
                        .a2atEnvPath(Path.of(env.toURI()).toString())
                        .build();
        return new A2ATransport(List.of(agentCard()), new CapturingRuntime(captured), config);
    }

    private static AgentCard agentCard() throws Exception {
        String json =
                """
                {
                  "name": "Test Agent",
                  "description": "test",
                  "version": "1.0",
                  "capabilities": {
                    "streaming": true,
                    "extensions": [
                      {"uri": "%s", "required": false},
                      {"uri": "%s", "required": false},
                      {"uri": "%s", "required": false}
                    ]
                  },
                  "defaultInputModes": ["text/plain"],
                  "defaultOutputModes": ["text/plain"],
                  "skills": [],
                  "supportedInterfaces": [{
                    "protocolBinding": "HTTP+JSON",
                    "protocolVersion": "1.0",
                    "url": "https://agent.example.test/a2a/json",
                    "tenant": ""
                  }]
                }
                """
                        .formatted(
                                A2ATExtension.TASK_T.uri(),
                                A2ATExtension.AUTHORIZATION_T.uri(),
                                A2ATExtension.NOTIFICATION_T.uri());
        return new ObjectMapper()
                .registerModule(new AgentCardJacksonModule())
                .readValue(json, AgentCard.class);
    }

    private record CapturingRuntime(AtomicReference<MessageSendParams> captured)
            implements A2AJavaClientRuntime {
        @Override
        public Iterable<ClientEvent> sendMessage(
                AgentCard agentCard,
                MessageSendParams params,
                ClientCallContext callContext,
                Consumer<ClientEvent> eventSink,
                Consumer<String> logSink) {
            captured.set(params);
            return List.of();
        }

        @Override
        public void close() {}
    }

    /** Offline LLM provider exercising the SDK's real schema-mapping pipelines without network. */
    public static final class TestLlmClient implements LLMClient {
        private final LLMClientConfig config;

        public TestLlmClient(LLMClientConfig config) {
            this.config = config;
        }

        @Override
        public LLMResponse structured(
                List<Map<String, String>> messages,
                Map<String, Object> jsonSchema,
                Double temperature,
                Integer maxTokens) {
            Object properties = jsonSchema.get("properties");
            if (properties instanceof Map<?, ?> propertyMap
                    && propertyMap.containsKey("semantic_verdict")) {
                return response(
                        "{\"semantic_verdict\":true,\"negotiation_type\":\"information\","
                                + "\"errors\":[],\"params\":{}}");
            }
            Object slotNames = jsonSchema.get("slotNames");
            StringBuilder slots = new StringBuilder("{");
            if (slotNames instanceof List<?> names) {
                for (int i = 0; i < names.size(); i++) {
                    if (i > 0) slots.append(',');
                    String name = String.valueOf(names.get(i));
                    slots.append('"')
                            .append(name)
                            .append("\":\"")
                            .append(slotValue(name))
                            .append('"');
                }
            }
            slots.append('}');
            return response("{\"slots\":" + slots + ",\"slot_errors\":[]}");
        }

        private LLMResponse response(String content) {
            return new LLMResponse(content, config.model(), Map.of(), Map.of());
        }

        private static String slotValue(String name) {
            if (name.contains("操作类型")) return "新增授权策略";
            if (name.contains("策略列表")) {
                return "业务投诉诊断/业务抢通/隧道调优/2026-06-01~2030-06-18";
            }
            if (name.contains("订阅条件")) return "全部地市";
            if (name.contains("上报通知数据格式")) return "执行状态、任务流水号、执行结果";
            if (name.contains("任务对象")) return "接入端口名称：P781-test";
            if (name.contains("任务上下文")) return "投诉分类：专线质差";
            return "test-value";
        }
    }
}
