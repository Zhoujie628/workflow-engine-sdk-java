/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.eastcom.apollo.orders.internal.shaded.v11x.com.eastcom.apollo.orders.commons.metadata.httpsession.OrderHttpSessionStrRequest;
import com.eastcom.apollo.orders.internal.shaded.v11x.com.eastcom.apollo.orders.commons.metadata.httpsession.OrderHttpSessionStrResponse;

import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.spec.AgentCapabilities;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentInterface;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.MessageSendParams;
import org.a2aproject.sdk.spec.TextPart;
import org.a2aproject.sdk.spec.TaskState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

class OrderGatewayClientRuntimeTest {
    @Test
    void parallelSendsUseTheirOwnRoutedSessionsAndCloseThem() throws Exception {
        var openedRoutes = new CopyOnWriteArrayList<AgentGatewayRoute>();
        var requests = new CopyOnWriteArrayList<OrderHttpSessionStrRequest>();
        var closeCount = new AtomicInteger();
        String responseBody =
                GatewayA2AResponseParserTest.nonStreamingTaskJson("task-1", "ctx-1");
        OrderGatewayClientRuntime.OrderSessionFactory sessions =
                route -> {
                    openedRoutes.add(route);
                    return new OrderGatewayClientRuntime.OrderSession() {
                        @Override
                        public OrderHttpSessionStrResponse execute(
                                OrderHttpSessionStrRequest request, int timeoutMillis) {
                            requests.add(request);
                            assertEquals(9_000, timeoutMillis);
                            return OrderHttpSessionStrResponse.newBuilder()
                                    .setStatus(200)
                                    .setBody(responseBody)
                                    .build();
                        }

                        @Override
                        public void executeStreaming(
                                OrderHttpSessionStrRequest request,
                                int timeoutMillis,
                                Predicate<OrderHttpSessionStrResponse> responseSink) {
                            throw new AssertionError("Blocking AgentCard must not use streaming");
                        }

                        @Override
                        public void close() {
                            closeCount.incrementAndGet();
                        }
                    };
                };
        var config =
                OrderGatewayClientRuntime.OrderConfig.builder()
                        .host("gateway")
                        .port(1234)
                        .username("user")
                        .password("password")
                        .agentNeRoutes(Map.of("city1", "ne-1", "city2", "ne-2"))
                        .timeoutSeconds(9)
                        .build();
        var runtime =
                new OrderGatewayClientRuntime(
                        config,
                        new ConfiguredAgentGatewayRouteResolver(
                                Map.of("city1", "ne-1", "city2", "ne-2"), null),
                        sessions,
                        new GatewayA2AResponseParser());

        CompletableFuture<?> first =
                CompletableFuture.runAsync(
                        () ->
                                runtime.sendMessage(
                                        card("city1", 26335, false), params(), null, null, null));
        CompletableFuture<?> second =
                CompletableFuture.runAsync(
                        () ->
                                runtime.sendMessage(
                                        card("city2", 26336, false), params(), null, null, null));
        CompletableFuture.allOf(first, second).join();

        assertEquals(2, openedRoutes.size());
        assertEquals(
                List.of("ne-1", "ne-2"),
                openedRoutes.stream().map(AgentGatewayRoute::ne).sorted().toList());
        assertEquals(2, closeCount.get());
        assertEquals(2, requests.size());
        assertTrue(
                requests.stream()
                        .allMatch(r -> "/a2a/json/message:send".equals(r.getUriPath())));
        assertTrue(requests.stream().allMatch(r -> "POST".equals(r.getMethod())));
    }

