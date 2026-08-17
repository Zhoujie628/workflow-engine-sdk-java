/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.openan.workflow.engine.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

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
    void missingFileDoesNotCreateCredentials() {
        AgentAuthManager manager =
                new AgentAuthManager("/nonexistent/missing-agent-credentials.json");

        assertNull(manager.getConfig("Test Agent"));
    }
}
