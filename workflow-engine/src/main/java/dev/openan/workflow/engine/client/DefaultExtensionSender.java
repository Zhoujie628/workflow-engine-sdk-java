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
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import org.a2aproject.sdk.spec.AgentCard;

/** Final-content sender independent of the workflow client. */
public final class DefaultExtensionSender implements ExtensionSender, AutoCloseable {
  private final A2ATransport transport;
  private final boolean closeTransportOnClose;
  private final AtomicBoolean closed = new AtomicBoolean();

  /** Creates a sender that owns and closes the supplied transport. */
  public DefaultExtensionSender(A2ATransport transport) {
    this(transport, true);
  }

  private DefaultExtensionSender(A2ATransport transport, boolean owning) {
    this.transport = Objects.requireNonNull(transport, "transport");
    this.closeTransportOnClose = owning;
  }

  /** Creates a sender facade whose transport remains caller-owned. */
  public static DefaultExtensionSender nonOwning(A2ATransport transport) {
    return new DefaultExtensionSender(transport, false);
  }

  public A2ATransport transport() {
    return transport;
  }

  @Override
  public CompletableFuture<SendMessageResult> sendAuthorization(
      String agentName, MessageContent content) {
    try {
      AgentCard card = requireExtension(agentName, content, A2ATExtension.AUTHORIZATION_T);
      String contextId = UUID.randomUUID().toString();
      return transport
          .send(card, agentName, content, contextId, null, null)
          .whenComplete((result, error) -> transport.closeConversation(card, contextId));
    } catch (RuntimeException error) {
      return CompletableFuture.failedFuture(error);
    }
  }

  @Override
  public NotificationSubscription openNotification(
      String agentName,
      MessageContent content,
      BiConsumer<NotificationSubscription, ReceivedMessage> listener) {
    AgentCard card = requireExtension(agentName, content, A2ATExtension.NOTIFICATION_T);
    return transport.openNotificationStream(
        card,
        agentName,
        content,
        UUID.randomUUID().toString(),
        Objects.requireNonNull(listener, "listener"));
  }

  private AgentCard requireExtension(
      String agentName, MessageContent content, A2ATExtension extension) {
    Objects.requireNonNull(content, "content");
    AgentCard card = transport.getCard(agentName);
    if (card == null) throw new IllegalArgumentException("Agent not found: " + agentName);
    if (!A2ATransport.extractExtensionUris(card).contains(extension.uri())
        || !content.extensions().contains(extension.uri())
        || !content.metadata().containsKey(extension.uri())) {
      throw new IllegalArgumentException(
          "Target capability and content must use " + extension.uri());
    }
    return card;
  }

  @Override
  public void close() {
    if (closed.compareAndSet(false, true) && closeTransportOnClose) {
      transport.close();
    }
  }
}
