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

import static org.junit.jupiter.api.Assertions.*;

import dev.openan.workflow.engine.client.A2ATExtension;
import dev.openan.workflow.engine.client.A2AJavaClientRuntime;
import dev.openan.workflow.engine.client.NotificationSubscription;
import dev.openan.workflow.engine.examples.extension.RecoveryNotification;
import dev.openan.workflow.engine.examples.testsupport.OfflineA2ATLlmClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.client.TaskEvent;
import org.a2aproject.sdk.client.transport.spi.interceptors.ClientCallContext;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.Artifact;
import org.a2aproject.sdk.spec.MessageSendParams;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Real lifecycle/transport/handle; only network runtime and content LLM are offline substitutes. */
@Timeout(30)
class WorkbenchNotificationLifecycleTest {
  private String previousLocations;
  private String previousEnv;

  @BeforeEach
  void configureOfflineSdkAndSingleAgent() {
    previousLocations = System.getProperty("A2A_AGENT_CARD_LOCATIONS");
    previousEnv = System.getProperty("a2at.env.path");
    System.setProperty("A2A_AGENT_CARD_LOCATIONS", "classpath:agentcard/spn_domain_agent_city1.json");
    System.setProperty("a2at.env.path", OfflineA2ATLlmClient.envPath());
    OfflineA2ATLlmClient.install();
  }

  @AfterEach
  void restoreProperties() {
    restore("A2A_AGENT_CARD_LOCATIONS", previousLocations);
    restore("a2at.env.path", previousEnv);
  }

  private static void restore(String key, String value) {
    if (value == null) System.clearProperty(key);
    else System.setProperty(key, value);
  }

  @Test
  void recoveryResultsAndHeartbeatKeepSameSubscriptionUntilExplicitUnsubscribe() throws Exception {
    try (Scenario scenario = new Scenario(false)) {
      scenario.start();
      scenario.notification.result("result-1", "成功");
      NotificationSubscription handle = scenario.handle.get();
      assertTrue(handle.isActive(), "first completed recovery must not close the subscription");
      long eventCount = handle.heartbeat().eventCount();
      scenario.notification.heartbeat();
      assertTrue(handle.isHealthy(Duration.ofSeconds(5)));
      assertEquals(eventCount, handle.heartbeat().eventCount(), "heartbeat is not a business event");
      scenario.notification.result("result-2", "失败");
      assertSame(handle, scenario.handle.get());
      assertTrue(handle.isActive(), "failed recovery result must not close the subscription");
      assertEquals(2, scenario.results.get());
      assertFalse(handle.completion().isDone());
      assertEquals(1, scenario.authorization.closes.get(), "authorization is one-shot");
      assertEquals(0, scenario.notification.closes.get());

      handle.close();
      handle.completion().get(2, TimeUnit.SECONDS);
      assertFalse(handle.isActive());
    }
  }

  @Test
  void observerFailureDoesNotCancelStreamOrPreventNextResult() throws Exception {
    try (Scenario scenario = new Scenario(true)) {
      scenario.start();
      scenario.notification.result("result-1", "成功");
      assertTrue(scenario.handle.get().isActive());
      scenario.notification.heartbeat();
      scenario.notification.result("result-2", "失败");
      assertEquals(2, scenario.results.get());
      assertTrue(scenario.handle.get().isActive());
    }
  }

  @Test
  void hostShutdownClosesStreamAfterRecoveryAndIsIdempotent() throws Exception {
    try (Scenario scenario = new Scenario(false)) {
      scenario.start();
      scenario.notification.result("result-1", "成功");
      NotificationSubscription handle = scenario.handle.get();
      assertTrue(handle.isActive());
      scenario.lifecycle.close();
      handle.completion().get(2, TimeUnit.SECONDS);
      assertFalse(handle.isActive());
      assertFalse(scenario.lifecycle.isActive());
      scenario.lifecycle.close();
      assertEquals(1, scenario.notification.closes.get());
      assertEquals(1, scenario.authorization.closes.get());
    }
  }

  private static final class Scenario implements AutoCloseable {
    final SimulatedRuntime authorization = new SimulatedRuntime(false);
    final SimulatedRuntime notification = new SimulatedRuntime(true);
    final AtomicReference<NotificationSubscription> handle = new AtomicReference<>();
    final AtomicInteger results = new AtomicInteger();
    final WorkbenchExtensionLifecycle lifecycle;

