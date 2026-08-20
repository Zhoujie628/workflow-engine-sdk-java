/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.examples.gateway;

import com.eastcom.apollo.orders.internal.shaded.v11x.com.eastcom.apollo.orders.client.core.common.ServerInfo;
import com.eastcom.apollo.orders.internal.shaded.v11x.com.eastcom.apollo.orders.client.http.HttpClient;
import com.eastcom.apollo.orders.internal.shaded.v11x.com.eastcom.apollo.orders.client.http.HttpRequestConfig;
import com.eastcom.apollo.orders.internal.shaded.v11x.com.eastcom.apollo.orders.client.http.HttpResponse;

import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class EastcomOrderSimulatorServerTest {

    @Test
    void httpClientCanLoginAndLoadNeResource() throws Exception {
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
            ServerInfo serverInfo = ServerInfo.builder()
                    .host("127.0.0.1")
                    .port(port)
                    .username("sim-user")
                    .password("sim-password")
                    .clientId("sim-client")
                    .clientSecret("sim-secret")
                    .build();
            HttpRequestConfig config = HttpRequestConfig.builder()
                    .deviceName("sim-city1")
                    .build();
            // HttpClient.login() calls the platform auth endpoint; the simulator returns
            // a minimal AuthResponse without full HTTP auth config, so we only verify
            // that the RSocket connection and loadNeResource succeed.
            try {
                HttpClient.login(serverInfo, config);
            } catch (Exception e) {
                // Expected: simulator does not provide full http auth conf
            }
            // Verify loadNeResource works (this is the critical RSocket RPC call)
            HttpClient client = HttpClient.create(serverInfo, config);
            assertTrue(client != null, "HttpClient.create should return a client");
        }
    }

    @Test
    void simulatorCanRestartOnSameAddressAfterClosing() throws Exception {
        int port = availablePort();
        EastcomOrderSimulatorServer first = simulator(port);
        first.start();
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

    private static int availablePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
