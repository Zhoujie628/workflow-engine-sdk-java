/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 *    Licensed under the Apache License, Version 2.0 (the "License"); you may
 *    not use this file except in compliance with the License. You may obtain
 *    a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *    WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *    License for the specific language governing permissions and limitations
 *    under the License.
 */

package dev.openan.workflow.engine.examples.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Sample LLM helper that calls an OpenAI-compatible chat completions endpoint.
 *
 * <p>This helper intentionally lives in samples: the workflow engine itself
 * does not call an LLM directly. Callers must provide a deterministic fallback
 * for missing configuration and transient model failures.
 */
public final class LlmHelper {

    private static final Logger log = LoggerFactory.getLogger(LlmHelper.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static volatile Config cached;

    private LlmHelper() {}

    public static String text(String envPath, String system, String user, String fallback) {
        Config config = resolve(envPath);
        if (config == null) {
            return fallback;
        }
        try {
            String body = buildRequestBody(config, system, user);
            HttpResponse response = post(config, body);
            if (response.statusCode != 200) {
                log.warn("[LlmHelper] HTTP {}: {}", response.statusCode, response.body);
                return fallback;
            }
            String content = extractContent(response.body);
            if (content == null || content.isBlank()) {
                log.warn("[LlmHelper] empty model reply, using fallback");
                return fallback;
            }
            return content;
        } catch (Exception e) {
            log.warn("[LlmHelper] LLM call failed, using fallback: {}", e.getMessage());
            return fallback;
        }
    }

    private static HttpResponse post(Config config, String body) throws IOException {
        HttpURLConnection connection =
                (HttpURLConnection)
                        URI.create(config.baseUrl + "/v1/chat/completions")
                                .toURL()
                                .openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout((int) Duration.ofSeconds(10).toMillis());
            connection.setReadTimeout(
                    Math.toIntExact(Duration.ofSeconds(config.timeoutSeconds).toMillis()));
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            connection.setRequestProperty("Authorization", "Bearer " + config.apiKey);
            connection.setDoOutput(true);
            try (var output = connection.getOutputStream()) {
                output.write(body.getBytes(StandardCharsets.UTF_8));
            }
            int status = connection.getResponseCode();
            InputStream responseStream =
                    status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String responseBody =
                    responseStream == null
                            ? ""
                            : new String(responseStream.readAllBytes(), StandardCharsets.UTF_8);
            if (responseStream != null) {
                responseStream.close();
            }
            return new HttpResponse(status, responseBody);
        } finally {
            connection.disconnect();
        }
    }

    private static Config resolve(String envPath) {
        if (!Boolean.parseBoolean(System.getProperty("a2at.samples.llm.enabled", "true"))) {
            log.debug("[LlmHelper] disabled by a2at.samples.llm.enabled");
            return null;
        }
        if (envPath == null || envPath.isBlank()) {
            return null;
        }
        if (cached != null && envPath.equals(cached.envPath)) {
            return cached;
        }
        synchronized (LlmHelper.class) {
            if (cached != null && envPath.equals(cached.envPath)) {
                return cached;
            }
            Config config = loadFromEnv(Path.of(envPath));
            if (config == null) {
                return null;
            }
            cached = config;
            log.info("[LlmHelper] LLM client ready: model={}, baseUrl={}", config.model, config.baseUrl);
            return cached;
        }
    }

    private static Config loadFromEnv(Path envFile) {
        if (!Files.exists(envFile)) {
            log.debug("[LlmHelper] .env not found: {}", envFile);
            return null;
        }
        Map<String, String> properties;
        try {
            properties = parseEnvLines(Files.readAllLines(envFile));
        } catch (IOException e) {
            log.warn("[LlmHelper] Failed to read {}: {}", envFile, e.getMessage());
            return null;
        }
        String apiKey = properties.get("A2AT_LLM_API_KEY");
        String baseUrl = properties.get("A2AT_LLM_BASE_URL");
        String model = properties.get("A2AT_LLM_MODEL");
        if (apiKey == null || apiKey.isBlank() || baseUrl == null || model == null) {
            log.warn("[LlmHelper] Missing LLM config in .env (need API_KEY, BASE_URL, MODEL)");
            return null;
        }
        int maxTokens = getIntProperty(properties, "A2AT_LLM_MAX_TOKENS", 2000);
        double temperature = getDoubleProperty(properties, "A2AT_LLM_TEMPERATURE", 0.0);
        int timeout = getIntProperty(properties, "A2AT_LLM_TIMEOUT_SECONDS", 60);
        return new Config(envFile.toString(), apiKey, baseUrl, model, maxTokens, temperature, timeout);
    }

    private static Map<String, String> parseEnvLines(List<String> lines) {
        Map<String, String> properties = new java.util.HashMap<>();
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int equals = trimmed.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            String key = trimmed.substring(0, equals).trim();
            String value = trimmed.substring(equals + 1).trim();
            if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length() - 1);
            }
            String environmentValue = System.getenv(key);
            properties.put(key, environmentValue != null ? environmentValue : value);
        }
        return properties;
    }

    private static int getIntProperty(Map<String, String> properties, String key, int defaultValue) {
        String value = properties.get(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static double getDoubleProperty(Map<String, String> properties, String key,
                                            double defaultValue) {
        String value = properties.get(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static String buildRequestBody(Config config, String system, String user) {
        try {
            List<Map<String, String>> messages = new ArrayList<>();
            messages.add(Map.of("role", "system", "content", system));
            messages.add(Map.of("role", "user", "content", user));
            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("model", config.model);
            body.put("messages", messages);
            body.put("temperature", config.temperature);
            body.put("max_tokens", config.maxTokens);
            return MAPPER.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build request body", e);
        }
    }

    private static String extractContent(String responseBody) {
        try {
            JsonNode choices = MAPPER.readTree(responseBody).path("choices");
            if (choices.isArray() && !choices.isEmpty()) {
                JsonNode message = choices.get(0).path("message").path("content");
                if (!message.isMissingNode()) {
                    return message.asText();
                }
            }
            return null;
        } catch (Exception e) {
            log.warn("[LlmHelper] Failed to parse response: {}", e.getMessage());
            return null;
        }
    }

    private static final class Config {
        private final String envPath;
        private final String apiKey;
        private final String baseUrl;
        private final String model;
        private final int maxTokens;
        private final double temperature;
        private final int timeoutSeconds;

        private Config(String envPath, String apiKey, String baseUrl, String model,
                       int maxTokens, double temperature, int timeoutSeconds) {
            this.envPath = envPath;
            this.apiKey = apiKey;
            this.baseUrl = baseUrl;
            this.model = model;
            this.maxTokens = maxTokens;
            this.temperature = temperature;
            this.timeoutSeconds = timeoutSeconds;
        }
    }

    private record HttpResponse(int statusCode, String body) {}
}