    Scenario(boolean failFirstObserver) {
      AtomicInteger runtimes = new AtomicInteger();
      lifecycle = new WorkbenchExtensionLifecycle(null, true, OfflineA2ATLlmClient.envPath(),
          () -> runtimes.getAndIncrement() == 0 ? authorization : notification,
          (subscription, event) -> {
            handle.set(subscription);
            if (RecoveryNotification.hasCompletedResult(event)) {
              int count = results.incrementAndGet();
              if (failFirstObserver && count == 1) throw new IllegalStateException("synthetic observer failure");
            }
          }, (agentName, card, headers) -> headers.put("Authorization", "Bearer synthetic-notification-test"));
    }

    void start() {
      lifecycle.start();
      assertTrue(lifecycle.isActive());
      assertNotNull(handle.get());
      assertTrue(handle.get().isActive());
    }

    @Override
    public void close() {
      lifecycle.close();
    }
  }

  private static final class SimulatedRuntime implements A2AJavaClientRuntime {
    private static final String RESULT = "### 业务抢通事件\n"
        + "1. 业务抢通方案执行状态：已结束\n2. 投诉诊断任务流水号：task-42\n"
        + "3. OSS侧事件流水号：event-42\n4. 接入端口名称：port-42\n"
        + "5. 是否已授权OMC自动抢通：是\n6. 业务抢通方案名称：隧道调优\n"
        + "7. 业务抢通方案详情：执行指定调优策略\n8. 业务抢通方案执行结束时间：2026-08-31T00:00:00Z\n"
        + "9. 业务抢通方案执行结果：";
    final AtomicInteger closes = new AtomicInteger();
    private final boolean notification;
    private final BlockingQueue<Runnable> actions = new LinkedBlockingQueue<>();
    private Consumer<ClientEvent> sink;
    private Runnable activity;
    private String contextId;

    SimulatedRuntime(boolean notification) {
      this.notification = notification;
    }

    @Override
    public Iterable<ClientEvent> sendMessage(AgentCard card, MessageSendParams params,
        ClientCallContext callContext, Consumer<ClientEvent> eventSink, Consumer<String> logSink) {
      if (!notification) return List.of(event(TaskState.TASK_STATE_COMPLETED, List.of()));
      assertEquals(NOTIFICATION_CHANNEL, callContext.getState().get(CHANNEL_STATE_KEY));
      sink = eventSink;
      activity = (Runnable) callContext.getState().get(TRANSPORT_ACTIVITY_STATE_KEY);
      contextId = params.message().contextId();
      sink.accept(event(TaskState.TASK_STATE_WORKING, List.of(artifact("subscription", "subscription", "订阅结果：成功"))));
      try {
        while (!Thread.currentThread().isInterrupted()) actions.take().run();
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      }
      return List.of();
    }

    void result(String id, String outcome) throws Exception {
      submit(() -> sink.accept(event(TaskState.TASK_STATE_WORKING,
          List.of(artifact(id, "recovery-result", RESULT + outcome)))));
    }

    void heartbeat() throws Exception {
      submit(activity);
    }

    private void submit(Runnable action) throws Exception {
      CompletableFuture<Void> delivered = new CompletableFuture<>();
      actions.add(() -> {
        try {
          action.run();
          delivered.complete(null);
        } catch (RuntimeException | Error error) {
          delivered.completeExceptionally(error);
          throw error;
        }
      });
      delivered.get(2, TimeUnit.SECONDS);
    }

    private static Artifact artifact(String id, String name, String content) {
      return Artifact.builder().artifactId(id).name(name).parts(List.of(new TextPart("synthetic event")))
          .metadata(Map.of(A2ATExtension.NOTIFICATION_T.uri(), content)).build();
    }

    private TaskEvent event(TaskState state, List<Artifact> artifacts) {
      return new TaskEvent(Task.builder().id("notification-task").contextId(contextId == null ? "auth-context" : contextId)
          .status(new TaskStatus(state)).artifacts(artifacts).history(List.of()).metadata(Map.of()).build());
    }

    @Override
    public void close() {
      closes.incrementAndGet();
    }
  }
}
