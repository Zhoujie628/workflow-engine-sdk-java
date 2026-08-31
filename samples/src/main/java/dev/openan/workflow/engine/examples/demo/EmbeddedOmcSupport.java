/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.examples.demo;

import dev.openan.workflow.engine.examples.server.LocalServerAddress;
import dev.openan.workflow.engine.examples.workbench.WorkbenchAgentCatalog;
import java.util.List;
import org.a2aproject.sdk.spec.AgentCard;

/** Preflights both demo OMCs before either local server or business executor starts. */
final class EmbeddedOmcSupport {
  private static final List<String> AGENTS =
      List.of("SPN Domain Agent City1", "SPN Domain Agent City2");

  private EmbeddedOmcSupport() {}

  static boolean enabled(String[] args, boolean defaultValue) {
    String configured = null;
    for (String arg : args) {
      if (arg.startsWith("--a2a.embedded-omc-enabled=")) {
        configured = arg.substring("--a2a.embedded-omc-enabled=".length());
        if (configured.isBlank()) {
          throw new IllegalArgumentException("--a2a.embedded-omc-enabled must be true or false");
        }
      }
    }
    if (configured == null) configured = System.getProperty("A2A_EMBEDDED_OMC_ENABLED");
    if (configured == null || configured.isBlank()) configured = System.getenv("A2A_EMBEDDED_OMC_ENABLED");
    if (configured == null || configured.isBlank()) return defaultValue;
    if ("true".equalsIgnoreCase(configured.trim())) return true;
    if ("false".equalsIgnoreCase(configured.trim())) return false;
    throw new IllegalArgumentException("A2A_EMBEDDED_OMC_ENABLED must be true or false");
  }

  static List<AgentCard> prepare(boolean enabled) {
    return enabled ? validateTargets(new WorkbenchAgentCatalog().load()) : List.of();
  }

  static List<AgentCard> validateTargets(List<AgentCard> cards) {
    return AGENTS.stream()
        .map(name -> {
          AgentCard card = cards.stream().filter(candidate -> name.equals(candidate.name()))
              .findFirst().orElseThrow(() -> new IllegalArgumentException(
                  "Embedded OMC AgentCard missing: " + name
                      + "; provide both demo cards or set A2A_EMBEDDED_OMC_ENABLED=false"));
          if (card.supportedInterfaces() == null || card.supportedInterfaces().isEmpty()) {
            throw new IllegalArgumentException("Embedded OMC AgentCard has no interface: " + name);
          }
          LocalServerAddress.requireLocalEndpoint(
              card.supportedInterfaces().get(0).url(), "Embedded OMC " + name);
          return card;
        })
        .toList();
  }
}
