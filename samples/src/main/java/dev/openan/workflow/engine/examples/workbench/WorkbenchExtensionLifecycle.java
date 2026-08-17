/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.examples.workbench;

import dev.openan.workflow.engine.client.A2AJavaClientRuntime;
import dev.openan.workflow.engine.client.A2ATransport;
import dev.openan.workflow.engine.client.DefaultExtensionSender;
import dev.openan.workflow.engine.client.WorkflowEngineClientConfig;

import org.a2aproject.sdk.spec.AgentCard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

import dev.openan.workflow.engine.examples.extension.ExtensionPrePositioner;
/**
 * Owns workbench-scoped pre-positioning resources.
 *
 * <p>Authorization-T is sent once and its request session is released after its response.
 * Notification-T streams remain owned by this lifecycle until {@link #close()} is called by the
 * workbench service lifecycle; task completion never closes them.
 */
public final class WorkbenchExtensionLifecycle implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(WorkbenchExtensionLifecycle.class);

    private final String credentialsPath;
    private final boolean sslVerify;
    private final String a2atEnvPath;
    private final Supplier<A2AJavaClientRuntime> runtimeSupplier;
    private final Consumer<Map<String, Object>> notificationCallback;

    private A2ATransport transport;

    public WorkbenchExtensionLifecycle(
            String credentialsPath,
            boolean sslVerify,
            String a2atEnvPath,
            Supplier<A2AJavaClientRuntime> runtimeSupplier,
            Consumer<Map<String, Object>> notificationCallback) {
        this.credentialsPath = credentialsPath;
        this.sslVerify = sslVerify;
        this.a2atEnvPath = a2atEnvPath;
        this.runtimeSupplier = runtimeSupplier;
        this.notificationCallback = notificationCallback;
    }

    public synchronized void start() {
        if (transport != null) {
            log.info(
                    "[ExtensionLifecycle] START_SKIPPED reason=already_active, contextId={}",
                    transport.getContextId());
            return;
        }

        List<AgentCard> agentCards = new WorkbenchAgentCatalog().load();
        A2AJavaClientRuntime runtime = runtimeSupplier != null ? runtimeSupplier.get() : null;
        A2ATransport candidate =
                new A2ATransport(
                        agentCards,
                        runtime,
                        WorkflowEngineClientConfig.builder()
                                .sslVerify(sslVerify)
                                .a2atEnvPath(a2atEnvPath)
                                .credentialsConfigPath(credentialsPath)
                                .build());
        long started = System.nanoTime();
        log.info(
                "[ExtensionLifecycle] START contextId={}, runtime={}, scope=workbench",
                candidate.getContextId(),
                runtime != null ? runtime.getClass().getSimpleName() : "DefaultA2AJavaClientRuntime");
        try {
            new ExtensionPrePositioner()
                    .prePosition(
                            new DefaultExtensionSender(candidate),
                            agentCards,
                            notificationCallback);
            transport = candidate;
            log.info(
                    "[ExtensionLifecycle] ACTIVE contextId={}, notificationScope=workbench, elapsedMs={}",
                    candidate.getContextId(),
                    elapsedMillis(started));
        } catch (RuntimeException e) {
            candidate.close();
            log.error(
                    "[ExtensionLifecycle] START_FAILED contextId={}, errorType={}, message={}",
                    candidate.getContextId(),
                    e.getClass().getSimpleName(),
                    e.getMessage(),
                    e);
            throw e;
        }
    }

    public synchronized boolean isActive() {
        return transport != null;
    }

    @Override
    public synchronized void close() {
        A2ATransport active = transport;
        transport = null;
        if (active == null) {
            return;
        }
        log.info(
                "[ExtensionLifecycle] CLOSE_START contextId={}, reason=workbench_shutdown",
                active.getContextId());
        active.close();
        log.info(
                "[ExtensionLifecycle] CLOSE_DONE contextId={}, reason=workbench_shutdown",
                active.getContextId());
    }

    private static long elapsedMillis(long startedNanos) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }
}
