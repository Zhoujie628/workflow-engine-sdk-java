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

import dev.openan.workflow.engine.control.ControlPoint;
import dev.openan.workflow.engine.control.EventCallback;
import dev.openan.workflow.engine.model.MessageContent;
import dev.openan.workflow.engine.model.SendMessageResult;
import dev.openan.workflow.engine.model.TaskRequest;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Sends final content and coordinates task interaction. Content generation belongs to the host. */
public interface WorkflowEngineClient {
  /** Dispatches a prepared workflow activation; target and protocol association remain internal. */
  CompletableFuture<SendMessageResult> dispatch(
      TaskRequest request, MessageContent content, ControlPoint callbacks);

  /** Sends final content outside the DAG using explicitly configured interaction callbacks. */
  CompletableFuture<SendMessageResult> sendMessage(String agentName, MessageContent content);

  /** Maximum wait for each business callback, including initial content preparation. */
  default long callbackTimeoutSeconds() {
    return 600;
  }

  void setControlPoint(ControlPoint controlPoint);

  void setEventCallback(EventCallback callback);

  /** Queries an existing remote task. */
  CompletableFuture<SendMessageResult> getTask(String agentName, String taskId);

  /** Cancels an existing remote task; this is not a Negotiation-T Abort. */
  CompletableFuture<SendMessageResult> cancelTask(String agentName, String taskId);

  /** Subscribes to an existing task, preserving its identity. */
  CompletableFuture<SendMessageResult> subscribeToTask(
      String agentName, String taskId, Consumer<Map<String, Object>> eventCallback);

  void close();
}
