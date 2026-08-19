/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.openan.workflow.engine.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.net.URL;

class AgentAuthManagerTest {

    @Test
    void loadsCredentialsFromClasspathLocation() throws Exception {
        URL resource = getClass().getClassLoader().getResource("test-agent-credentials.json");
        assertNotNull(resource, "test resource must exist on classpath");
        AgentAuthManager manager = new AgentAuthManager(resource.getPath());

        var config = manager.getConfig("Test Agent");

        assertNotNull(config);
        assertEquals(
                "https://auth.example.test/token",
                config.get("bearerAuth").get("login_url"));
    }

    @Test
    void loadsCredentialsFromClasspathPrefix() {
        AgentAuthManager manager =
                new AgentAuthManager("classpath:test-agent-credentials.json");

        var config = manager.getConfig("Test Agent");

        assertNotNull(config);
        assertEquals(
                "https://auth.example.test/token",
                config.get("bearerAuth").get("login_url"));
    }

    @Test
    void missingFileFailsClosed() {
        assertThrows(
                IllegalStateException.class,
                () -> new AgentAuthManager("/nonexistent/missing-agent-credentials.json"));
    }

    @Test
    void missingClasspathResourceFailsClosed() {
        assertThrows(
                IllegalStateException.class,
                () -> new AgentAuthManager("classpath:nonexistent-credentials.json"));
    }
}
