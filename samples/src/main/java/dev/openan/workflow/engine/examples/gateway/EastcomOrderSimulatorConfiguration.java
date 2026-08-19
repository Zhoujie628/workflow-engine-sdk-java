/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.examples.gateway;

import dev.openan.workflow.engine.examples.gateway.EastcomOrderSimulatorServer;
import dev.openan.workflow.engine.examples.config.OrderGatewayProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/** Spring-managed lifecycle for the sample-only Eastcom protocol simulator. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "a2a.order.simulator-enabled", havingValue = "true")
public class EastcomOrderSimulatorConfiguration {

    @Bean(destroyMethod = "close")
    EastcomOrderSimulatorServer eastcomOrderSimulatorServer(OrderGatewayProperties properties) {
        EastcomOrderSimulatorServer server =
                new EastcomOrderSimulatorServer(
                        properties.getHost(),
                        properties.getPort(),
                        properties.getUsername(),
                        properties.getPassword(),
                        properties.getClientId(),
                        properties.getClientSecret(),
                        Map.of(
                                properties.getCity1Ne(), "https://127.0.0.1:26335",
                                properties.getCity2Ne(), "https://127.0.0.1:26336"));
        server.start();
        return server;
    }
}
