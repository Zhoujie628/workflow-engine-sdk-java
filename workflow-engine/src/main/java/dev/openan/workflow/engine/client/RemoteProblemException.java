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
package dev.openan.workflow.engine.client;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * A remote problem response, including errors sent as SSE data after HTTP 200 headers.
 * Known credential fields are redacted using the same rules as protocol logging.
 */
public final class RemoteProblemException extends RuntimeException {
  private static final ObjectMapper JSON =
      new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  private final int status;
  private final String title;
  private final String detail;
  private final String type;
  private final String timestamp;

  private RemoteProblemException(JsonNode problem) {
    super("Remote agent error (status=" + problem.path("status").asInt() + "): "
        + text(problem, "title")
        + (text(problem, "title").isEmpty() ? "" : " — ") + text(problem, "detail"));
    status = problem.path("status").asInt();
    title = text(problem, "title");
    detail = text(problem, "detail");
    type = text(problem, "type");
    timestamp = text(problem, "timestamp");
  }

  /** Returns a top-level problem error, or null for ordinary A2A events and incomplete JSON. */
  public static RemoteProblemException fromPayload(String payload) {
    if (payload == null || payload.isBlank()) return null;
    JsonNode node;
    try {
      node = JSON.readTree(payload);
    } catch (java.io.IOException ignored) {
      return null;
    }
    if (node == null || !node.isObject() || !node.path("status").isIntegralNumber()
        || !node.path("status").canConvertToInt()) return null;
    int status = node.path("status").asInt();
    if (status < 400 || status > 599
        || (text(node, "title").isBlank() && text(node, "detail").isBlank())) return null;
    for (String envelope : new String[] {"task", "message", "statusUpdate", "artifactUpdate", "result", "error"}) {
      if (node.has(envelope)) return null;
    }
    return new RemoteProblemException(node);
  }

  private static String text(JsonNode node, String key) {
    return node.path(key).isTextual() ? WireLog.redact(node.path(key).textValue()) : "";
  }

  /** Finds a problem preserved in a transport exception's cause chain, without looping on cycles. */
  public static RemoteProblemException findIn(Throwable error) {
    var seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable, Boolean>());
    for (Throwable current = error; current != null && seen.add(current); current = current.getCause()) {
      if (current instanceof RemoteProblemException problem) return problem;
    }
    return null;
  }

  /** Problem status, which can differ from the HTTP envelope status. */
  public int getStatus() { return status; }
  /** Remote error title, or an empty string. */
  public String getTitle() { return title; }
  /** Remote business failure detail. */
  public String getDetail() { return detail; }
  /** Remote problem type, preserved even when empty. */
  public String getType() { return type; }
  /** Remote timestamp when supplied. */
  public String getTimestamp() { return timestamp; }
}
