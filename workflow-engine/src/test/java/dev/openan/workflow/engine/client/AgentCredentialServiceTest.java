/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;

import org.a2aproject.sdk.client.transport.spi.interceptors.ClientCallContext;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

class AgentCredentialServiceTest {

    @Test
    void formLoginEncodesKeysAndValuesAsUtf8() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/token",
                exchange -> {
                    requestBody.set(
                            new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    byte[] response = "{\"access_token\":\"token-1\"}".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, response.length);
                    exchange.getResponseBody().write(response);
                    exchange.close();
                });
        server.start();
        try {
            Map<String, Object> scheme = new HashMap<>();
            scheme.put("login_url", "http://127.0.0.1:" + server.getAddress().getPort() + "/token");
            scheme.put("content_type", "application/x-www-form-urlencoded");
            scheme.put("token_field", "access_token");
            scheme.put("request_fields", Map.of("user name", "a+b", "password", "密&码="));
            AgentCredentialService service =
                    new AgentCredentialService(
                            "agent", Map.of("bearer", scheme), HttpClient.newHttpClient());

            String token =
                    service.getCredential(
                            "bearer", new ClientCallContext(new HashMap<>(), new HashMap<>()));

            assertEquals("token-1", token);
            assertTrue(requestBody.get().contains("user+name=a%2Bb"));
            assertTrue(requestBody.get().contains("password=%E5%AF%86%26%E7%A0%81%3D"));
        } finally {
            server.stop(0);
        }
    }
}
