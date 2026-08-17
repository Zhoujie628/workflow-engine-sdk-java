/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.examples.spring;

import dev.openan.workflow.engine.client.A2AJavaClientRuntime;
import dev.openan.workflow.engine.examples.MockGatewayClientRuntime;
import dev.openan.workflow.engine.examples.OrderGatewayClientRuntime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** Creates the configured A2A transport runtime without coupling the agent executor to adapters. */
@Component
public final class ClientRuntimeFactory {
    public enum Mode {
        DIRECT,
        MOCK,
        ORDER;

        static Mode parse(String value) {
            try {
                return valueOf(value == null ? "ORDER" : value.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "Unsupported a2a.transport-mode '" + value + "'; use direct, mock or order",
                        e);
            }
        }
    }

    private final Mode mode;
    private final String mockGatewayUrl;
    private final OrderGatewayClientRuntime.OrderConfig orderConfig;

    public ClientRuntimeFactory(
            @Value("${a2a.transport-mode:order}") String transportMode,
            @Value("${a2a.mock-gateway-url:http://127.0.0.1:26400}") String mockGatewayUrl,
            @Value("${a2a.order.simulator-enabled:false}") boolean orderSimulatorEnabled,
            @Value("${a2a.order.host:}") String orderHost,
            @Value("${a2a.order.port:0}") int orderPort,
            @Value("${a2a.order.username:}") String orderUsername,
            @Value("${a2a.order.password:}") String orderPassword,
            @Value("${a2a.order.client-id:}") String orderClientId,
            @Value("${a2a.order.client-secret:}") String orderClientSecret,
            @Value("${a2a.order.default-ne:}") String defaultNe,
            @Value("${a2a.order.city1-ne:}") String city1Ne,
            @Value("${a2a.order.city2-ne:}") String city2Ne,
            @Value("${a2a.order.login-timeout-seconds:15}") int loginTimeoutSeconds,
            @Value("${a2a.order.timeout-seconds:600}") int timeoutSeconds) {
        this.mode = Mode.parse(transportMode);
        this.mockGatewayUrl = mockGatewayUrl;
        if (mode == Mode.ORDER
                && !orderSimulatorEnabled
                && isBundledSimulatorAddress(orderHost, orderPort)) {
            throw new IllegalArgumentException(
                    "EASTCOM_ORDER_SIMULATOR_ENABLED must be true when using the bundled "
                            + "simulator at 127.0.0.1:26401; otherwise configure the real "
                            + "Eastcom platform host and port");
        }
        this.orderConfig =
                mode == Mode.ORDER
                        ? buildOrderConfig(
                                orderHost,
                                orderPort,
                                orderUsername,
                                orderPassword,
                                orderClientId,
                                orderClientSecret,
                                defaultNe,
                                city1Ne,
                                city2Ne,
                                loginTimeoutSeconds,
                                timeoutSeconds)
                        : null;
    }

    public Mode mode() {
        return mode;
    }

    public A2AJavaClientRuntime create() {
        return switch (mode) {
            case DIRECT -> null;
            case MOCK -> new MockGatewayClientRuntime(mockGatewayUrl);
            case ORDER -> new OrderGatewayClientRuntime(orderConfig);
        };
    }

    private static OrderGatewayClientRuntime.OrderConfig buildOrderConfig(
            String host,
            int port,
            String username,
            String password,
            String clientId,
            String clientSecret,
            String defaultNe,
            String city1Ne,
            String city2Ne,
            int loginTimeoutSeconds,
            int timeoutSeconds) {
        Map<String, String> routes = new LinkedHashMap<>();
        putIfPresent(routes, "SPN Domain Agent City1", city1Ne);
        putIfPresent(routes, "SPN Domain Agent City2", city2Ne);
        return OrderGatewayClientRuntime.OrderConfig.builder()
                .host(required(host, "a2a.order.host"))
                .port(port)
                .username(required(username, "a2a.order.username"))
                .password(required(password, "a2a.order.password"))
                .clientId(blankToNull(clientId))
                .clientSecret(blankToNull(clientSecret))
                .defaultNe(blankToNull(defaultNe))
                .agentNeRoutes(routes)
                .loginTimeoutSeconds(loginTimeoutSeconds)
                .timeoutSeconds(timeoutSeconds)
                .build();
    }

    private static void putIfPresent(Map<String, String> routes, String agent, String ne) {
        if (ne != null && !ne.isBlank()) {
            routes.put(agent, ne);
        }
    }

    private static String required(String value, String property) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(property + " is required in order transport mode");
        }
        return value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static boolean isBundledSimulatorAddress(String host, int port) {
        return port == 26401
                && ("127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host));
    }
}
