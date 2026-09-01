/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.examples.workbench;

import dev.openan.workflow.engine.client.WorkflowEngineClient;
import dev.openan.workflow.engine.model.SendMessageResult;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionException;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.ListTasksParams;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Cancels non-terminal A2A tasks visible to the demo's authenticated identity before startup. */
final class StaleTaskCleaner {
  private static final Logger log = LoggerFactory.getLogger(StaleTaskCleaner.class);
  private static final List<TaskState> ACTIVE_STATES =
      List.of(
          TaskState.TASK_STATE_SUBMITTED,
          TaskState.TASK_STATE_WORKING,
          TaskState.TASK_STATE_INPUT_REQUIRED,
          TaskState.TASK_STATE_AUTH_REQUIRED);

  private final int pageSize;
  private final int maxTasks;

  StaleTaskCleaner(int pageSize, int maxTasks) {
    if (pageSize < 1 || pageSize > 100) {
      throw new IllegalArgumentException("task cleanup pageSize must be between 1 and 100");
    }
    if (maxTasks < 1) {
      throw new IllegalArgumentException("task cleanup maxTasks must be positive");
    }
    this.pageSize = pageSize;
    this.maxTasks = maxTasks;
  }

  CleanupReport cleanup(WorkflowEngineClient client, List<AgentCard> agentCards) {
    int listed = 0;
    int canceled = 0;
    int becameTerminal = 0;
    for (AgentCard card : agentCards) {
      Map<String, Task> active = listActiveTasks(client, card.name());
      listed += active.size();
      for (Task task : active.values()) {
        if (cancel(client, card.name(), task)) canceled++;
        else becameTerminal++;
      }
    }
    return new CleanupReport(listed, canceled, becameTerminal);
  }

  private Map<String, Task> listActiveTasks(WorkflowEngineClient client, String agentName) {
    Map<String, Task> active = new LinkedHashMap<>();
    for (TaskState state : ACTIVE_STATES) {
      String pageToken = null;
      Set<String> seenTokens = new java.util.HashSet<>();
      do {
        ListTasksParams.Builder query =
            ListTasksParams.builder()
                .status(state)
                .pageSize(pageSize)
                .historyLength(0)
                .includeArtifacts(false);
        if (pageToken != null) query.pageToken(pageToken);
        var page = client.listTasks(agentName, query.build()).join();
        for (Task task : page.tasks()) {
          if (task.status() != null && ACTIVE_STATES.contains(task.status().state())) {
            active.putIfAbsent(task.id(), task);
            if (active.size() > maxTasks) {
              throw new IllegalStateException(
                  "Task cleanup limit exceeded for " + agentName + ": " + maxTasks);
            }
          }
        }
        pageToken = page.nextPageToken();
        if (pageToken != null && !pageToken.isBlank() && !seenTokens.add(pageToken)) {
          throw new IllegalStateException(
              "Task cleanup received a repeated page token from " + agentName);
        }
      } while (pageToken != null && !pageToken.isBlank());
    }
    log.info("[TaskCleanup] LIST_DONE agent={}, activeTasks={}", agentName, active.size());
    return active;
  }

  private boolean cancel(WorkflowEngineClient client, String agentName, Task task) {
    try {
      SendMessageResult result = client.cancelTask(agentName, task.id()).join();
      if (result.getTask() == null
          || result.getTask().status() == null
          || result.getTask().status().state() != TaskState.TASK_STATE_CANCELED) {
        throw new IllegalStateException(
            "Cancellation did not reach CANCELED for " + agentName + "/" + task.id());
      }
      log.info(
          "[TaskCleanup] CANCEL_DONE agent={}, taskId={}, previousState={}",
          agentName,
          task.id(),
          task.status().state());
      return true;
    } catch (CompletionException | IllegalStateException cancellationError) {
      SendMessageResult current;
      try {
        current = client.getTask(agentName, task.id()).join();
      } catch (RuntimeException queryError) {
        cancellationError.addSuppressed(queryError);
        throw cancellationError;
      }
      if (current.getTask() != null
          && current.getTask().status() != null
          && current.getTask().status().state().isFinal()) {
        log.info(
            "[TaskCleanup] CANCEL_SKIP agent={}, taskId={}, reason=became_terminal, state={}",
            agentName,
            task.id(),
            current.getTask().status().state());
        return false;
      }
      throw cancellationError;
    }
  }

  record CleanupReport(int listed, int canceled, int becameTerminal) {}
}
