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
import org.a2aproject.sdk.spec.AgentCard;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;

/** Final-content sender on a caller-owned transport, independent of the workflow client. */
public record DefaultExtensionSender(A2ATransport transport) implements ExtensionSender, AutoCloseable {
    public DefaultExtensionSender {
        Objects.requireNonNull(transport, "transport");
    }

    @Override
    public CompletableFuture<SendMessageResult> sendAuthorization(String agentName, MessageContent content) {
        try {
            AgentCard card = requireExtension(agentName, content, A2ATExtension.AUTHORIZATION_T);
            String contextId = UUID.randomUUID().toString();
            return transport.send(card, agentName, content, contextId, null, null)
                    .whenComplete((result, error) -> transport.closeConversation(card, contextId));
        } catch (RuntimeException error) {
            return CompletableFuture.failedFuture(error);
        }
    }

    @Override
    public NotificationSubscription openNotification(String agentName, MessageContent content,
            BiConsumer<NotificationSubscription, ReceivedMessage> listener) {
        AgentCard card = requireExtension(agentName, content, A2ATExtension.NOTIFICATION_T);
        return transport.openNotificationStream(card, agentName, content, UUID.randomUUID().toString(),
                Objects.requireNonNull(listener, "listener"));
    }

    private AgentCard requireExtension(String agentName, MessageContent content, A2ATExtension extension) {
        Objects.requireNonNull(content, "content");
        AgentCard card = transport.getCard(agentName);
        if (card == null) throw new IllegalArgumentException("Agent not found: " + agentName);
        if (!A2ATransport.extractExtensionUris(card).contains(extension.uri())
                || !content.extensions().contains(extension.uri())
                || !content.metadata().containsKey(extension.uri())) {
            throw new IllegalArgumentException("Target capability and content must use " + extension.uri());
        }
        return card;
    }

    @Override
    public void close() {
        // Caller owns the independent transport and its subscriptions.
    }
}
