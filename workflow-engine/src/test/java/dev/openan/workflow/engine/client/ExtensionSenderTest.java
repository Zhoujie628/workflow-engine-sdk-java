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
import dev.openan.workflow.engine.model.*;
import net.openan.a2at.sdk.core.model.MetadataContent;
import org.a2aproject.sdk.spec.*;
import org.a2aproject.sdk.client.*;
import org.a2aproject.sdk.client.transport.spi.interceptors.ClientCallContext;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import static org.junit.jupiter.api.Assertions.*;

class ExtensionSenderTest {
    static AgentCard card() throws Exception {
        var card = DefaultWorkflowEngineClientNegotiationTest.card();
        return AgentCard.builder(card).capabilities(new AgentCapabilities(true, false, false, List.of(
                new AgentExtension("", Map.of(), false, A2ATExtension.AUTHORIZATION_T.uri()),
                new AgentExtension("", Map.of(), false, A2ATExtension.NOTIFICATION_T.uri())))).build();
    }

    static MessageContent content(A2ATExtension extension) {
        return A2atMessages.from(new MetadataContent("host-template", "final-content", extension.uri()),
                List.of(new TextPart("instruction")));
    }

    @Test void sendsFinalAuthorizationWithoutGeneratingOrSharingContext() throws Exception {
        List<String> contexts = new CopyOnWriteArrayList<>();
        try (var transport = new A2ATransport(List.of(card()),
                DefaultWorkflowEngineClientNegotiationTest.runtime(params -> {
                    contexts.add(params.message().contextId());
                    assertEquals(content(A2ATExtension.AUTHORIZATION_T).metadata(), params.message().metadata());
                    return List.of(DefaultWorkflowEngineClientNegotiationTest.response(params,
                            TaskState.TASK_STATE_COMPLETED, MessageContent.text("ack")));
                }), WorkflowEngineClientConfig.builder().build())) {
            var sender = new DefaultExtensionSender(transport);
            sender.sendAuthorization("test", content(A2ATExtension.AUTHORIZATION_T)).join();
            sender.sendAuthorization("test", content(A2ATExtension.AUTHORIZATION_T)).join();
            assertNotEquals(contexts.get(0), contexts.get(1));
            assertThrows(CompletionException.class, () -> sender.sendAuthorization("test", MessageContent.text("wrong")).join());
        }
    }

    @Test void earlyEventCanCloseHandleAndCompletionWaitsForActualExit() throws Exception {
        CountDownLatch callbackRan = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<NotificationSubscription> observed = new AtomicReference<>();
        var runtime = new A2AJavaClientRuntime() {
            public Iterable<ClientEvent> sendMessage(AgentCard card, MessageSendParams params,
                    ClientCallContext context, Consumer<ClientEvent> sink, Consumer<String> logs) {
                sink.accept(DefaultWorkflowEngineClientNegotiationTest.response(params,
                        TaskState.TASK_STATE_WORKING, MessageContent.text("ack")));
                while (true) {
                    try { release.await(); break; }
                    catch (InterruptedException ignored) { /* keep transport alive until test releases it */ }
                }
                return List.of();
            }
            public void close() {}
        };
        try (var transport = new A2ATransport(List.of(card()), runtime, WorkflowEngineClientConfig.builder().build())) {
            var subscription = new DefaultExtensionSender(transport).openNotification("test",
                    content(A2ATExtension.NOTIFICATION_T), (handle, event) -> {
                        observed.set(handle);
                        handle.close();
                        callbackRan.countDown();
                    });
            assertTrue(callbackRan.await(2, TimeUnit.SECONDS));
            assertSame(subscription, observed.get());
            assertFalse(subscription.completion().isDone());
            subscription.close();
            release.countDown();
            subscription.completion().get(2, TimeUnit.SECONDS);
            assertFalse(subscription.isActive());
        } finally { release.countDown(); }
    }
}
