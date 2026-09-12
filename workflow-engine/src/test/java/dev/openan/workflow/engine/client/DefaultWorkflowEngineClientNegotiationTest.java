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

import static org.junit.jupiter.api.Assertions.*;

import dev.openan.workflow.engine.control.ControlPoint;
import dev.openan.workflow.engine.model.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import net.openan.a2at.sdk.core.model.*;
import org.a2aproject.sdk.client.*;
import org.a2aproject.sdk.client.transport.spi.interceptors.ClientCallContext;
import org.a2aproject.sdk.spec.*;
import org.junit.jupiter.api.Test;

class DefaultWorkflowEngineClientNegotiationTest {
  static A2AJavaClientRuntime runtime(Function<MessageSendParams, List<ClientEvent>> send) {
    AtomicReference<MessageSendParams> lastRequest = new AtomicReference<>();
    return new A2AJavaClientRuntime() {
      public Iterable<ClientEvent> sendMessage(
          AgentCard card,
          MessageSendParams params,
          ClientCallContext context,
          Consumer<ClientEvent> sink,
          Consumer<String> logs) {
        lastRequest.set(params);
        List<ClientEvent> events = send.apply(params);
        if (sink != null) events.forEach(sink);
        return events;
      }

      public org.a2aproject.sdk.spec.Task cancelTask(
          AgentCard card, String taskId, ClientCallContext context) {
        MessageSendParams request = lastRequest.get();
        if (request == null) throw new IllegalStateException("No task has been sent");
        return response(request, TaskState.TASK_STATE_CANCELED, MessageContent.text("canceled"))
            .getTask();
      }

      public void close() {}
    };
  }

  static AgentCard card() throws Exception {
    return new com.fasterxml.jackson.databind.ObjectMapper()
        .registerModule(new AgentCardJacksonModule())
        .readValue(
            """
                    {"name":"test","description":"test","version":"1",
                     "capabilities":{"streaming":true,"extensions":[]},
                     "defaultInputModes":["text/plain"],"defaultOutputModes":["text/plain"],
                     "skills":[],"supportedInterfaces":[{"protocolBinding":"HTTP+JSON","protocolVersion":"1.0",
                     "url":"http://localhost:1","tenant":""}]}
                    """,
            AgentCard.class);
  }

  static MessageContent negotiation(String id, int round, NegotiationPerformative performative) {
    return A2atMessages.from(
        new MetadataContent(
            "Negotiation-T/information-negotiation/propose/v1",
            "opaque",
            A2ATExtension.NEGOTIATION_T.uri(),
            new NegotiationContext(id, round, round, performative)),
        List.of(new TextPart("not interpreted")));
  }

  static TaskEvent response(MessageSendParams request, TaskState state, MessageContent content) {
    Message message =
        Message.builder()
            .messageId(UUID.randomUUID().toString())
            .role(Message.Role.ROLE_AGENT)
            .parts(content.parts())
            .metadata(content.metadata())
            .build();
    return new TaskEvent(
        org.a2aproject.sdk.spec.Task.builder()
            .id("remote-task")
            .contextId(request.message().contextId())
            .status(new org.a2aproject.sdk.spec.TaskStatus(state, message, null))
            .history(List.of())
            .metadata(Map.of())
            .build());
  }

  @Test
  void publicClientContractExposesOnlyTheTaskOrientedSendName() {
    Set<String> methodNames =
        Arrays.stream(WorkflowEngineClient.class.getMethods())
            .map(java.lang.reflect.Method::getName)
            .collect(java.util.stream.Collectors.toSet());
    assertFalse(methodNames.contains("dispatch"));
    assertFalse(methodNames.contains("sendMessage"));
  }

