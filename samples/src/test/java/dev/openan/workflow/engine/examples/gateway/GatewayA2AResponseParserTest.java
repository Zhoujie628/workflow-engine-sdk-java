/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.examples.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.protobuf.util.JsonFormat;

import org.a2aproject.sdk.client.TaskEvent;
import org.a2aproject.sdk.grpc.SendMessageResponse;
import org.a2aproject.sdk.grpc.StreamResponse;
import org.a2aproject.sdk.grpc.utils.ProtoUtils;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

class GatewayA2AResponseParserTest {
    private final GatewayA2AResponseParser parser = new GatewayA2AResponseParser();

    @Test
    void parsesSseAndEmitsEachEvent() throws Exception {
        String body = "data: " + taskJson("task-1", "ctx-1") + "\n\n";
        var emitted = new ArrayList<>();

        var events = parser.parse(body, emitted::add);

        assertEquals(1, events.size());
        assertEquals(events, emitted);
        assertEquals("task-1", ((TaskEvent) events.get(0)).getTask().id());
    }

    @Test
    void keepsParsingStateLocalToEachResponse() throws Exception {
        var first = (TaskEvent) parser.parse(taskJson("task-1", "ctx-1"), null).get(0);
        var second = (TaskEvent) parser.parse(taskJson("task-2", "ctx-2"), null).get(0);

        assertEquals("task-1", first.getTask().id());
        assertEquals("task-2", second.getTask().id());
        assertEquals("ctx-2", second.getTask().contextId());
    }

    @Test
    void rejectsMalformedGatewayResponse() {
        assertThrows(IllegalArgumentException.class, () -> parser.parse("{not-json", null));
    }

    @Test
    void parsesSseSplitAcrossGatewayFramesIncrementally() throws Exception {
        String frame = "data: " + taskJson("task-split", "ctx-split") + "\n\n";
        int split = frame.length() / 2;
        var emitted = new ArrayList<>();
        var session = parser.newStreamingSession(emitted::add);

        session.accept(frame.substring(0, split));
        assertEquals(0, emitted.size());
        session.accept(frame.substring(split));

        assertEquals(1, emitted.size());
        assertEquals("task-split", ((TaskEvent) emitted.get(0)).getTask().id());
        assertEquals(1, session.complete().size());
    }

    @Test
    void parsesNonStreamingSendMessageResponse() throws Exception {
        var events = parser.parseNonStreaming(nonStreamingTaskJson("task-send", "ctx-send"), null);

        assertEquals(1, events.size());
        assertEquals("task-send", ((TaskEvent) events.get(0)).getTask().id());
    }

    static String taskJson(String taskId, String contextId) throws Exception {
        return taskJson(taskId, contextId, TaskState.TASK_STATE_COMPLETED);
    }

    static String taskJson(String taskId, String contextId, TaskState state) throws Exception {
        Task task =
                Task.builder()
                        .id(taskId)
                        .contextId(contextId)
                        .status(new TaskStatus(state))
                        .build();
        StreamResponse response =
                StreamResponse.newBuilder().setTask(ProtoUtils.ToProto.task(task)).build();
        return JsonFormat.printer().print(response);
    }

    static String nonStreamingTaskJson(String taskId, String contextId) throws Exception {
        Task task =
                Task.builder()
                        .id(taskId)
                        .contextId(contextId)
                        .status(new TaskStatus(TaskState.TASK_STATE_COMPLETED))
                        .build();
        SendMessageResponse response =
                SendMessageResponse.newBuilder()
                        .setTask(ProtoUtils.ToProto.task(task))
                        .build();
        return JsonFormat.printer().print(response);
    }
}
