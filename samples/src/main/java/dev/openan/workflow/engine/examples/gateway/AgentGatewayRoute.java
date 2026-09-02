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
package dev.openan.workflow.engine.examples.gateway;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.a2aproject.sdk.spec.AgentInterface;
import org.a2aproject.sdk.spec.util.Utils;

/** Immutable routing result used by the Eastcom gateway adapter. */
public record AgentGatewayRoute(String ne, AgentInterface agentInterface) {
  public AgentGatewayRoute {
    if (ne == null || ne.isBlank()) {
      throw new IllegalArgumentException("Gateway NE must not be blank");
    }
    if (agentInterface == null) {
      throw new IllegalArgumentException("Agent interface must not be null");
    }
  }

  public static AgentGatewayRoute fromTargetUrl(String ne, String targetUrl) {
    return new AgentGatewayRoute(ne, new AgentInterface("HTTP+JSON", targetUrl));
  }

  private static String normalizePath(String path) {
    if (path == null || path.isBlank()) {
      return "/";
    }
    return path.startsWith("/") ? path : "/" + path;
  }

  public String uriPath() {
    return normalizePath(URI.create(agentInterface.url()).getRawPath());
  }

  public boolean https() {
    return "https".equalsIgnoreCase(URI.create(agentInterface.url()).getScheme());
  }

  /** Builds the A2A REST operation path with the request or AgentInterface tenant. */
  public String messagePath(String requestTenant, boolean streaming) {
    return basePath(requestTenant) + (streaming ? "/message:stream" : "/message:send");
  }

  /** Builds the A2A task collection path used by the standard list-tasks operation. */
  public String taskCollectionPath(String requestTenant) {
    return basePath(requestTenant) + "/tasks";
  }

  /** Builds an A2A task-management path from the same advertised AgentInterface base. */
  public String taskPath(String requestTenant, String taskId, String operationSuffix) {
    if (taskId == null || taskId.isBlank()) {
      throw new IllegalArgumentException("taskId must not be blank");
    }
    String encodedTaskId = URLEncoder.encode(taskId, StandardCharsets.UTF_8).replace("+", "%20");
    return basePath(requestTenant)
        + "/tasks/"
        + encodedTaskId
        + (operationSuffix != null ? operationSuffix : "");
  }

  private String basePath(String requestTenant) {
    String baseUrl = Utils.buildBaseUrl(agentInterface, requestTenant);
    String basePath = normalizePath(URI.create(baseUrl).getRawPath());
    String withoutTrailingSlash =
        basePath.endsWith("/") && basePath.length() > 1
            ? basePath.substring(0, basePath.length() - 1)
            : basePath;
    return withoutTrailingSlash;
  }
}
