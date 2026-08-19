/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.examples.workbench;

import dev.openan.workflow.engine.examples.util.EnvResolver;
import dev.openan.workflow.engine.examples.config.WorkbenchClientProperties;
import dev.openan.workflow.engine.examples.workbench.WorkbenchExtensionLifecycle;
import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.Map;

import dev.openan.workflow.engine.examples.gateway.ClientRuntimeFactory;
/** Keeps Authorization-T/Notification-T pre-positioning independent from individual tasks. */
@Component
public final class SpringWorkbenchExtensionLifecycle {
    private static final Logger log =
            LoggerFactory.getLogger(SpringWorkbenchExtensionLifecycle.class);

    private final ClientRuntimeFactory runtimeFactory;
    private final ConfigurableListableBeanFactory beanFactory;
    private final WorkbenchClientProperties properties;

    private WorkbenchExtensionLifecycle lifecycle;

    public SpringWorkbenchExtensionLifecycle(
            ClientRuntimeFactory runtimeFactory,
            ConfigurableListableBeanFactory beanFactory,
            WorkbenchClientProperties properties) {
        this.runtimeFactory = runtimeFactory;
        this.beanFactory = beanFactory;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public synchronized void start() {
        if (lifecycle != null && lifecycle.isActive()) {
            return;
        }
        if (beanFactory.containsBean("eastcomOrderSimulatorServer")) {
            beanFactory.registerDependentBean(
                    "eastcomOrderSimulatorServer", "springWorkbenchExtensionLifecycle");
        }
        WorkbenchExtensionLifecycle candidate =
                new WorkbenchExtensionLifecycle(
                        resolveCredentialsPath(),
                        properties.isSslVerify(),
                        resolveEnvPath(),
                        runtimeFactory::create,
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
        return resource != null ? new File(resource.getPath()).getAbsolutePath() : null;
    }
}
