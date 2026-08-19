/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 *    Licensed under the Apache License, Version 2.0 (the "License"); you may
 *    not use this file except in compliance with the License. You may obtain
 *    a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *    WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *    License for the specific language governing permissions and limitations
 *    under the License.
 */

package dev.openan.workflow.engine.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the A2A-T Spring Boot starter.
 *
 * <p>Partners set {@code a2at.server.agent-card} to point at their AgentCard JSON file. Everything
 * else (port, SSL, logging) uses the partner's existing Spring Boot configuration.
 */
@ConfigurationProperties(prefix = "a2at.server")
public class A2AProperties {

    /** Whether A2A server autoconfiguration is enabled. Set false to disable all A2A beans. */
    private boolean enabled = true;

    /** Path to the AgentCard JSON file (classpath: or file: prefix supported). */
    private String agentCard = "classpath:agentcard.json";

    /** URL path prefix for A2A endpoints (extracted from AgentCard by default). */
    private String pathPrefix = "/a2a/json";

    /** Timeout for blocking agent execution. */
    private int agentTimeoutSeconds = 30;

    /** Timeout for consuming and persisting events in blocking calls. */
    private int consumptionTimeoutSeconds = 5;

    /** Timeout for TaskStore reconciliation polling in blocking calls. */
    private int reconciliationTimeoutSeconds = 1;

    /** Core server executor thread count. */
    private int executorCoreSize = 8;

    /** Maximum server executor thread count. */
    private int executorMaxSize = 8;

    /** Maximum number of queued server tasks. */
    private int executorQueueCapacity = 100;

    /** Idle timeout for server executor threads above the core size. */
    private int executorKeepAliveSeconds = 60;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getAgentCard() {
        return agentCard;
    }

    public void setAgentCard(String agentCard) {
        this.agentCard = agentCard;
    }

    public String getPathPrefix() {
        return pathPrefix;
    }

    public void setPathPrefix(String pathPrefix) {
        this.pathPrefix = pathPrefix;
    }

    public int getAgentTimeoutSeconds() {
        return agentTimeoutSeconds;
    }

    public void setAgentTimeoutSeconds(int agentTimeoutSeconds) {
        this.agentTimeoutSeconds = positive(agentTimeoutSeconds, "agentTimeoutSeconds");
    }

    public int getConsumptionTimeoutSeconds() {
        return consumptionTimeoutSeconds;
    }

    public void setConsumptionTimeoutSeconds(int consumptionTimeoutSeconds) {
        this.consumptionTimeoutSeconds =
                positive(consumptionTimeoutSeconds, "consumptionTimeoutSeconds");
    }

    public int getReconciliationTimeoutSeconds() {
        return reconciliationTimeoutSeconds;
    }

    public void setReconciliationTimeoutSeconds(int reconciliationTimeoutSeconds) {
        this.reconciliationTimeoutSeconds =
                positive(reconciliationTimeoutSeconds, "reconciliationTimeoutSeconds");
    }

    public int getExecutorCoreSize() {
        return executorCoreSize;
    }

    public void setExecutorCoreSize(int executorCoreSize) {
        this.executorCoreSize = positive(executorCoreSize, "executorCoreSize");
    }

    public int getExecutorMaxSize() {
        return executorMaxSize;
    }

    public void setExecutorMaxSize(int executorMaxSize) {
        this.executorMaxSize = positive(executorMaxSize, "executorMaxSize");
    }

    public int getExecutorQueueCapacity() {
        return executorQueueCapacity;
    }

    public void setExecutorQueueCapacity(int executorQueueCapacity) {
        this.executorQueueCapacity = positive(executorQueueCapacity, "executorQueueCapacity");
    }

    public int getExecutorKeepAliveSeconds() {
        return executorKeepAliveSeconds;
    }

    public void setExecutorKeepAliveSeconds(int executorKeepAliveSeconds) {
        this.executorKeepAliveSeconds =
                positive(executorKeepAliveSeconds, "executorKeepAliveSeconds");
    }

    private static int positive(int value, String name) {
        if (value <= 0) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }
}