    @Test
    void streamingSendEmitsIntermediateEventsBeforeStreamReturns() throws Exception {
        var emitted = new CopyOnWriteArrayList<ClientEvent>();
        var closeCount = new AtomicInteger();
        String working =
                GatewayA2AResponseParserTest.taskJson(
                        "task-stream", "ctx-stream", TaskState.TASK_STATE_WORKING);
        String completed =
                GatewayA2AResponseParserTest.taskJson(
                        "task-stream", "ctx-stream", TaskState.TASK_STATE_COMPLETED);
        OrderGatewayClientRuntime.OrderSessionFactory sessions =
                route ->
                        new OrderGatewayClientRuntime.OrderSession() {
                            @Override
                            public OrderHttpSessionStrResponse execute(
                                    OrderHttpSessionStrRequest request, int timeoutMillis) {
                                throw new AssertionError("Streaming AgentCard must not use execute");
                            }

                            @Override
                            public void executeStreaming(
                                    OrderHttpSessionStrRequest request,
                                    int timeoutMillis,
                                    Predicate<OrderHttpSessionStrResponse> responseSink) {
                                assertEquals("/a2a/json/message:stream", request.getUriPath());
                                assertEquals("text/event-stream", request.getHeadersMap().get("Accept"));
                                assertEquals(9_000, timeoutMillis);
                                assertFalse(
                                        responseSink.test(
                                                streamResponse("data: " + working + "\n\n")));
                                assertEquals(
                                        1,
                                        emitted.size(),
                                        "first event must be emitted before the stream closes");
                                assertTrue(
                                        responseSink.test(
                                                streamResponse("data: " + completed + "\n\n")));
                                assertEquals(2, emitted.size());
                            }

                            @Override
                            public void close() {
                                closeCount.incrementAndGet();
                            }
                        };
        var runtime = runtime(sessions);

        Iterable<ClientEvent> result =
                runtime.sendMessage(
                                        card("city1", 26335, true), params(), null, emitted::add, null);

        assertEquals(2, emitted.size());
        assertEquals(2, count(result));
        assertEquals(1, closeCount.get());
    }

    @Test
    void propagatesStreamingFailureAndStillClosesSession() {
        var closeCount = new AtomicInteger();
        OrderGatewayClientRuntime.OrderSessionFactory sessions =
                route ->
                        new OrderGatewayClientRuntime.OrderSession() {
                            @Override
                            public OrderHttpSessionStrResponse execute(
                                    OrderHttpSessionStrRequest request, int timeoutMillis) {
                                throw new AssertionError();
                            }

                            @Override
                            public void executeStreaming(
                                    OrderHttpSessionStrRequest request,
                                    int timeoutMillis,
                                    Predicate<OrderHttpSessionStrResponse> responseSink) {
                                throw new IllegalStateException("stream disconnected");
                            }

                            @Override
                            public void close() {
                                closeCount.incrementAndGet();
                            }
                        };

        assertThrows(
                IllegalStateException.class,
                () ->
                        runtime(sessions)
                                .sendMessage(
                                        card("city1", 26335, true),
                                        params(),
                                        null,
                                        null,
                                        null));
        assertEquals(1, closeCount.get());
    }

    @Test
    void negotiationFollowUpReusesLoginUntilLogicalConversationCompletes() throws Exception {
        var openCount = new AtomicInteger();
        var closeCount = new AtomicInteger();
        var requestCount = new AtomicInteger();
        String inputRequired =
                GatewayA2AResponseParserTest.taskJson(
                        "task-negotiation",
                        "ctx-negotiation",
                        TaskState.TASK_STATE_INPUT_REQUIRED);
        String completed =
                GatewayA2AResponseParserTest.taskJson(
                        "task-negotiation",
                        "ctx-negotiation",
                        TaskState.TASK_STATE_COMPLETED);
        OrderGatewayClientRuntime.OrderSessionFactory sessions =
                route -> {
                    openCount.incrementAndGet();
                    return new OrderGatewayClientRuntime.OrderSession() {
                        @Override
                        public OrderHttpSessionStrResponse execute(
                                OrderHttpSessionStrRequest request, int timeoutMillis) {
                            String body =
                                    requestCount.getAndIncrement() == 0
                                            ? inputRequired
                                            : completed;
                            return OrderHttpSessionStrResponse.newBuilder()
                                    .setStatus(200)
                                    .setBody(body)
                                    .build();
                        }

                        @Override
                        public void executeStreaming(
                                OrderHttpSessionStrRequest request,
                                int timeoutMillis,
                                Predicate<OrderHttpSessionStrResponse> responseSink) {
                            throw new AssertionError("Blocking AgentCard must not use streaming");
                        }

                        @Override
                        public void close() {
                            closeCount.incrementAndGet();
                        }
                    };
                };
        var runtime = runtime(sessions);
        AgentCard agentCard = card("city1", 26335, false);

        runtime.sendMessage(
                agentCard, params("ctx-negotiation", "message-1"), null, null, null);
        runtime.sendMessage(
                agentCard, params("ctx-negotiation", "message-2"), null, null, null);

        assertEquals(1, openCount.get(), "follow-up must not perform a second login");
        assertEquals(0, closeCount.get(), "session remains alive during negotiation");
        runtime.closeConversation(agentCard, "ctx-negotiation");
        assertEquals(1, closeCount.get());
    }

