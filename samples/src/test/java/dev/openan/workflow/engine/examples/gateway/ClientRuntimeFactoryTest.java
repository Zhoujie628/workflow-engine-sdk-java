/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.examples.gateway;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.openan.workflow.engine.client.A2AJavaClientRuntime;
import dev.openan.workflow.engine.examples.config.OrderGatewayProperties;
import dev.openan.workflow.engine.examples.config.WorkbenchClientProperties;

import org.junit.jupiter.api.Test;

class ClientRuntimeFactoryTest {

    @Test
    void directModeDelegatesToTheGenericA2aRuntime() {
        WorkbenchClientProperties workbench = new WorkbenchClientProperties();
        workbench.setTransportMode("direct");

        ClientRuntimeFactory factory =
                new ClientRuntimeFactory(workbench, new OrderGatewayProperties());

        assertNull(factory.create());
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

        ClientRuntimeFactory factory = new ClientRuntimeFactory(workbench, order);
        A2AJavaClientRuntime first = factory.create();
        A2AJavaClientRuntime second = factory.create();
        try {
            assertInstanceOf(OrderGatewayClientRuntime.class, first);
            assertInstanceOf(OrderGatewayClientRuntime.class, second);
            assertNotSame(first, second);
        } finally {
            first.close();
            second.close();
        }
    }

    @Test
    void unsupportedModeFailsDuringConfiguration() {
        WorkbenchClientProperties workbench = new WorkbenchClientProperties();
        workbench.setTransportMode("tunnel");

        assertThrows(
                IllegalArgumentException.class,
                () -> new ClientRuntimeFactory(workbench, new OrderGatewayProperties()));
    }
}
