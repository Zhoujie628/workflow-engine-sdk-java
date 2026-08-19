/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.a2aproject.sdk.server.config.A2AConfigProvider;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;

class A2AAutoConfigurationTest {

    @Test
    void blockingTimeoutsComeFromProperties() {
        A2AProperties properties = new A2AProperties();
        properties.setAgentTimeoutSeconds(90);
        properties.setConsumptionTimeoutSeconds(12);
        properties.setReconciliationTimeoutSeconds(3);

        A2AConfigProvider provider =
                new A2AAutoConfiguration().a2aConfigProvider(properties);

        assertEquals("90", provider.getValue("a2a.blocking.agent.timeout.seconds"));
        assertEquals("12", provider.getValue("a2a.blocking.consumption.timeout.seconds"));
        assertEquals("3", provider.getValue("a2a.blocking.reconciliation.timeout.seconds"));
    }

    @Test
    void executorUsesConfiguredBounds() {
        A2AProperties properties = new A2AProperties();
        properties.setExecutorCoreSize(2);
        properties.setExecutorMaxSize(4);
        properties.setExecutorQueueCapacity(7);
        properties.setExecutorKeepAliveSeconds(15);

        ExecutorService executor = new A2AAutoConfiguration().agentExecutorPool(properties);
        try {
            ThreadPoolExecutor pool = (ThreadPoolExecutor) executor;
            assertEquals(2, pool.getCorePoolSize());
            assertEquals(4, pool.getMaximumPoolSize());
            assertEquals(7, pool.getQueue().remainingCapacity());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void executorRejectsMaxBelowCore() {
        A2AProperties properties = new A2AProperties();
        properties.setExecutorCoreSize(4);
        properties.setExecutorMaxSize(2);
        assertThrows(
                IllegalArgumentException.class,
                () -> new A2AAutoConfiguration().agentExecutorPool(properties));
    }
}
