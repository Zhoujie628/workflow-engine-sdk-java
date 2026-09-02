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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sample LLM helper that calls an OpenAI-compatible chat completions endpoint.
 *
 * <p>This helper intentionally lives in samples: the workflow engine itself does not call an LLM
 * directly. Callers must provide a deterministic fallback for missing configuration and transient
 * model failures.
 */
public final class LlmHelper {

  private static final Logger log = LoggerFactory.getLogger(LlmHelper.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static volatile Config cached;

  private LlmHelper() {}

  /**
   * Calls the configured OpenAI-compatible chat API and returns the reply text.
   *
   * <p>Demo-only degradation contract: when the LLM is not configured or disabled, the HTTP call
   * returns a non-200 status, the reply is empty or incomplete (a non-null {@code finishReason}
   * other than {@code stop}), or the call throws, this method returns {@code fallback} and logs
   * an {@code LLM_FALLBACK} line. The return type carries no signal distinguishing a real reply
   * from the fallback, so callers report success either way. Production code should treat
   * generation failure as a business failure instead of adopting this pattern.
   *
   * @param envPath path to the .env file holding the LLM configuration
   * @param system system prompt, may be null
   * @param user user prompt
   * @param fallback text returned on any failure; see the degradation contract above
   */
  public static String text(String envPath, String system, String user, String fallback) {
    Config config = resolve(envPath);
    if (config == null) {
      log.info("[LlmHelper] LLM_FALLBACK reason=not_configured_or_disabled");
      return fallback;
    }
    long started = System.nanoTime();
    try {
      log.info(
          "[LlmHelper] LLM_CALL_START model={}, inputChars={}",
          config.model,
          (system != null ? system.length() : 0) + (user != null ? user.length() : 0));
      String body = buildRequestBody(config, system, user);
      HttpResponse response = post(config, body);
      if (response.statusCode != 200) {
        log.warn(
            "[LlmHelper] LLM_FALLBACK reason=http_status, status={}, elapsedMs={}",
            response.statusCode,
            elapsedMillis(started));
        return fallback;
      }
      ModelReply reply = extractReply(response.body);
      String content = reply != null ? reply.content() : null;
      if (content == null || content.isBlank()) {
        log.warn(
            "[LlmHelper] LLM_FALLBACK reason=empty_reply, elapsedMs={}", elapsedMillis(started));
        return fallback;
      }
      if (reply.finishReason() != null && !"stop".equalsIgnoreCase(reply.finishReason())) {
        log.warn(
            "[LlmHelper] LLM_FALLBACK reason=incomplete_reply, finishReason={}, outputChars={}, elapsedMs={}",
            reply.finishReason(),
            content.length(),
            elapsedMillis(started));
        return fallback;
      }
      log.info(
          "[LlmHelper] LLM_CALL_DONE model={}, finishReason={}, outputChars={}, elapsedMs={}",
          config.model,
          reply.finishReason(),
          content.length(),
          elapsedMillis(started));
      return content;
    } catch (Exception e) {
      log.warn(
          "[LlmHelper] LLM_FALLBACK reason=call_failed, errorType={}, elapsedMs={}, message={}",
          e.getClass().getSimpleName(),
          elapsedMillis(started),
          e.getMessage());
      return fallback;
    }
  }

  private static long elapsedMillis(long startedNanos) {
    return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
  }

  private static HttpResponse post(Config config, String body) throws IOException {
    HttpURLConnection connection =
        (HttpURLConnection)
            URI.create(config.baseUrl + "/v1/chat/completions").toURL().openConnection();
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

  private static double getDoubleProperty(
      Map<String, String> properties, String key, double defaultValue) {
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

  private static ModelReply extractReply(String responseBody) {
    try {
      JsonNode choices = MAPPER.readTree(responseBody).path("choices");
      if (choices.isArray() && !choices.isEmpty()) {
        JsonNode choice = choices.get(0);
        JsonNode message = choice.path("message").path("content");
        if (!message.isMissingNode()) {
          JsonNode finishReason = choice.path("finish_reason");
          return new ModelReply(
              message.asText(),
              finishReason.isMissingNode() || finishReason.isNull() ? null : finishReason.asText());
        }
      }
      return null;
    } catch (Exception e) {
      log.warn("[LlmHelper] Failed to parse response: {}", e.getMessage());
      return null;
    }
  }

  private record Config(
      String envPath,
      String apiKey,
      String baseUrl,
      String model,
      int maxTokens,
      double temperature,
      int timeoutSeconds) {}

  private record HttpResponse(int statusCode, String body) {}

  private record ModelReply(String content, String finishReason) {}
}
