/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.examples.gateway;

import dev.openan.workflow.engine.examples.gateway.EastcomOrderSimulatorServer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/** Spring-managed lifecycle for the sample-only Eastcom protocol simulator. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "a2a.order.simulator-enabled", havingValue = "true")
public class EastcomOrderSimulatorConfiguration {

    @Bean(destroyMethod = "close")
    EastcomOrderSimulatorServer eastcomOrderSimulatorServer(
            @Value("${a2a.order.host}") String host,
            @Value("${a2a.order.port}") int port,
            @Value("${a2a.order.username}") String username,
            @Value("${a2a.order.password}") String password,
            @Value("${a2a.order.client-id:}") String clientId,
            @Value("${a2a.order.client-secret:}") String clientSecret,
            @Value("${a2a.order.city1-ne:sim-city1}") String city1Ne,
            @Value("${a2a.order.city2-ne:sim-city2}") String city2Ne) {
        EastcomOrderSimulatorServer server =
                new EastcomOrderSimulatorServer(
                        host,
                        port,
                        username,
                        password,
                        clientId,
                        clientSecret,
                        Map.of(
                                city1Ne, "https://127.0.0.1:26335",
                                city2Ne, "https://127.0.0.1:26336"));
        server.start();
        return server;
    }
}
