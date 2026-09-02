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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.a2aproject.sdk.client.transport.spi.interceptors.ClientCallContext;
import org.a2aproject.sdk.spec.AgentCard;

/** Builds the final outbound call context from independent header contributors. */
final class ClientCallContextFactory {
  private final List<HeaderContributor> contributors;

  ClientCallContextFactory(HeaderContributor... contributors) {
    this.contributors = List.of(contributors);
  }

  static void mergeHeaders(
      String agentName, Map<String, String> destination, Map<String, String> additionalHeaders) {
    for (Map.Entry<String, String> entry : additionalHeaders.entrySet()) {
      String existingKey =
          destination.keySet().stream()
              .filter(key -> key.equalsIgnoreCase(entry.getKey()))
              .findFirst()
              .orElse(null);
      String existing = existingKey != null ? destination.get(existingKey) : null;
      if (existing != null && !existing.equals(entry.getValue())) {
        throw new SecurityException(
            "Authentication header conflict for agent " + agentName + ": " + entry.getKey());
      }
      if (existingKey == null) {
        destination.put(entry.getKey(), entry.getValue());
      }
    }
  }

  ClientCallContext create(
      AgentCard agentCard, String agentName, Map<String, Object> messageMetadata) {
    Map<String, String> headers = new HashMap<>();
    for (HeaderContributor contributor : contributors) {
      Map<String, String> contributed =
          contributor.contribute(agentCard, agentName, messageMetadata, headers);
      mergeHeaders(agentName, headers, contributed);
    }
    return new ClientCallContext(new HashMap<>(), headers);
  }
}
