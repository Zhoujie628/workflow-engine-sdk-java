/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 *    Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package dev.openan.workflow.engine.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.openan.workflow.engine.util.SensitiveDataRedactor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.a2aproject.sdk.spec.A2AClientHTTPError;
import org.a2aproject.sdk.spec.A2AError;
import org.a2aproject.sdk.spec.A2AErrorCodes;

/** A standard A2A HTTP error projected from the {@code google.rpc.Status} JSON envelope. */
public final class RemoteA2AErrorException extends RuntimeException {
  private static final ObjectMapper JSON =
      new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  private static final TypeReference<List<Map<String, Object>>> DETAIL_TYPE =
      new TypeReference<>() {};

  private final int httpStatus;
  private final int code;
  private final String status;
  private final String reason;
  private final String domain;
  private final List<Map<String, Object>> details;
  private final Map<String, List<String>> responseHeaders;

  private RemoteA2AErrorException(
      int httpStatus,
      int code,
      String status,
      String message,
      String reason,
      String domain,
      List<Map<String, Object>> details,
      Map<String, List<String>> responseHeaders) {
    super(message);
    this.httpStatus = httpStatus;
    this.code = code;
    this.status = status;
    this.reason = reason;
    this.domain = domain;
    this.details = List.copyOf(details);
    this.responseHeaders = SensitiveDataRedactor.safeHeaders(responseHeaders);
  }

  /** Parses a top-level standard A2A error envelope whose embedded code supplies HTTP status. */
  public static RemoteA2AErrorException fromPayload(String payload) {
    return fromResponse(0, payload, Map.of());
  }

  /** Parses a standard A2A error response, preferring an observed non-2xx HTTP status. */
  public static RemoteA2AErrorException fromResponse(
      int observedHttpStatus, String payload, Map<String, List<String>> responseHeaders) {
    if (payload == null || payload.isBlank()) return null;
    JsonNode root;
    try {
      root = JSON.readTree(payload);
    } catch (java.io.IOException ignored) {
      return null;
    }
    if (root == null || !root.isObject() || !root.path("error").isObject()) return null;
    JsonNode error = root.path("error");
    if (!error.path("code").isIntegralNumber() || !error.path("code").canConvertToInt()) {
      return null;
    }
    int code = error.path("code").asInt();
    int httpStatus = isErrorStatus(observedHttpStatus) ? observedHttpStatus : code;
    if (!isErrorStatus(code) || !isErrorStatus(httpStatus)) return null;
    String message = safeText(error, "message");
    if (message.isBlank()) return null;
    String status = safeText(error, "status");
    List<Map<String, Object>> details = safeDetails(error.path("details"));
    String reason = "";
    String domain = "";
    if (error.path("details").isArray()) {
      for (JsonNode detail : error.path("details")) {
        if (!detail.isObject() || !detail.path("reason").isTextual()) continue;
        reason = safeText(detail, "reason");
        domain = safeText(detail, "domain");
        break;
      }
    }
    return new RemoteA2AErrorException(
        httpStatus,
        code,
        status,
        message,
        reason,
        domain,
        details,
        responseHeaders == null ? Map.of() : responseHeaders);
  }

  /** Finds or projects a standard A2A error in a wrapped SDK/transport exception chain. */
  public static RemoteA2AErrorException findIn(Throwable error) {
    Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    for (Throwable current = error; current != null && seen.add(current); current = current.getCause()) {
      if (current instanceof RemoteA2AErrorException remote) return remote;
      if (current instanceof A2AClientHTTPError http) {
        RemoteA2AErrorException parsed =
            fromResponse(http.getCode(), http.getResponseBody(), http.getResponseHeaders());
        if (parsed != null) return parsed;
        return transportOnly(http);
      }
      if (current instanceof A2AError a2a) return fromSdkError(a2a);
    }
    return null;
  }

  private static RemoteA2AErrorException transportOnly(A2AClientHTTPError error) {
    String message = SensitiveDataRedactor.redact(error.getMessage());
    return new RemoteA2AErrorException(
        error.getCode(),
        error.getCode(),
        "",
        message.isBlank() ? "A2A request failed with HTTP " + error.getCode() : message,
        "",
        "",
        List.of(),
        error.getResponseHeaders());
  }

  private static RemoteA2AErrorException fromSdkError(A2AError error) {
    A2AErrorCodes errorCode = A2AErrorCodes.fromCode(error.getCode());
    int httpStatus = errorCode == null ? 500 : errorCode.httpCode();
    String reason = errorCode == null ? "" : errorCode.name();
    String status = errorCode == null ? "INTERNAL" : errorCode.grpcStatus();
    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("@type", "type.googleapis.com/google.rpc.ErrorInfo");
    if (!reason.isBlank()) detail.put("reason", reason);
    detail.put("domain", "a2a-protocol.org");
    if (!error.getDetails().isEmpty()) detail.put("metadata", error.getDetails());
    List<Map<String, Object>> details = safeDetails(JSON.valueToTree(List.of(detail)));
    return new RemoteA2AErrorException(
        httpStatus,
        httpStatus,
        status,
        SensitiveDataRedactor.redact(error.getMessage()),
        reason,
        "a2a-protocol.org",
        details,
        Map.of());
  }

  private static List<Map<String, Object>> safeDetails(JsonNode node) {
    if (!node.isArray()) return List.of();
    try {
      String redacted = SensitiveDataRedactor.redact(JSON.writeValueAsString(node));
      List<Map<String, Object>> converted = JSON.readValue(redacted, DETAIL_TYPE);
      List<Map<String, Object>> result = new ArrayList<>();
      for (Map<String, Object> detail : converted) {
        result.add(Collections.unmodifiableMap(new LinkedHashMap<>(detail)));
      }
      return List.copyOf(result);
    } catch (java.io.IOException ignored) {
      return List.of();
    }
  }

  private static boolean isErrorStatus(int value) {
    return value >= 400 && value <= 599;
  }

  private static String safeText(JsonNode node, String key) {
    return node.path(key).isTextual()
        ? SensitiveDataRedactor.redact(node.path(key).textValue())
        : "";
  }

  /** Stable workflow-facing error code that does not expose SDK exception class names. */
  public String workflowErrorCode() {
    String normalizedReason =
        reason
            .toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "_")
            .replaceAll("^_+|_+$", "");
    if (!normalizedReason.isBlank()) return "a2a." + normalizedReason;
    return "a2a.http." + httpStatus;
  }

  public int getHttpStatus() {
    return httpStatus;
  }

  public int getCode() {
    return code;
  }

  public String getStatus() {
    return status;
  }

  public String getReason() {
    return reason;
  }

  public String getDomain() {
    return domain;
  }

  public List<Map<String, Object>> getDetails() {
    return details;
  }

  public Map<String, List<String>> getResponseHeaders() {
    return responseHeaders;
  }

  public String getRetryAfter() {
    for (Map.Entry<String, List<String>> header : responseHeaders.entrySet()) {
      if ("retry-after".equalsIgnoreCase(header.getKey()) && !header.getValue().isEmpty()) {
        return header.getValue().get(0);
      }
    }
    return "";
  }
}
