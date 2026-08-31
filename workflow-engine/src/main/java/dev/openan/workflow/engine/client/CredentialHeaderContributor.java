/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
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
      String agentName,
      Map<String, String> headers,
      Map<String, Object> schemeConfig,
      String credential) {
    String authHeader = (String) schemeConfig.get("auth_header");
    if (authHeader != null && !authHeader.isEmpty()) {
      String prefix = (String) schemeConfig.getOrDefault("auth_header_prefix", "");
      headers.put(authHeader, prefix + credential);
      log.info("[Auth] Set header {} for agent {}", authHeader, agentName);
    } else {
      headers.put("Authorization", "Bearer " + credential);
      log.info("[Auth] Set Bearer header for agent {}", agentName);
    }
    String acceptHeader = (String) schemeConfig.get("accept_header");
    if (acceptHeader != null && !acceptHeader.isEmpty()) {
      headers.put("Accept", acceptHeader);
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

    for (SecurityRequirement requirement : securityRequirements) {
      Map<String, List<String>> schemes = requirement.schemes();
      for (String schemeName : schemes.keySet()) {
        Map<String, Object> schemeConfig = schemeConfigs.get(schemeName);
        if (schemeConfig == null) {
          throw new SecurityException(
              "Authentication failed for agent "
                  + agentName
                  + ": no configuration for scheme "
                  + schemeName);
        }
        String credential = credentialService.getCredential(schemeName, null);
        if (credential == null) {
          throw new SecurityException(
              "Authentication failed for agent "
                  + agentName
                  + " (scheme="
                  + schemeName
                  + "), request blocked");
        }
        addCredentialHeader(agentName, headers, schemeConfig, credential);
        return headers;
      }
    }
    return headers;
  }
}
