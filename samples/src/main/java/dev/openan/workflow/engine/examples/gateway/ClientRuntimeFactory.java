/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 *    Licensed under the Apache License, Version 2.0 (the License); you may
 *    not use this file except in compliance with the License. You may obtain
 *    a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an AS IS BASIS, WITHOUT
 *    WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *    License for the specific language governing permissions and limitations
 *    under the License.
 */

package dev.openan.workflow.engine.examples.gateway;

import dev.openan.workflow.engine.client.A2AJavaClientRuntime;
import dev.openan.workflow.engine.examples.config.OrderGatewayProperties;
import dev.openan.workflow.engine.examples.config.WorkbenchClientProperties;

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
    private volatile OrderGatewayClientRuntime sharedOrderRuntime;

    public ClientRuntimeFactory(
            WorkbenchClientProperties workbench,
            OrderGatewayProperties order) {
        Mode parsedMode = Mode.parse(workbench.getTransportMode());
        this.mockGatewayUrl = workbench.getMockGatewayUrl();
        if (parsedMode == Mode.ORDER
                && !order.isSimulatorEnabled()
                && isBundledSimulatorAddress(order.getHost(), order.getPort())) {
            throw new IllegalArgumentException(
                    "EASTCOM_ORDER_SIMULATOR_ENABLED must be true when using the bundled "
                            + "simulator at 127.0.0.1:26401; otherwise configure the real "
                            + "Eastcom platform host and port");
        }
        // TESTING: use HttpClient API even with simulator enabled
        Mode effectiveMode = parsedMode;
        this.mode = effectiveMode;
        this.orderConfig =
                effectiveMode == Mode.ORDER
                        ? buildOrderConfig(
                                order.getHost(),
                                order.getPort(),
                                order.getUsername(),
                                order.getPassword(),
                                order.getClientId(),
                                order.getClientSecret(),
                                order.getDefaultNe(),
                                order.getCity1Ne(),
                                order.getCity2Ne(),
                                order.getLoginTimeoutSeconds(),
                                order.getTimeoutSeconds())
                        : null;
    }

    public Mode mode() {
        return mode;
    }

    public A2AJavaClientRuntime create() {
        return switch (mode) {
            case DIRECT -> null;
            case MOCK -> new MockGatewayClientRuntime(mockGatewayUrl);
            case ORDER -> {
                if (sharedOrderRuntime == null) {
                    sharedOrderRuntime = new OrderGatewayClientRuntime(orderConfig);
                }
                yield sharedOrderRuntime;
            }
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
