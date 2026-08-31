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
import dev.openan.workflow.engine.examples.extension.ExtensionPrePositioner;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.a2aproject.sdk.spec.AgentCard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Owns workbench-scoped Authorization-T and Notification-T resources outside the workflow DAG. */
public final class WorkbenchExtensionLifecycle implements AutoCloseable {
  private static final Logger log = LoggerFactory.getLogger(WorkbenchExtensionLifecycle.class);

  private final String credentialsPath;
  private final boolean sslVerify;
  private final String a2atEnvPath;
  private final Supplier<A2AJavaClientRuntime> runtimeSupplier;
  private final java.util.function.BiConsumer<
          NotificationSubscription, dev.openan.workflow.engine.model.ReceivedMessage>
      notificationCallback;
  private final Map<String, NotificationSubscription> subscriptions = new ConcurrentHashMap<>();
  private A2ATransport notificationTransport;

  public WorkbenchExtensionLifecycle(
      String credentialsPath,
      boolean sslVerify,
      String a2atEnvPath,
      Supplier<A2AJavaClientRuntime> runtimeSupplier,
      java.util.function.BiConsumer<
              NotificationSubscription, dev.openan.workflow.engine.model.ReceivedMessage>
          notificationCallback) {
    this.credentialsPath = credentialsPath;
    this.sslVerify = sslVerify;
    this.a2atEnvPath = a2atEnvPath;
    this.runtimeSupplier = runtimeSupplier;
    this.notificationCallback = notificationCallback;
  }

  private static long elapsedMillis(long startedNanos) {
    return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
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
            .credentialsConfigPath(credentialsPath)
            .build();
    A2ATransport authorizationTransport =
        new A2ATransport(agentCards, authorizationRuntime, config);
    A2ATransport notificationCandidate = new A2ATransport(agentCards, notificationRuntime, config);
    long started = System.nanoTime();
    log.info(
        "[ExtensionLifecycle] START authorizationContextId={}, notificationContextId={}, scope=workbench",
        authorizationTransport.getContextId(),
        notificationCandidate.getContextId());
    try {
      List<NotificationSubscription> opened =
          new ExtensionPrePositioner(a2atEnvPath)
              .prePosition(
                  new DefaultExtensionSender(authorizationTransport),
                  new DefaultExtensionSender(notificationCandidate),
                  agentCards,
                  this::handleNotification,
                  subscription -> subscriptions.put(subscription.agentName(), subscription));
      Set<String> activeAgents =
          opened.stream()
              .map(NotificationSubscription::agentName)
              .collect(Collectors.toUnmodifiableSet());
      subscriptions.entrySet().removeIf(entry -> !activeAgents.contains(entry.getKey()));
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
    active.close();
    subscriptions.clear();
  }

  private void handleNotification(
      NotificationSubscription handle, dev.openan.workflow.engine.model.ReceivedMessage received) {
    if (dev.openan.workflow.engine.examples.extension.RecoveryNotification.hasCompletedResult(
        received)) {
      subscriptions.remove(handle.agentName(), handle);
      log.info(
          "[ExtensionLifecycle] NOTIFICATION_COMPLETE agent={}, contextId={}, action=close-stream",
          handle.agentName(),
          handle.contextId());
      handle.close();
    }
    if (notificationCallback != null) {
      try {
        notificationCallback.accept(handle, received);
      } catch (RuntimeException error) {
        log.warn("Notification observer failed", error);
      }
    }
  }
}
