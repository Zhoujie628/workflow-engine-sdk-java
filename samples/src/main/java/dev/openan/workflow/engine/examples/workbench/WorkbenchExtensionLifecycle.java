/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.examples.workbench;

import dev.openan.workflow.engine.client.A2AJavaClientRuntime;
import dev.openan.workflow.engine.client.A2ATransport;
import dev.openan.workflow.engine.client.DefaultExtensionSender;
import dev.openan.workflow.engine.client.NotificationSubscription;
import dev.openan.workflow.engine.client.WorkflowEngineClientConfig;

import org.a2aproject.sdk.spec.AgentCard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

import dev.openan.workflow.engine.examples.extension.ExtensionPrePositioner;
/**
 * Owns workbench-scoped Authorization-T and Notification-T resources outside the workflow DAG.
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

    private final Map<String, NotificationSubscription> subscriptions =
            new ConcurrentHashMap<>();
    private A2ATransport notificationTransport;

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
        if (notificationTransport != null) {
            log.info(
                    "[ExtensionLifecycle] START_SKIPPED reason=already_active, contextId={}",
                    notificationTransport.getContextId());
            return;
        }

        List<AgentCard> agentCards = new WorkbenchAgentCatalog().load();
        A2AJavaClientRuntime authorizationRuntime =
                runtimeSupplier != null ? runtimeSupplier.get() : null;
        A2AJavaClientRuntime notificationRuntime =
                runtimeSupplier != null ? runtimeSupplier.get() : null;
        if (authorizationRuntime != null && authorizationRuntime == notificationRuntime) {
            throw new IllegalStateException(
                    "Runtime supplier must create independent Authorization-T and Notification-T instances");
        }
        WorkflowEngineClientConfig config =
                WorkflowEngineClientConfig.builder()
                        .sslVerify(sslVerify)
                        .a2atEnvPath(a2atEnvPath)
                        .credentialsConfigPath(credentialsPath)
                        .build();
        A2ATransport authorizationTransport =
                new A2ATransport(
                        agentCards,
                        authorizationRuntime,
                        config);
        A2ATransport notificationCandidate =
                new A2ATransport(agentCards, notificationRuntime, config);
        long started = System.nanoTime();
        log.info(
                "[ExtensionLifecycle] START authorizationContextId={}, notificationContextId={}, scope=workbench",
                authorizationTransport.getContextId(),
                notificationCandidate.getContextId());
        try {
            List<NotificationSubscription> opened =
                    new ExtensionPrePositioner()
                    .prePosition(
                            new DefaultExtensionSender(authorizationTransport),
                            new DefaultExtensionSender(notificationCandidate),
                            agentCards,
                            this::handleNotification);
            opened.forEach(subscription -> subscriptions.put(
                    subscription.agentName(), subscription));
            authorizationTransport.close();
            notificationTransport = notificationCandidate;
            log.info(
                    "[ExtensionLifecycle] ACTIVE contextId={}, subscriptions={}, notificationScope=workbench, elapsedMs={}",
                    notificationCandidate.getContextId(),
                    subscriptions.size(),
                    elapsedMillis(started));
        } catch (RuntimeException e) {
            subscriptions.values().forEach(NotificationSubscription::close);
            subscriptions.clear();
            authorizationTransport.close();
            notificationCandidate.close();
            log.error(
                    "[ExtensionLifecycle] START_FAILED authorizationContextId={}, notificationContextId={}, errorType={}, message={}",
                    authorizationTransport.getContextId(),
                    notificationCandidate.getContextId(),
                    e.getClass().getSimpleName(),
                    e.getMessage(),
                    e);
            throw e;
        }
    }

    public synchronized boolean isActive() {
        return notificationTransport != null;
    }

    @Override
    public synchronized void close() {
        A2ATransport active = notificationTransport;
        notificationTransport = null;
        if (active == null) {
            return;
        }
        log.info(
                "[ExtensionLifecycle] CLOSE_START contextId={}, reason=workbench_shutdown",
                active.getContextId());
        // Let the transport own cancellation and wait for the underlying stream threads before
        // the Order simulator or web container can be stopped.
        active.close();
        subscriptions.clear();
        log.info(
                "[ExtensionLifecycle] CLOSE_DONE contextId={}, reason=workbench_shutdown",
                active.getContextId());
    }

    private void handleNotification(Map<String, Object> data) {
        if ("recovery-result".equals(data.get("artifact_name"))) {
            String agent = String.valueOf(data.get("agent"));
            NotificationSubscription completed = subscriptions.remove(agent);
            if (completed != null) {
                log.info(
                        "[ExtensionLifecycle] NOTIFICATION_COMPLETE agent={}, contextId={}, events={}, action=close-stream",
                        agent,
                        completed.contextId(),
                        completed.heartbeat().eventCount());
                completed.close();
            }
        }
        if (notificationCallback != null) {
            try {
                notificationCallback.accept(data);
            } catch (RuntimeException callbackError) {
                log.warn(
                        "[ExtensionLifecycle] Notification observer failed: {}",
                        callbackError.getMessage(),
                        callbackError);
            }
        }
    }

    private static long elapsedMillis(long startedNanos) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }
}
