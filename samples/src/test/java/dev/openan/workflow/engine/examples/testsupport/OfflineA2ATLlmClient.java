/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.examples.testsupport;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.openan.workflow.engine.examples.demo.SpnCasePrompts;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.openan.a2at.sdk.llm.LLMClient;
import net.openan.a2at.sdk.llm.LLMClientConfig;
import net.openan.a2at.sdk.llm.LLMClientFactory;
import net.openan.a2at.sdk.llm.LLMResponse;

/** Offline structured provider shared by the sample's real A2A-T integration tests. */
public final class OfflineA2ATLlmClient implements LLMClient {
    public static final String PROVIDER = "workflow-engine-e2e-test";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final LLMClientConfig config;

    public OfflineA2ATLlmClient(LLMClientConfig config) {
        this.config = config;
    }

    public static void install() {
        if (!LLMClientFactory.availableProviders().contains(PROVIDER)) {
            LLMClientFactory.register(PROVIDER, OfflineA2ATLlmClient.class);
        }
    }

    public static String envPath() {
        try {
            var env =
                    OfflineA2ATLlmClient.class
                            .getClassLoader()
                            .getResource("a2at-e2e.env");
            if (env == null) {
                throw new IllegalStateException("Missing test resource a2at-e2e.env");
            }
            return Path.of(env.toURI()).toString();
        } catch (java.net.URISyntaxException e) {
            throw new IllegalStateException("Invalid a2at-e2e.env resource URI", e);
        }
    }

    @Override
    public LLMResponse structured(
            List<Map<String, String>> messages,
            Map<String, Object> jsonSchema,
            Double temperature,
            Integer maxTokens) {
        String prompt =
                messages.isEmpty()
                        ? ""
                        : messages.get(messages.size() - 1).getOrDefault("content", "");
        Object propertiesValue = jsonSchema.get("properties");
        if (propertiesValue instanceof Map<?, ?> properties
                && properties.containsKey("semantic_verdict")) {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("semantic_verdict", true);
            if (properties.containsKey("negotiation_type")) {
                response.put("negotiation_type", "information");
            }
            response.put("errors", List.of());
            response.put("params", validationParams(properties, prompt));
            return response(response);
        }
        if (propertiesValue instanceof Map<?, ?> properties
                && properties.containsKey("conclusion")
                && properties.containsKey("items")) {
            return response(
                    Map.of(
                            "conclusion",
                            "Accept",
                            "items",
                            List.of(
                                    Map.of(
                                            "name",
                                            "接入端口名称",
                                            "value",
                                            "P533-珠江旧城-PTN3900-23-TPA1EG24-1"),
                                    Map.of("name", "投诉分类", "value", "专线质差"))));
        }

        Object slotNames = jsonSchema.get("slotNames");
        if (slotNames instanceof List<?> names) {
            Map<String, Object> slots = new LinkedHashMap<>();
            for (Object rawName : names) {
                String name = String.valueOf(rawName);
                slots.put(name, slotValue(name, prompt));
            }
            return response(Map.of("slots", slots, "slot_errors", List.of()));
        }
        throw new IllegalArgumentException(
                "Unsupported offline A2A-T LLM schema: " + jsonSchema.keySet());
    }

    private static Map<String, Object> validationParams(
            Map<?, ?> outputProperties, String prompt) {
        Object paramsValue = outputProperties.get("params");
        if (paramsValue instanceof Map<?, ?> paramsSchema
                && paramsSchema.get("properties") instanceof Map<?, ?> businessProperties
                && !businessProperties.isEmpty()) {
            Map<String, Object> params = new LinkedHashMap<>();
            for (Object rawName : businessProperties.keySet()) {
                String name = String.valueOf(rawName);
                params.put(name, slotValue(name, prompt));
            }
            return params;
        }
        if (prompt.contains("授权策略") || prompt.contains("白名单")) {
            return authorizationData(prompt);
        }
        if (prompt.contains("订阅条件") || prompt.contains("上报通知数据格式")) {
            return SpnCasePrompts.subscribeServiceRecoveryData();
        }
        Map<String, Object> task = new LinkedHashMap<>();
        task.put("任务对象", slotValue("任务对象", prompt));
        task.put("任务上下文", slotValue("任务上下文", prompt));
        return task;
    }

    private static Map<String, Object> authorizationData(String prompt) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("授权策略的操作类型", authorizationOperation(prompt));
        int ruleIndex = prompt.indexOf("业务投诉诊断，业务抢通，隧道调优，");
        int idIndex = prompt.indexOf("7d8c7b00-3c8c-4f8e-9b1e-9b17b6a3e5c3");
        data.put(
                "动网操作的授权策略列表",
                idIndex >= 0 && (ruleIndex < 0 || idIndex < ruleIndex)
                        ? "7d8c7b00-3c8c-4f8e-9b1e-9b17b6a3e5c3"
                        : "业务投诉诊断，业务抢通，隧道调优，2026-06-01~2030-06-18");
        return data;
    }

    private static Object slotValue(String name, String prompt) {
        if (name.contains("授权策略的操作类型") || name.contains("操作类型")) {
            return authorizationOperation(prompt);
        }
        if (name.contains("动网操作的授权策略列表") || name.contains("策略列表")) {
            return authorizationData(prompt).get("动网操作的授权策略列表");
        }
        if (name.contains("订阅条件")) return "";
        if (name.contains("上报通知数据格式")) {
            return SpnCasePrompts.subscribeServiceRecoveryData().get("上报通知数据格式");
        }
        if (name.contains("接入端口名称")) {
            return "P533-珠江旧城-PTN3900-23-TPA1EG24-1";
        }
        if (name.contains("投诉分类")) return "专线质差";
        if (name.contains("任务对象")) {
            if (prompt.contains("P882-")) {
                return "接入端口名称：P882-珠江新城-PTN7900-23-TPA1EG24-11";
            }
            if (prompt.contains("\"任务对象\":\"\"")
                    || prompt.contains("'任务对象': ''")) {
                return "";
            }
            return "接入端口名称：P781-珠江新城-PTN7900-23-TPA1EG24-17";
        }
        if (name.contains("任务上下文")) {
            return "投诉分类：专线质差；OSS侧事件流水号：event-id-20260511-09013";
        }
        return "test-value";
    }

    private static String authorizationOperation(String prompt) {
        Map<String, String> candidates =
                Map.of(
                        "新增授权策略", "新增授权策略",
                        "修改授权策略", "修改授权策略",
                        "删除授权策略", "删除授权策略",
                        "查询授权策略", "查询授权策略");
        int earliest = Integer.MAX_VALUE;
        String operation = "新增授权策略";
        for (Map.Entry<String, String> candidate : candidates.entrySet()) {
            int index = prompt.indexOf(candidate.getKey());
            if (index >= 0 && index < earliest) {
                earliest = index;
                operation = candidate.getValue();
            }
        }
        return operation;
    }

    private LLMResponse response(Map<String, ?> body) {
        try {
            return new LLMResponse(
                    MAPPER.writeValueAsString(body), config.model(), Map.of(), Map.of());
        } catch (Exception e) {
            throw new IllegalStateException("Cannot serialize offline A2A-T response", e);
        }
    }
}
