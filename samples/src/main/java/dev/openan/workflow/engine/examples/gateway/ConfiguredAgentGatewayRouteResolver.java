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

import java.util.Map;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentInterface;

/** Agent-name based route resolver with an optional default NE. */
public final class ConfiguredAgentGatewayRouteResolver implements AgentGatewayRouteResolver {
  private final Map<String, String> agentNeRoutes;
  private final String defaultNe;

  public ConfiguredAgentGatewayRouteResolver(Map<String, String> agentNeRoutes, String defaultNe) {
    this.agentNeRoutes = agentNeRoutes == null ? Map.of() : Map.copyOf(agentNeRoutes);
    this.defaultNe = defaultNe;
  }

  @Override
  public AgentGatewayRoute resolve(AgentCard agentCard) {
    String ne = agentNeRoutes.get(agentCard.name());
    if (ne == null || ne.isBlank()) {
      ne = defaultNe;
    }
    if (ne == null || ne.isBlank()) {
      throw new IllegalArgumentException(
          "No Eastcom NE route configured for agent '" + agentCard.name() + "'");
    }
    if (agentCard.supportedInterfaces() == null || agentCard.supportedInterfaces().isEmpty()) {
      throw new IllegalArgumentException(
          "AgentCard has no supported interface: " + agentCard.name());
    }
    AgentInterface httpInterface =
        agentCard.supportedInterfaces().stream()
            .filter(candidate -> "HTTP+JSON".equalsIgnoreCase(candidate.protocolBinding()))
            .findFirst()
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "Eastcom HTTP forwarding requires an HTTP+JSON "
                            + "interface: "
                            + agentCard.name()));
    return new AgentGatewayRoute(ne, httpInterface);
  }
}