    @Test
    void notificationStreamUsesSeparateSessionLaneFromTaskConversation() throws Exception {
        var openCount = new AtomicInteger();
        var closeCount = new AtomicInteger();
        String completed =
                GatewayA2AResponseParserTest.taskJson(
                        "task-lane", "ctx-lane", TaskState.TASK_STATE_COMPLETED);
        OrderGatewayClientRuntime.OrderSessionFactory sessions =
                route -> {
                    openCount.incrementAndGet();
                    return new OrderGatewayClientRuntime.OrderSession() {
                        @Override
                        public OrderHttpSessionStrResponse execute(
                                OrderHttpSessionStrRequest request, int timeoutMillis) {
                            return OrderHttpSessionStrResponse.newBuilder()
                                    .setStatus(200)
                                    .setBody(completed)
                                    .build();
                        }

                        @Override
                        public void executeStreaming(
                                OrderHttpSessionStrRequest request,
                                int timeoutMillis,
                                Predicate<OrderHttpSessionStrResponse> responseSink) {
                            throw new AssertionError("Blocking AgentCard must not use streaming");
                        }

                        @Override
                        public void close() {
                            closeCount.incrementAndGet();
                        }
                    };
                };
        var runtime = runtime(sessions);
        AgentCard agentCard = card("city1", 26335, false);

        runtime.sendMessage(
                agentCard,
                params(
                        "ctx-lane",
                        "notification-message",
                        Map.of(
                                "https://projects.tmforum.org/a2aproject/telecommunication/extensions/Notification-T/v1",
                                "subscribe")),
                null,
                null,
                null);
        runtime.sendMessage(
                agentCard, params("ctx-lane", "task-message"), null, null, null);

        assertEquals(2, openCount.get(), "long-lived notification must not block task sends");
        runtime.closeConversation(agentCard, "ctx-lane");
        assertEquals(1, closeCount.get(), "conversation close only releases the task lane");
        runtime.close();
        assertEquals(2, closeCount.get(), "runtime close releases the notification lane");
    }

    private static OrderGatewayClientRuntime runtime(
            OrderGatewayClientRuntime.OrderSessionFactory sessions) {
        var config =
                OrderGatewayClientRuntime.OrderConfig.builder()
                        .host("gateway")
                        .port(1234)
                        .username("user")
                        .password("password")
                        .agentNeRoutes(Map.of("city1", "ne-1"))
                        .timeoutSeconds(9)
                        .build();
        return new OrderGatewayClientRuntime(
                config,
                new ConfiguredAgentGatewayRouteResolver(Map.of("city1", "ne-1"), null),
                sessions,
                new GatewayA2AResponseParser());
    }

    private static OrderHttpSessionStrResponse streamResponse(String body) {
        return OrderHttpSessionStrResponse.newBuilder().setStatus(200).setBody(body).build();
    }

    private static int count(Iterable<ClientEvent> events) {
        int count = 0;
        for (ClientEvent ignored : events) {
            count++;
        }
        return count;
    }

    private static MessageSendParams params() {
        return params(null, "message-1");
    }

    private static MessageSendParams params(String contextId, String messageId) {
        return params(contextId, messageId, Map.of());
    }

    private static MessageSendParams params(
            String contextId, String messageId, Map<String, Object> metadata) {
        Message message =
                Message.builder()
                        .role(Message.Role.ROLE_USER)
                        .messageId(messageId)
                        .contextId(contextId)
                        .parts(new TextPart("diagnose"))
                        .metadata(metadata)
                        .build();
        return MessageSendParams.builder().message(message).build();
    }

    private static AgentCard card(String name, int port, boolean streaming) {
        return AgentCard.builder()
                .name(name)
                .description("test")
                .version("1")
                .capabilities(AgentCapabilities.builder().streaming(streaming).build())
                .defaultInputModes(List.of("text/plain"))
                .defaultOutputModes(List.of("text/plain"))
                .skills(List.of())
                .supportedInterfaces(
                        List.of(
                                new AgentInterface(
                                        "HTTP+JSON", "https://127.0.0.1:" + port + "/a2a/json")))
                .build();
    }
}
