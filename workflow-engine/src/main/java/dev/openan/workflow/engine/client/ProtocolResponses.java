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

package dev.openan.workflow.engine.client;

import dev.openan.workflow.engine.model.MessageContent;
import dev.openan.workflow.engine.model.ReceivedMessage;
import java.util.*;
import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.client.MessageEvent;
import org.a2aproject.sdk.client.TaskEvent;
import org.a2aproject.sdk.client.TaskUpdateEvent;
import org.a2aproject.sdk.spec.*;

/** Assembles response snapshots without flattening task/message/artifact metadata. */
public final class ProtocolResponses {
  private ProtocolResponses() {}

  /** Reduces ordered SDK events to final response snapshots. */
  public static List<ReceivedMessage> assemble(Iterable<ClientEvent> events) {
    Accumulator accumulator = new Accumulator();
    events.forEach(accumulator::accept);
    return accumulator.snapshots();
  }

  static MessageContent content(Message message) {
    return message == null
        ? null
        : new MessageContent(
            message.parts(),
            message.metadata(),
            message.extensions() == null ? Set.of() : new LinkedHashSet<>(message.extensions()));
  }

  static boolean terminal(TaskState state) {
    return state == TaskState.TASK_STATE_COMPLETED
        || state == TaskState.TASK_STATE_FAILED
        || state == TaskState.TASK_STATE_CANCELED
        || state == TaskState.TASK_STATE_REJECTED;
  }

  /** Per-stream accumulator, never shared across tasks or subscriptions. */
  static final class Accumulator {
    private final Map<String, Artifact> artifacts = new LinkedHashMap<>();
    private final Map<String, MessageContent> messages = new LinkedHashMap<>();
    private Map<String, Object> taskMetadata = Map.of();
    private MessageContent statusMessage;
    private boolean hasTask;

    void accept(ClientEvent event) {
      if (event instanceof MessageEvent message) {
        Message value = message.getMessage();
        messages.put(value.messageId(), content(value));
      } else if (event instanceof TaskEvent task) {
        snapshot(task.getTask(), true);
      } else if (event instanceof TaskUpdateEvent update) {
        snapshot(update.getTask(), false);
        if (update.getUpdateEvent() instanceof TaskArtifactUpdateEvent artifact) {
          append(artifact);
        } else if (update.getUpdateEvent() instanceof TaskStatusUpdateEvent status) {
          statusMessage = content(status.status().message());
          // SDK task snapshots already include the delta. Seed missing artifacts,
          // but never append that snapshot or overwrite raw delta fields with it.
          if (update.getTask().artifacts() != null) {
            update.getTask().artifacts().forEach(a -> artifacts.putIfAbsent(a.artifactId(), a));
          }
        }
      }
    }

    List<ReceivedMessage> acceptIncrementally(ClientEvent event) {
      if (event instanceof MessageEvent message) {
        Message value = message.getMessage();
        return List.of(new ReceivedMessage(content(value), Map.of(), List.of()));
      }
      accept(event);
      if (event instanceof TaskEvent || event instanceof TaskUpdateEvent) {
        return List.of(taskSnapshot());
      }
      return List.of();
    }

    private void snapshot(Task task, boolean authoritative) {
      if (task == null) return;
      hasTask = true;
      if (task.metadata() != null) taskMetadata = task.metadata();
      if (task.status() != null) statusMessage = content(task.status().message());
      if (authoritative && task.artifacts() != null) {
        artifacts.clear();
        for (Artifact artifact : task.artifacts()) {
          artifacts.put(artifact.artifactId(), artifact);
        }
      }
    }

    private void append(TaskArtifactUpdateEvent event) {
      Artifact value = event.artifact();
      Artifact previous = artifacts.get(value.artifactId());
      if (Boolean.TRUE.equals(event.append()) && previous != null) {
        List<Part<?>> parts = new ArrayList<>(previous.parts());
        parts.addAll(value.parts());
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (previous.metadata() != null) metadata.putAll(previous.metadata());
        if (value.metadata() != null) metadata.putAll(value.metadata());
        value =
            new Artifact(
                value.artifactId(),
                value.name() == null ? previous.name() : value.name(),
                value.description() == null ? previous.description() : value.description(),
                parts,
                metadata,
                value.extensions() == null ? previous.extensions() : value.extensions());
      }
      artifacts.put(value.artifactId(), value);
    }

    List<ReceivedMessage> snapshots() {
      List<ReceivedMessage> result = new ArrayList<>();
      messages.values().forEach(m -> result.add(new ReceivedMessage(m, Map.of(), List.of())));
      if (hasTask) result.add(taskSnapshot());
      return List.copyOf(result);
    }

    private ReceivedMessage taskSnapshot() {
      return new ReceivedMessage(statusMessage, taskMetadata, new ArrayList<>(artifacts.values()));
    }
  }
}
