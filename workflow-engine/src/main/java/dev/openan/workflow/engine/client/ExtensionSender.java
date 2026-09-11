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
import dev.openan.workflow.engine.model.SendMessageResult;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;

/** Operations independent of workflow execution; all content is supplied by the host. */
public interface ExtensionSender {
  /** Sends final authorization content on this sender's independent transport. */
  CompletableFuture<SendMessageResult> sendAuthorization(String agentName, MessageContent content);

  /**
   * Sends a one-shot task to an agent without a workflow context.
   *
   * <p>The host supplies final content (structured Task-T prompt or plain text); the engine
   * envelopes it, authenticates, sends, and assembles the complete response. No {@code
   * ControlPoint} is invoked and no negotiation is attempted: if the agent responds with
   * {@code INPUT_REQUIRED}, the result carries that state and the caller decides the next step.
   * The operation uses a fresh context independent of any workflow session, exactly like
   * {@link #sendAuthorization(String, MessageContent)}.
   */
  CompletableFuture<SendMessageResult> sendTask(String agentName, MessageContent content);

  /** Registers a handle before starting I/O; early callbacks may close it directly. */
  NotificationSubscription openNotification(
      String agentName,
      MessageContent content,
      BiConsumer<NotificationSubscription, ReceivedMessage> listener);
}
