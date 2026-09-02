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

  private final dev.openan.workflow.engine.client.AuthProvider authProvider;
  private final String credentialsPath;
  private final boolean sslVerify;
  private final String a2atEnvPath;
  private final Supplier<A2AJavaClientRuntime> runtimeSupplier;
  private final boolean taskCleanupEnabled;
  private final boolean taskCleanupFailFast;
  private final int taskCleanupPageSize;
  private final int taskCleanupMaxTasks;
  private final String localAgentName;
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
    this(
        credentialsPath,
        sslVerify,
        a2atEnvPath,
        runtimeSupplier,
        notificationCallback,
        null,
        false,
        true,
        100,
        1000,
        null);
  }

  public WorkbenchExtensionLifecycle(
      String credentialsPath, boolean sslVerify, String a2atEnvPath,
      Supplier<A2AJavaClientRuntime> runtimeSupplier,
      java.util.function.BiConsumer<NotificationSubscription, dev.openan.workflow.engine.model.ReceivedMessage> notificationCallback,
      dev.openan.workflow.engine.client.AuthProvider authProvider) {
    this(
        credentialsPath,
        sslVerify,
        a2atEnvPath,
        runtimeSupplier,
        notificationCallback,
        authProvider,
        false,
        true,
        100,
        1000,
        null);
  }

  public WorkbenchExtensionLifecycle(
      String credentialsPath,
      boolean sslVerify,
      String a2atEnvPath,
      Supplier<A2AJavaClientRuntime> runtimeSupplier,
      java.util.function.BiConsumer<
              NotificationSubscription, dev.openan.workflow.engine.model.ReceivedMessage>
          notificationCallback,
      dev.openan.workflow.engine.client.AuthProvider authProvider,
      boolean taskCleanupEnabled,
      boolean taskCleanupFailFast,
      int taskCleanupPageSize,
      int taskCleanupMaxTasks,
      String localAgentName) {
    this.authProvider = authProvider;
    this.credentialsPath = credentialsPath;
    this.sslVerify = sslVerify;
    this.a2atEnvPath = a2atEnvPath;
    this.runtimeSupplier = runtimeSupplier;
    this.notificationCallback = notificationCallback;
    this.taskCleanupEnabled = taskCleanupEnabled;
    this.taskCleanupFailFast = taskCleanupFailFast;
    this.taskCleanupPageSize = taskCleanupPageSize;
    this.taskCleanupMaxTasks = taskCleanupMaxTasks;
    this.localAgentName = localAgentName;
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
    cleanupStaleTasks(agentCards);
    A2AJavaClientRuntime authorizationRuntime = null;
    A2AJavaClientRuntime notificationRuntime = null;
    A2ATransport authorizationTransport = null;
    A2ATransport notificationCandidate = null;
    try {
      authorizationRuntime = runtimeSupplier != null ? runtimeSupplier.get() : null;
      notificationRuntime = runtimeSupplier != null ? runtimeSupplier.get() : null;
      if (authorizationRuntime != null && authorizationRuntime == notificationRuntime) {
        throw new IllegalStateException(
            "Runtime supplier must create independent Authorization-T and Notification-T instances");
      }
      WorkflowEngineClientConfig config =
          WorkflowEngineClientConfig.builder()
              .sslVerify(sslVerify)
              .credentialsConfigPath(credentialsPath)
              .authProvider(authProvider)
              .build();
      authorizationTransport = new A2ATransport(agentCards, authorizationRuntime, config);
      notificationCandidate = new A2ATransport(agentCards, notificationRuntime, config);
      long started = System.nanoTime();
      log.info(
          "[ExtensionLifecycle] START authorizationContextId={}, notificationContextId={}, scope=workbench",
          authorizationTransport.getContextId(),
          notificationCandidate.getContextId());
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
      notificationTransport = notificationCandidate;
      log.info(
          "[ExtensionLifecycle] ACTIVE contextId={}, subscriptions={}, notificationScope=workbench, elapsedMs={}",
          notificationCandidate.getContextId(),
          subscriptions.size(),
          elapsedMillis(started));
    } catch (RuntimeException e) {
      subscriptions.values().forEach(NotificationSubscription::close);
      subscriptions.clear();
      closeResource(notificationCandidate,
          notificationRuntime == authorizationRuntime ? null : notificationRuntime);
      throw e;
    } finally {
      closeResource(authorizationTransport, authorizationRuntime);
    }
  }

  private void cleanupStaleTasks(List<AgentCard> agentCards) {
    if (!taskCleanupEnabled) {
      log.info("[TaskCleanup] SKIP reason=disabled");
      return;
    }
    long started = System.nanoTime();
    A2AJavaClientRuntime cleanupRuntime = null;
    A2ATransport cleanupTransport = null;
    try {
      cleanupRuntime = runtimeSupplier != null ? runtimeSupplier.get() : null;
      WorkflowEngineClientConfig config =
          WorkflowEngineClientConfig.builder()
              .sslVerify(sslVerify)
              .credentialsConfigPath(credentialsPath)
              .authProvider(authProvider)
              .build();
      cleanupTransport = new A2ATransport(agentCards, cleanupRuntime, config);
      List<AgentCard> cleanupTargets =
          localAgentName == null
              ? agentCards
              : agentCards.stream()
                  .filter(card -> !localAgentName.equals(card.name()))
                  .toList();
      var report =
          new StaleTaskCleaner(taskCleanupPageSize, taskCleanupMaxTasks)
              .cleanup(
                  new dev.openan.workflow.engine.client.DefaultWorkflowEngineClient(cleanupTransport),
                  cleanupTargets);
      log.info(
          "[TaskCleanup] DONE listed={}, canceled={}, becameTerminal={}, elapsedMs={}",
          report.listed(),
          report.canceled(),
          report.becameTerminal(),
          elapsedMillis(started));
    } catch (RuntimeException error) {
      log.error(
          "[TaskCleanup] FAILED elapsedMs={}, errorType={}, message={}, failFast={}",
          elapsedMillis(started),
          error.getClass().getSimpleName(),
          error.getMessage(),
          taskCleanupFailFast,
          error);
      if (taskCleanupFailFast) {
        throw new TaskCleanupException("Failed to clean stale remote tasks", error);
      }
    } finally {
      closeResource(cleanupTransport, cleanupRuntime);
    }
  }

  private static void closeResource(A2ATransport transport, A2AJavaClientRuntime runtime) {
    try {
      if (transport != null) transport.close();
      else if (runtime != null) runtime.close();
    } catch (RuntimeException error) {
      log.warn("Extension resource cleanup failed", error);
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

  public static final class TaskCleanupException extends RuntimeException {
    TaskCleanupException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
