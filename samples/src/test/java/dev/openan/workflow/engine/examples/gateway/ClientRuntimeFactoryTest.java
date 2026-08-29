/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.examples.gateway;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.openan.workflow.engine.client.A2AJavaClientRuntime;
import dev.openan.workflow.engine.client.AuthProvider;
import dev.openan.workflow.engine.examples.config.OrderGatewayProperties;
import dev.openan.workflow.engine.examples.config.WorkbenchClientProperties;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class ClientRuntimeFactoryTest {

  @Test
  void directModeDelegatesToTheGenericA2aRuntime() {
    WorkbenchClientProperties workbench = new WorkbenchClientProperties();
    workbench.setTransportMode("direct");
    OrderGatewayProperties order = new OrderGatewayProperties();
    order.setOmcCredentialsPath("missing-order-credentials.json");

    ClientRuntimeFactory factory = new ClientRuntimeFactory(workbench, order);

    assertNull(factory.create());
    assertNull(factory.authProvider());
  }

  @Test
  void orderModeCreatesIndependentRuntimeInstancesForIndependentChannels() throws Exception {
    WorkbenchClientProperties workbench = new WorkbenchClientProperties();
    workbench.setTransportMode("order");
    OrderGatewayProperties order = new OrderGatewayProperties();
    order.setHost("instruction-platform.example");
    order.setPort(18080);
    order.setUsername("workbench");
    order.setPassword("secret");
    order.setClientId("workbench-app");

    ClientRuntimeFactory factory = new ClientRuntimeFactory(workbench, order);
    A2AJavaClientRuntime first = factory.create();
    A2AJavaClientRuntime second = factory.create();
    try {
      assertInstanceOf(OrderGatewayClientRuntime.class, first);
      assertInstanceOf(OrderGatewayClientRuntime.class, second);
      assertNotSame(first, second);
      assertNotNull(factory.authProvider());
    } finally {
      first.close();
      second.close();
    }
  }

  @Test
  void hostAuthProviderWinsWithoutReadingTheBuiltInCredentialFile() {
    WorkbenchClientProperties workbench = new WorkbenchClientProperties();
    workbench.setTransportMode("order");
    OrderGatewayProperties order = configuredOrder();
    order.setOmcCredentialsPath("missing-file-that-must-not-be-read.json");
    AuthProvider hostProvider = (agentName, agentCard, headers) -> headers.put("X-Auth", "host");

    ClientRuntimeFactory factory =
        new ClientRuntimeFactory(workbench, order, Optional.of(hostProvider));

    assertSame(hostProvider, factory.authProvider());
  }

  @Test
  void rejectsHostAuthProviderWhenOrderAuthenticationIsDisabled() {
    WorkbenchClientProperties workbench = new WorkbenchClientProperties();
    workbench.setTransportMode("order");
    OrderGatewayProperties order = configuredOrder();
    order.setOmcAuthEnabled(false);
    AuthProvider hostProvider = (agentName, agentCard, headers) -> {};

    assertThrows(
        IllegalArgumentException.class,
        () -> new ClientRuntimeFactory(workbench, order, Optional.of(hostProvider)));
  }

  @Test
  void disabledOrderAuthenticationDoesNotReadFallbackCredentials() {
    WorkbenchClientProperties workbench = new WorkbenchClientProperties();
    workbench.setTransportMode("order");
    OrderGatewayProperties order = configuredOrder();
    order.setOmcAuthEnabled(false);
    order.setOmcCredentialsPath("missing-order-credentials.json");

    ClientRuntimeFactory factory = new ClientRuntimeFactory(workbench, order);

    assertNull(factory.authProvider());
  }

  @Test
  void builtInOrderAuthenticationRequiresItsOwnCredentialsPath() {
    WorkbenchClientProperties workbench = new WorkbenchClientProperties();
    workbench.setTransportMode("order");
    OrderGatewayProperties order = configuredOrder();
    order.setOmcCredentialsPath(" ");

    assertThrows(IllegalArgumentException.class, () -> new ClientRuntimeFactory(workbench, order));
  }

  @Test
  void springUsesTheQualifiedHostAuthProviderBean() {
    AuthProvider hostProvider =
        (agentName, agentCard, headers) -> headers.put("Authorization", "host-token");
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      context.registerBean(
          WorkbenchClientProperties.class,
          () -> {
            WorkbenchClientProperties workbench = new WorkbenchClientProperties();
            workbench.setTransportMode("order");
            return workbench;
          });
      context.registerBean(OrderGatewayProperties.class, ClientRuntimeFactoryTest::configuredOrder);
      context.registerBean(
          ClientRuntimeFactory.OMC_AUTH_PROVIDER_BEAN_NAME, AuthProvider.class, () -> hostProvider);
      context.register(ClientRuntimeFactory.class);
      context.refresh();

      assertSame(hostProvider, context.getBean(ClientRuntimeFactory.class).authProvider());
    }
  }

  @Test
  void orderModeRequiresClientIdUsedByTheVendorLoadNeResourceApi() {
    WorkbenchClientProperties workbench = new WorkbenchClientProperties();
    workbench.setTransportMode("order");
    OrderGatewayProperties order = new OrderGatewayProperties();
    order.setHost("instruction-platform.example");
    order.setPort(18080);
    order.setUsername("workbench");
    order.setPassword("secret");

    assertThrows(IllegalArgumentException.class, () -> new ClientRuntimeFactory(workbench, order));
  }

  @Test
  void unsupportedModeFailsDuringConfiguration() {
    WorkbenchClientProperties workbench = new WorkbenchClientProperties();
    workbench.setTransportMode("tunnel");

    assertThrows(
        IllegalArgumentException.class,
        () -> new ClientRuntimeFactory(workbench, new OrderGatewayProperties()));
  }

  private static OrderGatewayProperties configuredOrder() {
    OrderGatewayProperties order = new OrderGatewayProperties();
    order.setHost("instruction-platform.example");
    order.setPort(18080);
    order.setUsername("workbench");
    order.setPassword("secret");
    order.setClientId("workbench-app");
    return order;
  }
}
