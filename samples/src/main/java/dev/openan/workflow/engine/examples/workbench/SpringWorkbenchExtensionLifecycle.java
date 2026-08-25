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

package dev.openan.workflow.engine.examples.workbench;

import dev.openan.workflow.engine.examples.util.EnvResolver;
import dev.openan.workflow.engine.examples.config.WorkbenchClientProperties;
import dev.openan.workflow.engine.examples.workbench.WorkbenchExtensionLifecycle;
import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Keeps Authorization-T/Notification-T protocol lifecycles independent from individual tasks. */
@Component
public final class SpringWorkbenchExtensionLifecycle {
    private static final Logger log =
            LoggerFactory.getLogger(SpringWorkbenchExtensionLifecycle.class);

    private final WorkbenchClientProperties properties;

    private WorkbenchExtensionLifecycle lifecycle;

    public SpringWorkbenchExtensionLifecycle(WorkbenchClientProperties properties) {
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public synchronized void start() {
        if (lifecycle != null && lifecycle.isActive()) {
            return;
        }
        WorkbenchExtensionLifecycle candidate =
                new WorkbenchExtensionLifecycle(
                        resolveCredentialsPath(),
                        properties.isSslVerify(),
                        resolveEnvPath(),
                        null,
                        this::onNotification);
        candidate.start();
        lifecycle = candidate;
    }

    @PreDestroy
    public synchronized void close() {
        if (lifecycle == null) {
            return;
        }
        lifecycle.close();
        lifecycle = null;
    }

    private void onNotification(Map<String, Object> data) {
        Object text = data.get("text");
        log.info(
                "[Notification] EVENT scope=workbench, agent={}, state={}, textChars={}, metadata={}",
                data.get("agent"),
                data.get("state"),
                text != null ? String.valueOf(text).length() : 0,
                data.containsKey("metadata") ? "yes" : "no");
        if (text != null) {
            log.debug("[Notification] Recovery result from {}: {}", data.get("agent"), text);
        }
    }

    private String resolveEnvPath() {
        return properties.getA2atEnvPath() != null && !properties.getA2atEnvPath().isBlank()
                ? properties.getA2atEnvPath()
                : EnvResolver.resolveEnvPath();
    }

    private String resolveCredentialsPath() {
        if (properties.getCredentialsPath() != null
                && !properties.getCredentialsPath().isBlank()) {
            return properties.getCredentialsPath();
        }
        var resource = getClass().getClassLoader().getResource("spn_agent_credentials.json");
        return resource != null ? "classpath:spn_agent_credentials.json" : null;
    }
}
