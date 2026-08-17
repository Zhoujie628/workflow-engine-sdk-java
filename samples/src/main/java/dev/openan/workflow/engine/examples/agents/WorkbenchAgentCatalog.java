/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.examples.agents;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.openan.workflow.engine.client.AgentCardJacksonModule;

import org.a2aproject.sdk.spec.AgentCard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Loads the downstream AgentCards shared by task execution and extension subscriptions. */
public final class WorkbenchAgentCatalog {
    private static final Logger log = LoggerFactory.getLogger(WorkbenchAgentCatalog.class);
    private static final ObjectMapper mapper =
            new ObjectMapper().registerModule(new AgentCardJacksonModule());
    private static final List<String> AGENT_CARD_RESOURCES =
            List.of(
                    "agentcard/spn_domain_agent_city1.json",
                    "agentcard/spn_domain_agent_city2.json",
                    "agentcard/transport_workbench_agent.json");

    public List<AgentCard> load() {
        Map<String, AgentCard> byName = new LinkedHashMap<>();
        for (String resource : AGENT_CARD_RESOURCES) {
            try {
                try (var input = getClass().getClassLoader().getResourceAsStream(resource)) {
                    if (input == null) {
                        log.warn("[AgentCatalog] CARD_MISSING resource={}", resource);
                        continue;
                    }
                    AgentCard card = mapper.readValue(input, AgentCard.class);
                    byName.put(card.name(), card);
                    log.info(
                            "[AgentCatalog] CARD_LOADED resource={}, agent={}, interfaces={}",
                            resource,
                            card.name(),
                            card.supportedInterfaces() != null
                                    ? card.supportedInterfaces().size()
                                    : 0);
                }
            } catch (Exception e) {
                log.warn(
                        "[AgentCatalog] CARD_FAILED resource={}, errorType={}, message={}",
                        resource,
                        e.getClass().getSimpleName(),
                        e.getMessage(),
                        e);
            }
        }
        return new ArrayList<>(byName.values());
    }
}
