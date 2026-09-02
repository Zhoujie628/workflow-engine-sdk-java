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

import dev.openan.workflow.engine.client.WorkflowEngineClient;
import dev.openan.workflow.engine.control.ControlPoint;
import dev.openan.workflow.engine.control.EventCallback;
import dev.openan.workflow.engine.model.MessageContent;
import dev.openan.workflow.engine.model.SendMessageResult;
import dev.openan.workflow.engine.model.TaskRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.a2aproject.sdk.jsonrpc.common.wrappers.ListTasksResult;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.ListTasksParams;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.junit.jupiter.api.Test;

class StaleTaskCleanerTest {

  @Test
  void cancelsEveryVisibleNonTerminalTask() {
    FakeClient client = new FakeClient();
    client.tasks.add(task("working", TaskState.TASK_STATE_WORKING));
    client.tasks.add(task("waiting", TaskState.TASK_STATE_INPUT_REQUIRED));
    client.tasks.add(task("done", TaskState.TASK_STATE_COMPLETED));

    var report = new StaleTaskCleaner(100, 10).cleanup(client, List.of(agentCard()));

    assertEquals(2, report.listed());
    assertEquals(2, report.canceled());
    assertEquals(List.of("working", "waiting"), client.canceledTaskIds);
  }

  @Test
  void acceptsTaskThatBecomesTerminalBeforeCancellation() {
    FakeClient client = new FakeClient();
    client.tasks.add(task("racing", TaskState.TASK_STATE_WORKING));
    client.failCancellation = true;

    var report = new StaleTaskCleaner(100, 10).cleanup(client, List.of(agentCard()));

    assertEquals(1, report.listed());
    assertEquals(0, report.canceled());
    assertEquals(1, report.becameTerminal());
  }

  private static AgentCard agentCard() {
    return new WorkbenchAgentCatalog().load().get(0);
  }

  private static Task task(String id, TaskState state) {
    return Task.builder()
        .id(id)
        .contextId("ctx-" + id)
        .status(new TaskStatus(state))
        .build();
  }

  private static final class FakeClient implements WorkflowEngineClient {
    private final List<Task> tasks = new ArrayList<>();
    private final List<String> canceledTaskIds = new ArrayList<>();
    private boolean failCancellation;

    @Override
    public CompletableFuture<ListTasksResult> listTasks(String agentName, ListTasksParams params) {
      return CompletableFuture.completedFuture(
          new ListTasksResult(
              tasks.stream().filter(task -> task.status().state() == params.status()).toList()));
    }

    @Override
    public CompletableFuture<SendMessageResult> cancelTask(String agentName, String taskId) {
      if (failCancellation) {
        return CompletableFuture.failedFuture(new IllegalStateException("already terminal"));
      }
      canceledTaskIds.add(taskId);
      return CompletableFuture.completedFuture(result(task(taskId, TaskState.TASK_STATE_CANCELED)));
    }

    @Override
    public CompletableFuture<SendMessageResult> getTask(String agentName, String taskId) {
      return CompletableFuture.completedFuture(result(task(taskId, TaskState.TASK_STATE_COMPLETED)));
    }

    private static SendMessageResult result(Task task) {
      return SendMessageResult.builder().task(task).taskState(task.status().state().name()).build();
    }

    @Override
    public CompletableFuture<SendMessageResult> dispatch(
        TaskRequest request, MessageContent content, ControlPoint callbacks) {
      throw new UnsupportedOperationException();
    }

    @Override
    public CompletableFuture<SendMessageResult> sendMessage(
        String agentName, MessageContent content) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void setControlPoint(ControlPoint controlPoint) {}

    @Override
    public void setEventCallback(EventCallback callback) {}

    @Override
    public CompletableFuture<SendMessageResult> subscribeToTask(
        String agentName, String taskId, Consumer<Map<String, Object>> eventCallback) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void close() {}
  }
}
