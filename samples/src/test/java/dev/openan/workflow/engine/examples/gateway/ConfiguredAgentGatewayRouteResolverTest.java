/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.examples.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentCapabilities;
import org.a2aproject.sdk.spec.AgentInterface;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

class ConfiguredAgentGatewayRouteResolverTest {
    @Test
    void resolvesDifferentAgentsToDifferentNes() {
        ConfiguredAgentGatewayRouteResolver resolver =
                new ConfiguredAgentGatewayRouteResolver(
                        Map.of("city1", "ne-1", "city2", "ne-2"), null);
        assertEquals("ne-1", resolver.resolve(card("city1", 26335)).ne());
        assertEquals("ne-2", resolver.resolve(card("city2", 26336)).ne());
        assertEquals("/a2a/json", resolver.resolve(card("city2", 26336)).uriPath());
    }

    @Test
    void failsFastWhenNoRouteExists() {
        ConfiguredAgentGatewayRouteResolver resolver =
                new ConfiguredAgentGatewayRouteResolver(Map.of(), null);
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve(card("city1", 26335)));
    }

    @Test
    void selectsHttpJsonInterfaceInsteadOfAssumingFirstInterface() {
        AgentCard card =
                AgentCard.builder(card("city1", 26335))
                        .supportedInterfaces(
                                List.of(
                                        new AgentInterface(
                                                "JSONRPC", "https://wrong.example/rpc"),
                                        new AgentInterface(
                                                "HTTP+JSON",
                                                "https://127.0.0.1:26335/a2a/json")))
                        .build();
        ConfiguredAgentGatewayRouteResolver resolver =
                new ConfiguredAgentGatewayRouteResolver(Map.of("city1", "ne-1"), null);

        assertEquals("/a2a/json", resolver.resolve(card).uriPath());
    }

    @Test
    void appliesAgentTenantAndRequestTenantOverrideToMessagePath() {
        AgentCard card =
                AgentCard.builder(card("city1", 26335))
                        .supportedInterfaces(
                                List.of(
                                        new AgentInterface(
                                                "HTTP+JSON",
                                                "https://127.0.0.1:26335/a2a/json",
                                                "/tenant-default",
                                                "1.0")))
                        .build();
        ConfiguredAgentGatewayRouteResolver resolver =
                new ConfiguredAgentGatewayRouteResolver(Map.of("city1", "ne-1"), null);
        AgentGatewayRoute route = resolver.resolve(card);

        assertEquals(
                "/a2a/json/tenant-default/message:send", route.messagePath(null, false));
        assertEquals(
                "/a2a/json/tenant-request/message:stream",
                route.messagePath("tenant-request", true));
    }

    private static AgentCard card(String name, int port) {
        return AgentCard.builder()
                .name(name)
                .description("test")
                .version("1")
                .capabilities(AgentCapabilities.builder().streaming(true).build())
                .defaultInputModes(List.of("text/plain"))
                .defaultOutputModes(List.of("text/plain"))
                .skills(List.of())
                .supportedInterfaces(
                        List.of(
                                new AgentInterface(
                                        "HTTP+JSON", "https://127.0.0.1:" + port + "/a2a/json")))
                .build();
    }
}
