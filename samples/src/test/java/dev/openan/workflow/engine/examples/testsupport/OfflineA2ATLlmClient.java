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
            String source = validationInput(prompt);
            Map<String, Object> params = validationParams(properties, source);
            boolean valid = verdict(properties, params, source);
            response.put("semantic_verdict", valid);
            if (properties.containsKey("negotiation_type")) {
                response.put("negotiation_type", "information");
            }
            response.put("errors", valid ? List.of() : List.of(Map.of("slot_name", "input",
                    "code", "validation.semantic_rejected", "facts", Map.of("reason", "Missing actual input fields"))));
            response.put("params", params);
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
                                            portValue(validationInput(prompt))),
                                    Map.of("name", "投诉分类", "value", label(validationInput(prompt), "投诉分类")))));
        }

        Object slotNames = jsonSchema.get("slotNames");
        if (slotNames instanceof List<?> names) {
            Map<String, Object> slots = new LinkedHashMap<>();
            for (Object rawName : names) {
                String name = String.valueOf(rawName);
                slots.put(name, slotValue(name, validationInput(prompt)));
            }
            return response(Map.of("slots", slots, "slot_errors", List.of()));
        }
        throw new IllegalArgumentException(
                "Unsupported offline A2A-T LLM schema: " + jsonSchema.keySet());
    }

    private static Map<String, Object> validationParams(Map<?, ?> outputProperties, String source) {
        Map<?, ?> properties = outputProperties.get("params") instanceof Map<?, ?> params
                && params.get("properties") instanceof Map<?, ?> fields ? fields : Map.of();
        if (properties.isEmpty()) {
            List<String> names = source.contains("## 任务类型") ? List.of("任务对象", "任务上下文")
                    : source.contains("授权策略") ? List.of("授权策略的操作类型", "动网操作的授权策略列表")
                    : source.contains("订阅条件") ? List.of("订阅条件", "上报通知数据格式") : List.of();
            Map<String, Object> inferred = new LinkedHashMap<>();
            names.forEach(name -> inferred.put(name, Map.of()));
            properties = inferred;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        for (Object rawName : properties.keySet()) {
            String name = String.valueOf(rawName);
            if ("items".equals(name)) {
                values.put(name, negotiationItems(source).keySet().stream().toList());
            } else if ("relationship".equals(name)) {
                values.put(name, source.contains("AND") ? "AND" : null);
            } else if ("reason".equals(name)) {
                String reason = section(source, "协商终止原因");
                values.put(name, reason.isEmpty() ? section(source, "信息协商结果内容") : reason);
            } else {
                values.put(name, slotValue(name, source));
            }
        }
        return values;
    }

    private static boolean verdict(Map<?, ?> outputProperties, Map<String, Object> params, String source) {
        if (outputProperties.containsKey("negotiation_type")) {
            boolean formal = source.contains("## 信息协商") || source.contains("## 协商结果");
            if (!formal) return false; // A parts summary is NOT a negotiation prompt.
        }
        if (outputProperties.get("params") instanceof Map<?, ?> schema && schema.get("required") instanceof List<?> required) {
            for (Object key : required) {
                Object value = params.get(key);
                if (value == null || value instanceof String s && s.isBlank()
                        || value instanceof List<?> list && list.isEmpty()) return false;
            }
        }
        if (source.contains("## 任务类型")) {
            String object = String.valueOf(params.getOrDefault("任务对象", ""));
            String context = String.valueOf(params.getOrDefault("任务上下文", ""));
            return !portValue(object).isEmpty() && !label(context, "投诉分类").isEmpty()
                    && !label(context, "OSS侧事件流水号").isEmpty();
        }
        return true;
    }

    private static Map<String, String> negotiationItems(String source) {
        String content = section(source, "信息协商结果内容");
        if (content.isEmpty()) content = section(source, "所需信息项");
        Map<String, String> values = new LinkedHashMap<>();
        var matcher = java.util.regex.Pattern.compile("(?m)^\\s*\\d+[.、]\\s*([^：:\\r\\n]+)[：:]\\s*(.*)$").matcher(content);
        while (matcher.find()) values.put(matcher.group(1).strip(), matcher.group(2).strip());
        return values;
    }

    private static Object slotValue(String name, String source) {
        String value = section(source, name);
        if (!value.isEmpty()) return value;
        Map<String, String> items = negotiationItems(source);
        if (items.containsKey(name)) return items.get(name);
        // Structured SDK input uses Map.toString(); values are confined to the actual [input] block.
        String keys = "任务对象|任务上下文|授权策略的操作类型|动网操作的授权策略列表|订阅条件|上报通知数据格式";
        var field = java.util.regex.Pattern.compile("(?:^|[,{])\\s*" + java.util.regex.Pattern.quote(name)
                + "=(.*?)(?=,\\s*(?:" + keys + ")=|}\\s*$)", java.util.regex.Pattern.DOTALL).matcher(source);
        if (field.find()) return field.group(1).strip();
        if ("接入端口名称".equals(name)) return portValue(source);
        if ("投诉分类".equals(name)) return label(source, name);
        // Small explicit natural-language fixture, not general LLM emulation.
        if ("任务对象".equals(name) || "任务上下文".equals(name)) {
            int start = source.indexOf(name + "：");
            if (start >= 0) {
                start += name.length() + 1;
                int end = source.indexOf("；任务上下文：", start);
                return source.substring(start, end < 0 ? source.length() : end).strip();
            }
        }
        return "";
    }

    private static String section(String source, String name) {
        var heading = java.util.regex.Pattern.compile("(?m)^##\\s+" + java.util.regex.Pattern.quote(name)
                + "(?:\\([^\\r\\n]*\\))?[ \\t]*\\r?\\n").matcher(source);
        if (!heading.find()) return "";
        int end = source.indexOf("\n## ", heading.end());
        String body = source.substring(heading.end(), end < 0 ? source.length() : end);
        int guidance = body.indexOf("\n要求");
        if (guidance >= 0) body = body.substring(0, guidance);
        return body.replace("（必选）", "").replace("（必填）", "").strip();
    }

    private static String label(String source, String name) {
        var matcher = java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(name)
                + "[：:]\\s*[\\\"“]?([^；;\\\"”\\r\\n]+)").matcher(source);
        return matcher.find() ? matcher.group(1).strip() : "";
    }

    /** Only actual inputs, never explanatory examples or template guidance. */
    private static String validationInput(String prompt) {
        int start = prompt.indexOf("[input]\n");
        if (start >= 0) {
            start += "[input]\n".length();
            int end = prompt.indexOf("\n\n[slots]", start);
            return prompt.substring(start, end < 0 ? prompt.length() : end);
        }
        start = prompt.indexOf("输入内容：");
        if (start >= 0) {
            int end = prompt.indexOf("\n模板标识：", start);
            return prompt.substring(start + "输入内容：".length(), end < 0 ? prompt.length() : end);
        }
        start = prompt.indexOf("待校验的协商报文：\n");
        if (start >= 0) {
            int end = prompt.indexOf("\n\n请按系统提示词", start);
            return prompt.substring(start + "待校验的协商报文：\n".length(), end < 0 ? prompt.length() : end);
        }
        return prompt;
    }

    private static String portValue(String prompt) {
        var matcher = java.util.regex.Pattern.compile("P\\d+-[\\p{L}\\p{N}-]+").matcher(prompt);
        return matcher.find() ? matcher.group() : "";
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