  @Test
  void standaloneTaskUsesFreshContextAndConfiguredInteractionLoop() throws Exception {
    List<String> contexts = new CopyOnWriteArrayList<>();
    try (var transport =
        new A2ATransport(
            List.of(card()),
            runtime(
                params -> {
                  contexts.add(params.message().contextId());
                  return List.of(
                      response(
                          params,
                          TaskState.TASK_STATE_COMPLETED,
                          MessageContent.text("task-done")));
                }),
            WorkflowEngineClientConfig.builder().build())) {
      var client = new DefaultWorkflowEngineClient(transport);

      assertEquals(
          "TASK_STATE_COMPLETED",
          client.sendTask("test", MessageContent.text("first")).join().getTaskState());
      assertEquals(
          "TASK_STATE_COMPLETED",
          client.sendTask("test", MessageContent.text("second")).join().getTaskState());
    }
    assertEquals(2, contexts.size());
    assertNotEquals(contexts.get(0), contexts.get(1));
  }

  @Test
  void standaloneTaskAcceptsPerCallNegotiationStrategy() throws Exception {
    AtomicInteger sends = new AtomicInteger();
    AtomicInteger negotiations = new AtomicInteger();
    try (var transport =
        new A2ATransport(
            List.of(card()),
            runtime(
                params ->
                    List.of(
                        response(
                            params,
                            sends.incrementAndGet() == 1
                                ? TaskState.TASK_STATE_INPUT_REQUIRED
                                : TaskState.TASK_STATE_COMPLETED,
                            sends.get() == 1
                                ? negotiation("standalone", 1, NegotiationPerformative.PROPOSE)
                                : MessageContent.text("done")))),
            WorkflowEngineClientConfig.builder().build())) {
      var client = new DefaultWorkflowEngineClient(transport);

      var result =
          client
              .sendTask(
                  "test",
                  MessageContent.text("start"),
                  request -> {
                    negotiations.incrementAndGet();
                    return CompletableFuture.completedFuture(
                        new NegotiationReply.Send(
                            negotiation("standalone", 1, NegotiationPerformative.ACCEPT)));
                  })
              .join();

      assertEquals("TASK_STATE_COMPLETED", result.getTaskState());
      assertEquals(1, negotiations.get());
      assertEquals(2, sends.get());
    }
  }

  @Test
  void standaloneTaskCancelsRemoteTaskBeforeFailingWhenNegotiationCannotContinue()
      throws Exception {
    AtomicInteger cancellations = new AtomicInteger();
    AtomicReference<MessageSendParams> sent = new AtomicReference<>();
    A2AJavaClientRuntime runtime =
        new A2AJavaClientRuntime() {
          public Iterable<ClientEvent> sendMessage(
              AgentCard card,
              MessageSendParams params,
              ClientCallContext context,
              Consumer<ClientEvent> sink,
              Consumer<String> logs) {
            sent.set(params);
            return List.of(
                response(
                    params,
                    TaskState.TASK_STATE_INPUT_REQUIRED,
                    negotiation("standalone", 1, NegotiationPerformative.PROPOSE)));
          }

          public org.a2aproject.sdk.spec.Task cancelTask(
              AgentCard card, String taskId, ClientCallContext context) {
            cancellations.incrementAndGet();
            assertEquals("remote-task", taskId);
            return response(
                    sent.get(), TaskState.TASK_STATE_CANCELED, MessageContent.text("canceled"))
                .getTask();
          }

          public void close() {}
        };
    try (var transport =
        new A2ATransport(List.of(card()), runtime, WorkflowEngineClientConfig.builder().build())) {
      var client = new DefaultWorkflowEngineClient(transport);

      assertThrows(
          CompletionException.class,
          () -> client.sendTask("test", MessageContent.text("start")).join());
      assertEquals(1, cancellations.get());
    }
  }

  @Test
  void lateRemoteIdentityIsCanceledAfterLocalTimeout() throws Exception {
    CountDownLatch canceled = new CountDownLatch(1);
    AtomicReference<MessageSendParams> sent = new AtomicReference<>();
    A2AJavaClientRuntime runtime =
        new A2AJavaClientRuntime() {
          public Iterable<ClientEvent> sendMessage(
              AgentCard card,
              MessageSendParams params,
              ClientCallContext context,
              Consumer<ClientEvent> sink,
              Consumer<String> logs) {
            sent.set(params);
            java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1_200));
            return List.of(
                response(
                    params,
                    TaskState.TASK_STATE_INPUT_REQUIRED,
                    negotiation("late", 1, NegotiationPerformative.PROPOSE)));
          }

