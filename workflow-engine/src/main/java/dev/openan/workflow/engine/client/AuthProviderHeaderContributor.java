/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.openan.workflow.engine.client;

import java.util.HashMap;
import java.util.Map;
import org.a2aproject.sdk.spec.AgentCard;

/** Supplies headers from the host-provided {@link AuthProvider}. */
final class AuthProviderHeaderContributor implements HeaderContributor {
  private final AuthProvider authProvider;

  AuthProviderHeaderContributor(AuthProvider authProvider) {
    this.authProvider = authProvider;
  }

  @Override
  public Map<String, String> contribute(
      AgentCard agentCard,
      String agentName,
      Map<String, Object> messageMetadata,
      Map<String, String> currentHeaders) {
    Map<String, String> headers = new HashMap<>();
    if (authProvider != null) {
      authProvider.applyAuth(agentName, agentCard, headers);
    }
    return headers;
  }
}
