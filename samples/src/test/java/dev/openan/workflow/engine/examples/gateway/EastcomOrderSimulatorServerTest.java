/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.examples.gateway;

import com.eastcom.apollo.orders.internal.shaded.v11x.com.eastcom.apollo.orders.client.core.common.ServerInfo;
import com.eastcom.apollo.orders.internal.shaded.v11x.com.eastcom.apollo.orders.client.core.config.ConfigOption;
import com.eastcom.apollo.orders.internal.shaded.v11x.com.eastcom.apollo.orders.client.httpsession.OrderHttpSessionClient;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class EastcomOrderSimulatorServerTest {

    @Test
    void realVendorClientCanLoginInitializeAndLogout() throws Exception {
        int port = availablePort();
        try (EastcomOrderSimulatorServer server =
                new EastcomOrderSimulatorServer(
                        "127.0.0.1",
                        port,
                        "sim-user",
                        "sim-password",
                        "sim-client",
                        "sim-secret",
                        Map.of("sim-city1", "https://127.0.0.1:26335"))) {
            server.start();
            OrderHttpSessionClient client = new OrderHttpSessionClient();
            client.login(
                    ServerInfo.builder()
                            .host("127.0.0.1")
                            .port(port)
                            .username("sim-user")
                            .password("sim-password")
                            .clientId("sim-client")
                            .clientSecret("sim-secret")
                            .build());
            client.init("sim-city1", true);
            assertTrue(client.isConnected());
            client.logout();
        }
    }

    @Test
    void simulatorCanRestartOnSameAddressAfterClosingVendorConnections() throws Exception {
        int port = availablePort();
        EastcomOrderSimulatorServer first = simulator(port);
        first.start();
        loginInitializeAndLogout(port);
        first.close();

        try (EastcomOrderSimulatorServer restarted = simulator(port)) {
            restarted.start();
        }
    }

    private static EastcomOrderSimulatorServer simulator(int port) {
        return new EastcomOrderSimulatorServer(
                "127.0.0.1",
                port,
                "sim-user",
                "sim-password",
                "sim-client",
                "sim-secret",
                Map.of("sim-city1", "https://127.0.0.1:26335"));
    }

    private static void loginInitializeAndLogout(int port) {
        OrderHttpSessionClient client = new OrderHttpSessionClient();
        client.configuration(ConfigOption.LOGIN_TIMEOUT, "3");
        client.login(
                ServerInfo.builder()
                        .host("127.0.0.1")
                        .port(port)
                        .username("sim-user")
                        .password("sim-password")
                        .clientId("sim-client")
                        .clientSecret("sim-secret")
                        .build());
        client.init("sim-city1", true);
        assertTrue(client.isConnected());
        client.logout();
    }

    private static int availablePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