          public org.a2aproject.sdk.spec.Task cancelTask(
              AgentCard card, String taskId, ClientCallContext context) {
            canceled.countDown();
            return response(
                    sent.get(), TaskState.TASK_STATE_CANCELED, MessageContent.text("canceled"))
                .getTask();
          }

          public void close() {}
        };
    WorkflowEngineClientConfig config =
        WorkflowEngineClientConfig.builder().sendTimeoutSeconds(1).build();
    try (var transport = new A2ATransport(List.of(card()), runtime, config)) {
      var client = new DefaultWorkflowEngineClient(transport, config);

      CompletionException error =
          assertThrows(
              CompletionException.class,
              () -> client.sendTask("test", MessageContent.text("start")).join());
      assertInstanceOf(TimeoutException.class, error.getCause());
      assertTrue(canceled.await(3, TimeUnit.SECONDS));
    }
  }

  @Test
  void standaloneTaskCancelsUnsupportedAuthRequiredTask() throws Exception {
    AtomicInteger cancellations = new AtomicInteger();
    AtomicReference<MessageSendParams> sent = new AtomicReference<>();
    A2AJavaClientRuntime runtime =
        new A2AJavaClientRuntime() {
          public Iterable<ClientEvent> sendMessage(
              AgentCard card,
              MessageSendParams params,
              ClientCallContext context,
              Consumer<ClientEvent> sink,
              Consumer<String> logs) {
            sent.set(params);
            return List.of(
                response(
                    params,
                    TaskState.TASK_STATE_AUTH_REQUIRED,
                    MessageContent.text("authentication required")));
          }

          public org.a2aproject.sdk.spec.Task cancelTask(
              AgentCard card, String taskId, ClientCallContext context) {
            cancellations.incrementAndGet();
            return response(
                    sent.get(), TaskState.TASK_STATE_CANCELED, MessageContent.text("canceled"))
                .getTask();
          }

          public void close() {}
        };
    try (var transport =
        new A2ATransport(List.of(card()), runtime, WorkflowEngineClientConfig.builder().build())) {
      var client = new DefaultWorkflowEngineClient(transport);

      CompletionException error =
          assertThrows(
              CompletionException.class,
              () -> client.sendTask("test", MessageContent.text("start")).join());
      assertTrue(error.getCause().getMessage().contains("TASK_STATE_AUTH_REQUIRED"));
      assertEquals(1, cancellations.get());
    }
  }

  @Test
  void standaloneTaskValidatesTaskTExtensionOnlyWhenActivated() throws Exception {
    AtomicInteger sends = new AtomicInteger();
    try (var transport =
        new A2ATransport(
            List.of(card()),
            runtime(
                params -> {
                  sends.incrementAndGet();
                  return List.of(
                      response(
                          params, TaskState.TASK_STATE_COMPLETED, MessageContent.text("done")));
                }),
            WorkflowEngineClientConfig.builder().build())) {
      var client = new DefaultWorkflowEngineClient(transport);

      client.sendTask("test", MessageContent.text("plain task")).join();
      MessageContent taskT =
          A2atMessages.from(
              new MetadataContent(
                  "Task-T/example/v1", "structured task", A2ATExtension.TASK_T.uri()),
              List.of(new TextPart("structured task")));
      CompletionException error =
          assertThrows(CompletionException.class, () -> client.sendTask("test", taskT).join());
      assertTrue(error.getCause().getMessage().contains("does not declare Task-T"));
      assertEquals(1, sends.get());
    }
  }

  @Test
  void standaloneTaskPreservesStandardA2AErrorWithoutCancelingAnUncreatedTask() throws Exception {
    AtomicInteger cancellations = new AtomicInteger();
    RemoteA2AErrorException remoteError =
        RemoteA2AErrorException.fromPayload(
            """
            {"error":{"code":429,"status":"RESOURCE_EXHAUSTED","message":"Task limit reached",
            "details":[{"@type":"type.googleapis.com/google.rpc.ErrorInfo",
            "reason":"TASK_LIMIT_REACHED","domain":"a2a-protocol.org"}]}}
            """);
    A2AJavaClientRuntime runtime =
        new A2AJavaClientRuntime() {
          public Iterable<ClientEvent> sendMessage(
              AgentCard card,
              MessageSendParams params,
              ClientCallContext context,
              Consumer<ClientEvent> sink,
              Consumer<String> logs) {
            throw remoteError;
          }

          public org.a2aproject.sdk.spec.Task cancelTask(
              AgentCard card, String taskId, ClientCallContext context) {
            cancellations.incrementAndGet();
            throw new AssertionError("No remote task was created");
          }

          public void close() {}
        };
    try (var transport =
        new A2ATransport(List.of(card()), runtime, WorkflowEngineClientConfig.builder().build())) {
      var client = new DefaultWorkflowEngineClient(transport);

      CompletionException error =
          assertThrows(
              CompletionException.class,
              () -> client.sendTask("test", MessageContent.text("start")).join());
      RemoteA2AErrorException actual = RemoteA2AErrorException.findIn(error);
      assertNotNull(actual);
      assertEquals(429, actual.getHttpStatus());
      assertEquals("TASK_LIMIT_REACHED", actual.getReason());
      assertEquals(0, cancellations.get());
    }
  }

  @Test
  void answersFinalRoundAndKeepsOriginalRemoteAssociation() throws Exception {
    List<MessageSendParams> sent = new CopyOnWriteArrayList<>();
    var runtime =
        runtime(
            params -> {
              sent.add(params);
              return List.of(
                  response(
                      params,
                      sent.size() == 1
                          ? TaskState.TASK_STATE_INPUT_REQUIRED
                          : TaskState.TASK_STATE_COMPLETED,
                      sent.size() == 1
                          ? negotiation("city1", 3, NegotiationPerformative.PROPOSE)
                          : MessageContent.text("done")));
            });
    AtomicReference<NegotiationRequest> seen = new AtomicReference<>();
    try (var transport =
        new A2ATransport(List.of(card()), runtime, WorkflowEngineClientConfig.builder().build())) {
      var client = new DefaultWorkflowEngineClient(transport);
      client.setControlPoint(
          ControlPoint.builder()
              .onNegotiation(
                  request -> {
                    seen.set(request);
                    return CompletableFuture.completedFuture(
                        new NegotiationReply.Send(
                            negotiation("city1", 3, NegotiationPerformative.ACCEPT)));
                  })
              .build());
      assertEquals(
          "TASK_STATE_COMPLETED",
          client.sendTask("test", MessageContent.text("start")).join().getTaskState());
    }
    assertEquals(2, sent.size());
    assertNull(sent.get(0).message().taskId());
    assertEquals("remote-task", sent.get(1).message().taskId());
    assertEquals(sent.get(0).message().contextId(), sent.get(1).message().contextId());
    assertTrue(seen.get().previousExchanges().isEmpty());
    assertFalse(seen.get().remainingWait().isZero());
    assertEquals(MessageContent.text("start"), seen.get().originalSubmission());
  }

  @Test
  void terminalStateNeverRestartsOldNegotiation() throws Exception {
    AtomicInteger callbacks = new AtomicInteger();
    try (var transport =
        new A2ATransport(
            List.of(card()),
            runtime(
                p ->
                    List.of(
                        response(
                            p,
                            TaskState.TASK_STATE_COMPLETED,
                            negotiation("old", 1, NegotiationPerformative.PROPOSE)))),
            WorkflowEngineClientConfig.builder().build())) {
      var client = new DefaultWorkflowEngineClient(transport);
      client.setControlPoint(
          ControlPoint.builder()
              .onNegotiation(
                  q -> {
                    callbacks.incrementAndGet();
                    return CompletableFuture.failedFuture(new AssertionError());
                  })
              .build());
      client.sendTask("test", MessageContent.text("start")).join();
    }
    assertEquals(0, callbacks.get());
  }

  @Test
  void rejectsWrongContextNewProposeAndMissingCallbackWithoutSendingAgain() throws Exception {
    for (NegotiationReply reply :
        List.of(
            new NegotiationReply.Send(negotiation("other-city", 1, NegotiationPerformative.ACCEPT)),
            new NegotiationReply.Send(negotiation("city1", 1, NegotiationPerformative.PROPOSE)),
            new NegotiationReply.Stop("manual.required", "Need human"))) {
      AtomicInteger sends = new AtomicInteger();
      try (var transport =
          new A2ATransport(
              List.of(card()),
              runtime(
                  p -> {
                    sends.incrementAndGet();
                    return List.of(
                        response(
                            p,
                            TaskState.TASK_STATE_INPUT_REQUIRED,
                            negotiation("city1", 1, NegotiationPerformative.PROPOSE)));
                  }),
              WorkflowEngineClientConfig.builder().build())) {
        var client = new DefaultWorkflowEngineClient(transport);
        client.setControlPoint(
            ControlPoint.builder()
                .onNegotiation(q -> CompletableFuture.completedFuture(reply))
                .build());
        assertThrows(
            CompletionException.class,
            () -> client.sendTask("test", MessageContent.text("start")).join());
      }
      assertEquals(1, sends.get());
    }
  }

  @Test
  void plainInputRequiredIsExplicitlyUnsupported() throws Exception {
    try (var transport =
        new A2ATransport(
            List.of(card()),
            runtime(
                p ->
                    List.of(
                        response(
                            p,
                            TaskState.TASK_STATE_INPUT_REQUIRED,
                            MessageContent.text("question")))),
            WorkflowEngineClientConfig.builder().build())) {
      var client = new DefaultWorkflowEngineClient(transport);
      assertThrows(
          CompletionException.class,
          () -> client.sendTask("test", MessageContent.text("start")).join());
    }
  }

  @Test
  void abortAcknowledgementIsNotDiagnosisSuccessAndPreservesRemoteState() throws Exception {
    AtomicInteger sends = new AtomicInteger();
    try (var transport =
        new A2ATransport(
            List.of(card()),
            runtime(
                p ->
                    List.of(
                        response(
                            p,
                            sends.incrementAndGet() == 1
                                ? TaskState.TASK_STATE_INPUT_REQUIRED
                                : TaskState.TASK_STATE_COMPLETED,
                            negotiation("city1", 1, NegotiationPerformative.PROPOSE)))),
            WorkflowEngineClientConfig.builder().build())) {
      var client = new DefaultWorkflowEngineClient(transport);
      client.setControlPoint(
          ControlPoint.builder()
              .onNegotiation(
                  q ->
                      CompletableFuture.completedFuture(
                          new NegotiationReply.Send(
                              negotiation("city1", 1, NegotiationPerformative.ABORT))))
              .build());
      var result = client.sendTask("test", MessageContent.text("start")).join();
      assertEquals("TASK_STATE_COMPLETED", result.getTaskState());
      assertEquals("negotiation.aborted", result.getFailureCode());
    }
    assertEquals(2, sends.get());
  }

  @Test
  void workingAckAndDuplicateProposeAreObservedWithoutRepeatedSubmissionOrCallback()
      throws Exception {
    for (TaskState secondState :
        List.of(TaskState.TASK_STATE_WORKING, TaskState.TASK_STATE_INPUT_REQUIRED)) {
      AtomicInteger sends = new AtomicInteger();
      AtomicInteger polls = new AtomicInteger();
      AtomicInteger callbacks = new AtomicInteger();
      AtomicReference<MessageSendParams> last = new AtomicReference<>();
      A2AJavaClientRuntime runtime =
          new A2AJavaClientRuntime() {
            public Iterable<ClientEvent> sendMessage(
                AgentCard card,
                MessageSendParams params,
                ClientCallContext context,
                Consumer<ClientEvent> sink,
                Consumer<String> logs) {
              last.set(params);
              return List.of(
                  response(
                      params,
                      sends.incrementAndGet() == 1
                          ? TaskState.TASK_STATE_INPUT_REQUIRED
                          : secondState,
                      negotiation("city1", 1, NegotiationPerformative.PROPOSE)));
            }

            public org.a2aproject.sdk.spec.Task getTask(
                AgentCard card, String taskId, ClientCallContext context) {
              polls.incrementAndGet();
              assertEquals("remote-task", taskId);
              assertNotNull(WireLog.context().get("attempt"));
              return response(
                      last.get(), TaskState.TASK_STATE_COMPLETED, MessageContent.text("diagnosis"))
                  .getTask();
            }

            public void close() {}
          };
      try (var transport =
          new A2ATransport(
              List.of(card()), runtime, WorkflowEngineClientConfig.builder().build())) {
        var client = new DefaultWorkflowEngineClient(transport);
        client.setControlPoint(
            ControlPoint.builder()
                .onNegotiation(
                    q -> {
                      callbacks.incrementAndGet();
                      return CompletableFuture.completedFuture(
                          new NegotiationReply.Send(
                              negotiation("city1", 1, NegotiationPerformative.ACCEPT)));
                    })
                .build());
        assertEquals(
            "TASK_STATE_COMPLETED",
            client
                .sendTask("test", MessageContent.text("start"))
                .get(5, TimeUnit.SECONDS)
                .getTaskState());
        assertEquals(2, sends.get());
        assertEquals(1, callbacks.get());
        assertEquals(1, polls.get());
      }
    }
  }

  @Test
  void canceledOrTimedOutNegotiationNeverSendsLateReply() throws Exception {
    for (boolean timeout : List.of(false, true)) {
      AtomicInteger sends = new AtomicInteger();
      CompletableFuture<NegotiationReply> answer = new CompletableFuture<>();
      CountDownLatch entered = new CountDownLatch(1);
      try (var transport =
          new A2ATransport(
              List.of(card()),
              runtime(
                  p -> {
                    sends.incrementAndGet();
                    return List.of(
                        response(
                            p,
                            TaskState.TASK_STATE_INPUT_REQUIRED,
                            negotiation("city1", 1, NegotiationPerformative.PROPOSE)));
                  }),
              WorkflowEngineClientConfig.builder().sendTimeoutSeconds(1).build())) {
        var client = new DefaultWorkflowEngineClient(transport);
        client.setControlPoint(
            ControlPoint.builder()
                .onNegotiation(
                    q -> {
                      entered.countDown();
                      return answer;
                    })
                .build());
        var result = client.sendTask("test", MessageContent.text("start"));
        assertTrue(entered.await(1, TimeUnit.SECONDS));
        if (timeout) assertThrows(ExecutionException.class, () -> result.get(3, TimeUnit.SECONDS));
        else assertTrue(result.cancel(true));
        answer.complete(
            new NegotiationReply.Send(negotiation("city1", 1, NegotiationPerformative.ACCEPT)));
        assertEquals(1, sends.get());
      }
    }
  }

  @Test
  void closingNonOwningFacadePreventsNewWorkButDoesNotCloseSharedTransport() throws Exception {
    try (var transport =
        new A2ATransport(
            List.of(card()),
            runtime(
                p ->
                    List.of(
                        response(p, TaskState.TASK_STATE_COMPLETED, MessageContent.text("done")))),
            WorkflowEngineClientConfig.builder().build())) {
      var closedClient = new DefaultWorkflowEngineClient(transport);
      closedClient.close();
      assertThrows(
          CompletionException.class,
          () -> closedClient.sendTask("test", MessageContent.text("no")).join());
      assertEquals(
          "TASK_STATE_COMPLETED",
          new DefaultWorkflowEngineClient(transport)
              .sendTask("test", MessageContent.text("yes"))
              .join()
              .getTaskState());
    }
  }
}
