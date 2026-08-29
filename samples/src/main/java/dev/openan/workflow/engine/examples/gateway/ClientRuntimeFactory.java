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
import dev.openan.workflow.engine.client.AuthProvider;
import dev.openan.workflow.engine.examples.config.OrderGatewayProperties;
import dev.openan.workflow.engine.examples.config.WorkbenchClientProperties;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/** Creates the configured A2A transport runtime without coupling the agent executor to adapters. */
@Component
public final class ClientRuntimeFactory {
  /** Bean name used by a host application to supply its own OMC authentication provider. */
  public static final String OMC_AUTH_PROVIDER_BEAN_NAME = "workflowOmcAuthProvider";

  public enum Mode {
    DIRECT,
    MOCK,
    ORDER;

    static Mode parse(String value) {
      try {
        return valueOf(value == null ? "ORDER" : value.trim().toUpperCase());
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException(
            "Unsupported a2a.transport-mode '" + value + "'; use direct, mock or order", e);
      }
    }
  }

  private final Mode mode;
  private final String mockGatewayUrl;
  private final OrderGatewayClientRuntime.OrderConfig orderConfig;
  private final AuthProvider authProvider;

  @Autowired
  public ClientRuntimeFactory(
      WorkbenchClientProperties workbench,
      OrderGatewayProperties order,
      @Qualifier(OMC_AUTH_PROVIDER_BEAN_NAME) Optional<AuthProvider> hostAuthProvider) {
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
    this.mode = parsedMode;
    this.orderConfig =
        parsedMode == Mode.ORDER
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
    this.authProvider = resolveAuthProvider(parsedMode, order, hostAuthProvider);
  }

  ClientRuntimeFactory(WorkbenchClientProperties workbench, OrderGatewayProperties order) {
    this(workbench, order, Optional.empty());
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

  /** Shared, thread-safe authentication provider for all independently scoped transports. */
  public AuthProvider authProvider() {
    return authProvider;
  }

  private static AuthProvider resolveAuthProvider(
      Mode mode, OrderGatewayProperties order, Optional<AuthProvider> hostAuthProvider) {
    if (mode != Mode.ORDER) {
      return null;
    }
    if (!order.isOmcAuthEnabled()) {
      if (hostAuthProvider.isPresent()) {
        throw new IllegalArgumentException(
            "A host OMC AuthProvider is configured, but a2a.order.omc-auth-enabled is false");
      }
      return null;
    }
    if (hostAuthProvider.isPresent()) {
      return hostAuthProvider.get();
    }
    return new EastcomAuthProvider(
        new EastcomTokenService(order, requiredOrderCredentialsPath(order)),
        order.getOmcRequestAuthHeader(),
        order.getOmcRequestAuthScheme());
  }

  private static String requiredOrderCredentialsPath(OrderGatewayProperties order) {
    String path = order.getOmcCredentialsPath();
    if (path == null || path.isBlank()) {
      throw new IllegalArgumentException(
          "a2a.order.omc-credentials-path is required when the built-in Eastcom AuthProvider is"
              + " used");
    }
    return path.trim();
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
        .clientId(required(clientId, "a2a.order.client-id"))
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
    return port == 26401 && ("127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host));
  }
}
