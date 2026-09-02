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
package dev.openan.workflow.engine.examples.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.protobuf.util.JsonFormat;
import java.util.ArrayList;
import org.a2aproject.sdk.client.TaskEvent;
import org.a2aproject.sdk.grpc.SendMessageResponse;
import org.a2aproject.sdk.grpc.StreamResponse;
import org.a2aproject.sdk.grpc.utils.ProtoUtils;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.junit.jupiter.api.Test;

class GatewayA2AResponseParserTest {
  private final GatewayA2AResponseParser parser = new GatewayA2AResponseParser();

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(ints = {400, 429})
  void rejectsProblemFramesImmediatelyAndPreservesBusinessDetail(int status) {
    String problem = "{\"status\":" + status + ",\"detail\":\"OMC业务错误\",\"type\":\"\"}";
    var events = new ArrayList<org.a2aproject.sdk.client.ClientEvent>();
    var session = parser.newStreamingSession(events::add);
    session.accept("data: " + problem.substring(0, 15));
    var error =
        assertThrows(
            dev.openan.workflow.engine.client.RemoteProblemException.class,
            () -> session.accept(problem.substring(15) + "\n\n"));
    assertEquals(status, error.getStatus());
    assertEquals("OMC业务错误", error.getDetail());
    assertEquals(0, events.size());
    var bare = parser.newStreamingSession(events::add);
    assertThrows(
        dev.openan.workflow.engine.client.RemoteProblemException.class, () -> bare.accept(problem));
    assertThrows(
        dev.openan.workflow.engine.client.RemoteProblemException.class,
        () -> parser.parseNonStreaming(problem, events::add));
  }

  static String taskJson(String taskId, String contextId) throws Exception {
    return taskJson(taskId, contextId, TaskState.TASK_STATE_COMPLETED);
  }

  static String taskJson(String taskId, String contextId, TaskState state) throws Exception {
    Task task =
        Task.builder().id(taskId).contextId(contextId).status(new TaskStatus(state)).build();
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
        SendMessageResponse.newBuilder().setTask(ProtoUtils.ToProto.task(task)).build();
    return JsonFormat.printer().print(response);
  }

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

  @Test
  void notifiesCompleteFramesOnlyAfterReassembly() throws Exception {
    String frame = "id:1\ndata: " + taskJson("task-frame", "ctx-frame") + "\n\n";
    String expectedCompleteFrame = frame.substring(0, frame.length() - 2);
    int split = 20;
    var frames = new ArrayList<String>();
    var session = parser.newStreamingSession(null, frames::add);

    session.accept(frame.substring(0, split));
    assertEquals(0, frames.size(), "partial chunk must not be notified");
    session.accept(frame.substring(split));

    assertEquals(1, frames.size());
    assertEquals(expectedCompleteFrame, frames.get(0));
    assertEquals(1, session.complete().size());
  }
}
