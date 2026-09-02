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
package dev.openan.workflow.engine.examples.workbench;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.openan.workflow.engine.client.AgentCardJacksonModule;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.a2aproject.sdk.spec.AgentCard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Loads the downstream AgentCards shared by task execution and extension subscriptions. */
public final class WorkbenchAgentCatalog {
  private static final Logger log = LoggerFactory.getLogger(WorkbenchAgentCatalog.class);
  private static final ObjectMapper mapper =
      new ObjectMapper().registerModule(new AgentCardJacksonModule());
  private static final String LOCATIONS_PROPERTY = "A2A_AGENT_CARD_LOCATIONS";
  private static final List<String> DEFAULT_AGENT_CARD_LOCATIONS =
      List.of(
          "classpath:agentcard/spn_domain_agent_city1.json",
          "classpath:agentcard/spn_domain_agent_city2.json",
          "classpath:agentcard/transport_workbench_agent.json");

  private final List<String> locations;
  private final boolean explicitlyConfigured;

  public WorkbenchAgentCatalog() {
    String configured = configuredLocations();
    this.explicitlyConfigured = configured != null;
    this.locations =
        configured == null
            ? DEFAULT_AGENT_CARD_LOCATIONS
            : Arrays.stream(configured.split("\\s*[,;]\\s*"))
                .filter(value -> !value.isBlank())
                .toList();
    if (locations.isEmpty()) {
      throw new IllegalArgumentException(LOCATIONS_PROPERTY + " must contain a location");
    }
  }

  WorkbenchAgentCatalog(List<String> locations) {
    this.locations = List.copyOf(locations);
    this.explicitlyConfigured = true;
  }

  private static String configuredLocations() {
    String value = System.getProperty(LOCATIONS_PROPERTY);
    if (value == null || value.isBlank()) {
      value = System.getenv(LOCATIONS_PROPERTY);
    }
    return value == null || value.isBlank() ? null : value.trim();
  }

  public List<AgentCard> load() {
    Map<String, AgentCard> byName = new LinkedHashMap<>();
    for (String location : locations) {
      try {
        try (InputStream input = open(location)) {
          if (input == null) {
            if (explicitlyConfigured) {
              throw new IllegalArgumentException(
                  "Configured AgentCard does not exist: " + location);
            }
            log.warn("[AgentCatalog] CARD_MISSING location={}", location);
            continue;
          }
          AgentCard card = mapper.readValue(input, AgentCard.class);
          byName.put(card.name(), card);
          log.info(
              "[AgentCatalog] CARD_LOADED location={}, agent={}, interfaces={}",
              location,
              card.name(),
              card.supportedInterfaces() != null ? card.supportedInterfaces().size() : 0);
        }
      } catch (Exception e) {
        if (explicitlyConfigured) {
          throw new IllegalStateException("Failed to load configured AgentCard " + location, e);
        }
        log.warn(
            "[AgentCatalog] CARD_FAILED location={}, errorType={}, message={}",
            location,
            e.getClass().getSimpleName(),
            e.getMessage(),
            e);
      }
    }
    return new ArrayList<>(byName.values());
  }

  private InputStream open(String location) throws Exception {
    if (location.startsWith("classpath:")) {
      String resource = location.substring("classpath:".length());
      return getClass().getClassLoader().getResourceAsStream(resource);
    }
    Path path =
        location.startsWith("file:")
            ? Path.of(URI.create(location))
            : Path.of(location).toAbsolutePath().normalize();
    return Files.isRegularFile(path) ? Files.newInputStream(path) : null;
  }
}
