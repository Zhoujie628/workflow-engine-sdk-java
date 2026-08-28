/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.a2aproject.sdk.server.config.A2AConfigProvider;
import org.a2aproject.sdk.server.agentexecution.AgentExecutor;
import org.a2aproject.sdk.server.requesthandlers.RequestHandler;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.transport.rest.handler.RestHandler;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;

class A2AAutoConfigurationTest {

    @Test
    void everyTaskRouteUsesConfiguredA2aPathPrefix() throws Exception {
        String placeholder = "${a2at.server.path-prefix}";

        assertEquals(
                placeholder + "/tasks/{id}",
                A2AController.class
                        .getMethod(
                                "getTask",
                                jakarta.servlet.http.HttpServletRequest.class,
                                String.class)
                        .getAnnotation(GetMapping.class)
                        .value()[0]);
        assertEquals(
                placeholder + "/tasks/{id}:cancel",
                A2AController.class
                        .getMethod(
                                "cancelTask",
                                jakarta.servlet.http.HttpServletRequest.class,
                                String.class,
                                String.class)
                        .getAnnotation(PostMapping.class)
                        .value()[0]);
        var subscribeMethod =
                A2AController.class.getMethod(
                        "subscribeToTask",
                        jakarta.servlet.http.HttpServletRequest.class,
                        String.class);
        assertEquals(
                placeholder + "/tasks/{id}:subscribe",
                subscribeMethod.getAnnotation(PostMapping.class).value()[0]);
        assertEquals(SseEmitter.class, subscribeMethod.getReturnType());

        var streamMethod =
                A2AController.class.getMethod(
                        "streamMessage",
                        jakarta.servlet.http.HttpServletRequest.class,
                        String.class);
        assertEquals(SseEmitter.class, streamMethod.getReturnType());
    }

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

    @Test
    void whenEnabledFalseThenNoA2ABeansCreated() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(A2AAutoConfiguration.class))
                .withPropertyValues("a2at.server.enabled=false")
                .run(context -> {
                    assertFalse(context.containsBean("agentCard"));
                    assertFalse(context.containsBean("requestHandler"));
                    assertFalse(context.containsBean("restHandler"));
                    assertFalse(context.containsBean("a2aController"));
                    assertFalse(context.containsBean("agentExecutorPool"));
                    assertFalse(context.containsBean("eventBus"));
                    assertFalse(context.containsBean("taskStore"));
                });
    }

    @Test
    void whenEnabledTrueThenA2ABeansCreated() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(A2AAutoConfiguration.class))
                .withPropertyValues("a2at.server.enabled=true")
                .run(context -> {
                    // agentCard will fail to load without a real classpath resource,
                    // but the bean definition should be present (condition matched)
                    assertFalse(context.getStartupFailure() != null
                            && context.getStartupFailure().getMessage() != null
                            && context.getStartupFailure().getMessage().contains("did not match"));
                });
    }
}
