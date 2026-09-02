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
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.SecurityRequirement;
import org.a2aproject.sdk.spec.SecurityScheme;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Supplies credentials declared by an AgentCard and loaded from engine credential configuration.
 */
final class CredentialHeaderContributor implements HeaderContributor {
  private static final Logger log = LoggerFactory.getLogger(CredentialHeaderContributor.class);

  private final AgentAuthManager authManager;
  private final AuthProvider authProvider;

  CredentialHeaderContributor(AgentAuthManager authManager, AuthProvider authProvider) {
    this.authManager = authManager;
    this.authProvider = authProvider;
  }

  private static void addCredentialHeader(
      Map<String, String> headers,
      Map<String, Object> schemeConfig,
      String credential) {
    String authHeader = (String) schemeConfig.get("auth_header");
    if (authHeader != null && !authHeader.isEmpty()) {
      String prefix = (String) schemeConfig.getOrDefault("auth_header_prefix", "");
      putHeader(headers, authHeader, prefix + credential);
    } else {
      putHeader(headers, "Authorization", "Bearer " + credential);
    }
    String acceptHeader = (String) schemeConfig.get("accept_header");
    if (acceptHeader != null && !acceptHeader.isEmpty()) {
      putHeader(headers, "Accept", acceptHeader);
    }
  }

  private static void putHeader(Map<String, String> headers, String name, String value) {
    String existing = headers.putIfAbsent(name, value);
    if (existing != null && !existing.equals(value)) {
      throw new SecurityException(
          "Authentication schemes require conflicting values for header " + name);
    }
  }

  @Override
  public Map<String, String> contribute(
      AgentCard agentCard,
      String agentName,
      Map<String, Object> messageMetadata,
      Map<String, String> currentHeaders) {
    Map<String, String> headers = new HashMap<>();
    Map<String, SecurityScheme> securitySchemes = agentCard.securitySchemes();
    List<SecurityRequirement> securityRequirements = agentCard.securityRequirements();
    if (securitySchemes == null
        || securitySchemes.isEmpty()
        || securityRequirements == null
        || securityRequirements.isEmpty()) {
      return headers;
    }

    AgentCredentialService credentialService = authManager.getService(agentName);
    if (credentialService == null) {
      if (authProvider != null) {
        return headers;
      }
      throw new SecurityException(
          "Authentication failed for agent "
              + agentName
              + ": AgentCard requires credentials but none are configured");
    }
    Map<String, Map<String, Object>> schemeConfigs = authManager.getConfig(agentName);
    if (schemeConfigs == null || schemeConfigs.isEmpty()) {
      if (authProvider != null) {
        return headers;
      }
      throw new SecurityException(
          "Authentication failed for agent " + agentName + ": credential configuration is empty");
    }

    java.util.ArrayList<String> failures = new java.util.ArrayList<>();
    for (SecurityRequirement requirement : securityRequirements) {
      Map<String, String> candidate = new HashMap<>();
      Map<String, List<String>> schemes = requirement.schemes();
      String failure = null;
      for (String schemeName : schemes.keySet()) {
        if (!securitySchemes.containsKey(schemeName)) {
          failure = "scheme " + schemeName + " is not declared by the AgentCard";
          break;
        }
        Map<String, Object> schemeConfig = schemeConfigs.get(schemeName);
        if (schemeConfig == null) {
          failure = "no configuration for scheme " + schemeName;
          break;
        }
        String credential = credentialService.getCredential(schemeName, null);
        if (credential == null) {
          failure = "no credential for scheme " + schemeName;
          break;
        }
        try {
          addCredentialHeader(candidate, schemeConfig, credential);
        } catch (SecurityException error) {
          failure = error.getMessage();
          break;
        }
      }
      if (failure == null) {
        log.info(
            "[Auth] Satisfied security requirement for agent {}: {}", agentName, schemes.keySet());
        return candidate;
      }
      failures.add(failure);
    }
    if (authProvider != null) return headers;
    throw new SecurityException(
        "Authentication failed for agent "
            + agentName
            + ": no security requirement can be satisfied ("
            + String.join("; ", failures)
            + ")");
  }
}
