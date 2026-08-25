/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.examples.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.eastcom.apollo.orders.internal.shaded.v11x.com.eastcom.apollo.orders.commons.metadata.httpsession.OrderHttpSessionStrRequest;
import com.eastcom.apollo.orders.internal.shaded.v11x.com.eastcom.apollo.orders.commons.metadata.httpsession.OrderHttpSessionStrResponse;
import com.google.protobuf.util.JsonFormat;

import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.spec.AgentCapabilities;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentInterface;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.MessageSendParams;
import org.a2aproject.sdk.spec.TextPart;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskStatus;
import org.a2aproject.sdk.grpc.utils.ProtoUtils;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

class OrderGatewayClientRuntimeTest {
    @Test
    void protocolBodiesAreDisabledUnlessExplicitlyEnabled() {
        String setting = "WORKFLOW_ENGINE_PROTOCOL_INCLUDE_BODY";
        String previous = System.getProperty(setting);
        try {
            System.setProperty(setting, "false");
            assertEquals(
                    "(body logging disabled)",
                    OrderGatewayClientRuntime.formatProtocolBody("customer-data"));
            System.setProperty(setting, "true");
            assertTrue(
                    OrderGatewayClientRuntime.formatProtocolBody("customer-data")
                            .contains("customer-data"));
        } finally {
            if (previous == null) {
                System.clearProperty(setting);
            } else {
                System.setProperty(setting, previous);
            }
        }
    }

    @Test
    void formatSseFramePrettyPrintsDataPayloadAndKeepsControlFields() {
        String frame = "id:1\ndata:{\"artifactUpdate\": {\"taskId\": \"t-1\"}}";

        String formatted = OrderGatewayClientRuntime.formatSseFrame(frame);

        assertTrue(formatted.startsWith("id:1\ndata: {\n"));
        assertTrue(formatted.contains("\"artifactUpdate\" : {\n"));
        assertTrue(formatted.endsWith("\n  }\n}"));
        assertTrue(formatted.contains("\"artifactUpdate\" : {\n"));
        assertTrue(formatted.endsWith("\n}}\n}") || formatted.endsWith("\n  }\n}"));
    }

    @Test
    void formatSseFramePrettyPrintsPlainJsonWithoutDataPrefix() {
        String formatted = OrderGatewayClientRuntime.formatSseFrame("{\"result\": \"ok\"}");

        assertTrue(formatted.startsWith("{\n"));
        assertTrue(formatted.contains("\"result\" : \"ok\""));
    }

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

    @Test
    void taskQueryAndCancelUseGatewayRestRoutesAndParsePlainTask() throws Exception {
        var requests = new CopyOnWriteArrayList<OrderHttpSessionStrRequest>();
        var closeCount = new AtomicInteger();
        String taskBody = plainTaskJson("task with space", "ctx-task");
        OrderGatewayClientRuntime.OrderSessionFactory sessions =
                route ->
                        new OrderGatewayClientRuntime.OrderSession() {
                            @Override
                            public OrderHttpSessionStrResponse execute(
                                    OrderHttpSessionStrRequest request, int timeoutMillis) {
                                requests.add(request);
                                return OrderHttpSessionStrResponse.newBuilder()
                                        .setStatus(200)
                                        .setBody(taskBody)
                                        .build();
                            }

                            @Override
                            public void executeStreaming(
                                    OrderHttpSessionStrRequest request,
                                    int timeoutMillis,
                                    Predicate<OrderHttpSessionStrResponse> responseSink) {
                                throw new AssertionError();
                            }

                            @Override
                            public void close() {
                                closeCount.incrementAndGet();
                            }
                        };
        var runtime = runtime(sessions);
        AgentCard card = card("city1", 26335, false);

        assertEquals("task with space", runtime.getTask(card, "task with space", null).id());
        assertEquals("task with space", runtime.cancelTask(card, "task with space", null).id());

        assertEquals(2, requests.size());
        assertEquals("GET", requests.get(0).getMethod());
        assertEquals("/a2a/json/tasks/task%20with%20space", requests.get(0).getUriPath());
        assertEquals("POST", requests.get(1).getMethod());
        assertEquals(
                "/a2a/json/tasks/task%20with%20space:cancel",
                requests.get(1).getUriPath());
        assertEquals("{}", requests.get(1).getBody());
        assertEquals(2, closeCount.get(), "query/cancel sessions must be ephemeral");
    }

    @Test
    void taskSubscriptionUsesDedicatedGatewayLaneAndReturnsTerminalTask() throws Exception {
        var requests = new CopyOnWriteArrayList<OrderHttpSessionStrRequest>();
        var emitted = new CopyOnWriteArrayList<ClientEvent>();
        var closeCount = new AtomicInteger();
        String completed =
                GatewayA2AResponseParserTest.taskJson(
                        "task-subscribe", "ctx-subscribe", TaskState.TASK_STATE_COMPLETED);
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
                                requests.add(request);
                                responseSink.test(streamResponse("data:" + completed + "\n\n"));
                            }

                            @Override
                            public void close() {
                                closeCount.incrementAndGet();
                            }
                        };
        var runtime = runtime(sessions);

        var result =
                runtime.subscribeToTask(
                                card("city1", 26335, true),
                                "task-subscribe",
                                null,
                                emitted::add)
                        .join();

        assertEquals("TASK_STATE_COMPLETED", result.getTaskState());
        assertEquals("task-subscribe", result.getTask().id());
        assertEquals(1, emitted.size());
        assertEquals(1, requests.size());
        assertEquals("POST", requests.get(0).getMethod());
        assertEquals(
                "/a2a/json/tasks/task-subscribe:subscribe",
                requests.get(0).getUriPath());
        assertEquals("text/event-stream", requests.get(0).getHeadersMap().get("Accept"));
        assertEquals(1, closeCount.get(), "terminal subscription must release its lane");
    }

    @Test
    void conversationsToSameNeOwnIndependentSessions() throws Exception {
        var openCount = new AtomicInteger();
        var closeCount = new AtomicInteger();
        String completed =
                GatewayA2AResponseParserTest.nonStreamingTaskJson("task", "ctx");
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
                            throw new AssertionError();
                        }

                        @Override
                        public void close() {
                            closeCount.incrementAndGet();
                        }
                    };
                };
        var runtime = runtime(sessions);
        AgentCard card = card("city1", 26335, false);

        runtime.sendMessage(card, params("ctx-1", "m-1"), null, null, null);
        runtime.sendMessage(card, params("ctx-2", "m-2"), null, null, null);
        runtime.sendMessage(card, params("ctx-2", "m-3"), null, null, null);

        assertEquals(2, openCount.get());
        runtime.closeConversation(card, "ctx-1");
        assertEquals(1, closeCount.get());
        runtime.close();
        assertEquals(2, closeCount.get());
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

    private static String plainTaskJson(String taskId, String contextId) throws Exception {
        Task task =
                Task.builder()
                        .id(taskId)
                        .contextId(contextId)
                        .status(new TaskStatus(TaskState.TASK_STATE_COMPLETED))
                        .build();
        return JsonFormat.printer().print(ProtoUtils.ToProto.task(task));
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
