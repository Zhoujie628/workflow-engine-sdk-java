/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.examples.extension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.openan.workflow.engine.client.ExtensionSender;
import dev.openan.workflow.engine.client.NotificationSubscription;
import dev.openan.workflow.engine.examples.workbench.WorkbenchAgentCatalog;
import dev.openan.workflow.engine.model.SendMessageResult;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ExtensionPrePositionerTest {
  @org.junit.jupiter.api.BeforeEach
  void configureOfflineContentSdk() throws Exception {
    dev.openan.workflow.engine.examples.testsupport.OfflineA2ATLlmClient.install();
    System.setProperty("a2at.env.path", java.nio.file.Path.of(getClass().getResource("/a2at-e2e.env").toURI()).toString());
  }

  @org.junit.jupiter.api.AfterEach
  void clearOfflineConfiguration() {
    System.clearProperty("a2at.env.path");
  }

  @Test
  void notificationFailureDoesNotBecomeAWorkflowStartupCondition() {
    AtomicBoolean authorizationAttempted = new AtomicBoolean();
    ExtensionSender authorizationSender =
        sender(
            (method, args) -> {
              if ("sendAuthorization".equals(method.getName())) {
                authorizationAttempted.set(true);
                return CompletableFuture.completedFuture(
                    SendMessageResult.builder()
                        .taskState("TASK_STATE_COMPLETED")
                        .text("authorized")
                        .build());
              }
              throw new UnsupportedOperationException(method.getName());
            });
    ExtensionSender notificationSender =
        sender(
            (method, args) -> {
              if ("openNotification".equals(method.getName())) {
                throw new IllegalStateException("subscription rejected");
              }
              throw new UnsupportedOperationException(method.getName());
            });

    List<NotificationSubscription> subscriptions =
        new ExtensionPrePositioner()
            .prePosition(
                authorizationSender,
                notificationSender,
                List.of(city1Card()),
                (handle, received) -> {});

    assertTrue(authorizationAttempted.get());
    assertTrue(subscriptions.isEmpty());
  }

  @Test
  void authorizationFailureDoesNotBlockSubscriptionOrWorkflowStartup() throws Exception {
    NotificationSubscription subscription = newSubscription("SPN Domain Agent City1");
    AtomicBoolean notificationAttempted = new AtomicBoolean();
    ExtensionSender authorizationSender =
        sender(
            (method, args) -> {
              if ("sendAuthorization".equals(method.getName())) {
                return CompletableFuture.failedFuture(
                    new IllegalStateException("authorization rejected"));
              }
              throw new UnsupportedOperationException(method.getName());
            });
    ExtensionSender notificationSender =
        sender(
            (method, args) -> {
              if ("openNotification".equals(method.getName())) {
                notificationAttempted.set(true);
                return subscription;
              }
              throw new UnsupportedOperationException(method.getName());
            });
    CountDownLatch registered = new CountDownLatch(1);
    AtomicReference<NotificationSubscription> registeredSubscription = new AtomicReference<>();

    CompletableFuture<List<NotificationSubscription>> result =
        CompletableFuture.supplyAsync(
            () ->
                new ExtensionPrePositioner()
                    .prePosition(
                        authorizationSender,
                        notificationSender,
                        List.of(city1Card()),
                        (handle, received) -> {},
                        opened -> {
                          registeredSubscription.set(opened);
                          registered.countDown();
                        }));

    assertTrue(registered.await(5, TimeUnit.SECONDS));
    assertFalse(result.isDone(), "registration must happen before waiting for the ACK");
    acknowledge(subscription, "TASK_STATE_WORKING");

    assertEquals(List.of(subscription), result.get(5, TimeUnit.SECONDS));
    assertTrue(notificationAttempted.get());
    assertSame(subscription, registeredSubscription.get());
  }

  private static org.a2aproject.sdk.spec.AgentCard city1Card() {
    return new WorkbenchAgentCatalog()
        .load().stream()
            .filter(card -> "SPN Domain Agent City1".equals(card.name()))
            .findFirst()
            .orElseThrow();
  }

  private static NotificationSubscription newSubscription(String agentName) throws Exception {
    Constructor<NotificationSubscription> constructor =
        NotificationSubscription.class.getDeclaredConstructor(
            String.class, String.class, Runnable.class);
    constructor.setAccessible(true);
    return constructor.newInstance(agentName, "notification-context", (Runnable) () -> {});
  }

  private static void acknowledge(NotificationSubscription subscription, String state)
      throws Exception {
    Method acknowledge =
        NotificationSubscription.class.getDeclaredMethod("acknowledge", SendMessageResult.class);
    acknowledge.setAccessible(true);
    acknowledge.invoke(
        subscription, SendMessageResult.builder().taskState(state).text("subscribed").build());
  }

  private static ExtensionSender sender(Invocation invocation) {
    return (ExtensionSender)
        Proxy.newProxyInstance(
            ExtensionSender.class.getClassLoader(),
            new Class<?>[] {ExtensionSender.class},
            (proxy, method, args) -> invocation.invoke(method, args));
  }

  @FunctionalInterface
  private interface Invocation {
    Object invoke(Method method, Object[] args) throws Throwable;
  }
}
