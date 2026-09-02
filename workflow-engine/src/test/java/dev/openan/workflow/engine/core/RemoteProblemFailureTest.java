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
package dev.openan.workflow.engine.core;

import static org.junit.jupiter.api.Assertions.*;

import dev.openan.workflow.engine.StubWorkflowEngineClient;
import dev.openan.workflow.engine.client.RemoteProblemException;
import dev.openan.workflow.engine.control.ControlPoint;
import dev.openan.workflow.engine.model.*;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RemoteProblemFailureTest {
  @ParameterizedTest
  @ValueSource(ints = {400, 429})
  void waitsForTheOtherCityAndSkipsMergeWhileReportingCorrelatedFailure(int status) throws Exception {
    var started = new java.util.concurrent.CountDownLatch(2);
    var reported = new java.util.concurrent.CountDownLatch(1);
    var city1 = new CompletableFuture<SendMessageResult>();
    var city2 = new CompletableFuture<SendMessageResult>();
    var observed = new java.util.concurrent.CopyOnWriteArrayList<Map<String, Object>>();
    var client = new StubWorkflowEngineClient() {
      @Override public CompletableFuture<SendMessageResult> dispatch(
          TaskRequest request, MessageContent content, ControlPoint callbacks) {
        started.countDown();
        return request.getAgentName().equals("city1") ? city1 : city2;
      }
    };
    var merges = new AtomicInteger();
    var callbacks = ControlPoint.builder()
        .onTask(request -> CompletableFuture.completedFuture(MessageContent.text("diagnose")))
        .onSelfTask(request -> {
          merges.incrementAndGet();
          return CompletableFuture.completedFuture(TaskResult.success(List.of("must not merge")));
        }).build();
    var workflow = Workflow.builder().name("parallel-failure").steps(List.of(
        city("city1"), city("city2"),
        WorkflowStep.builder().name("merge").stepType(StepType.SELF_LOOP)
            .subtasks(List.of(Task.builder().agent("host").description("aggregate").build()))
            .next(List.of()).build())).build();
    var events = new dev.openan.workflow.engine.control.EventCallback() {
      @Override public void onEvent(String type, Map<String, Object> data) {
        if (type.equals(dev.openan.workflow.engine.control.EventType.TASK_RESPONSE)) {
          observed.add(data);
          if (Boolean.FALSE.equals(data.get("success"))) reported.countDown();
        }
        if (type.equals(dev.openan.workflow.engine.control.EventType.ERROR)) {
          assertNotNull(data.get("error"));
        }
      }
    };
    var execution = new WorkflowExecutor(workflow, callbacks, client, events, "complaint", "zh").run();
    try {
      assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS));
      city1.completeExceptionally(RemoteProblemException.fromPayload(
          "{\"status\":" + status + ",\"detail\":\"OMC拒绝请求\"}"));
      assertTrue(reported.await(5, java.util.concurrent.TimeUnit.SECONDS));
      assertFalse(execution.isDone(), "The other in-flight diagnosis must be collected");
      assertFalse(city2.isCancelled());
      city2.complete(SendMessageResult.builder().taskState("TASK_STATE_COMPLETED")
          .receivedMessages(List.of(new ReceivedMessage(
              MessageContent.text("city2 diagnosis"), Map.of(), List.of()))).build());
      var result = execution.get(5, java.util.concurrent.TimeUnit.SECONDS);
      assertFalse(result.isSuccess());
      assertEquals(2, result.getHistory().size());
      assertEquals(0, merges.get());
      assertFalse(result.getStepOutputs().containsKey("merge"));
      assertEquals(2, observed.size());
      for (var event : observed) {
        assertNotNull(event.get("executionId"));
        assertNotNull(event.get("taskId"));
      }
      assertEquals(observed.get(0).get("executionId"), observed.get(1).get("executionId"));
      assertNotEquals(observed.get(0).get("taskId"), observed.get(1).get("taskId"));
      var success = result.getHistory().stream().filter(h -> h.get("agent").equals("city2")).findFirst().orElseThrow();
      assertEquals("success", success.get("status"));
      assertEquals(List.of("city2 diagnosis"), success.get("outputs"));
    } finally {
      city1.cancel(true);
      city2.cancel(true);
      execution.cancel(true);
    }
  }

  private static WorkflowStep city(String name) {
    return WorkflowStep.builder().name(name).stepType(StepType.ALL_SUCCESS)
        .subtasks(List.of(Task.builder().agent(name).description("diagnose").build()))
        .next(List.of(new JumpCondition("merge", ""))).build();
  }
  @ParameterizedTest
  @ValueSource(ints = {400, 429})
  void remoteFailureReachesWorkflowHistoryWithoutNegotiationOrSuccessfulOutputs(int status) {
    var problem = RemoteProblemException.fromPayload(
        "{\"status\":" + status + ",\"detail\":\"OMC拒绝请求\",\"type\":\"\"}");
    var calls = new AtomicInteger();
    var negotiations = new AtomicInteger();
    var client = new StubWorkflowEngineClient("omc") {
      @Override
      public CompletableFuture<SendMessageResult> dispatch(
          TaskRequest request, MessageContent content, ControlPoint callbacks) {
        calls.incrementAndGet();
        return CompletableFuture.failedFuture(new CompletionException(problem));
      }
    };
    var step = WorkflowStep.builder().name("diagnose").stepType(StepType.ALL_SUCCESS)
        .subtasks(List.of(Task.builder().agent("omc").description("diagnose").build()))
        .next(List.of()).build();
    var workflow = Workflow.builder().name("remote-failure").steps(List.of(step)).build();
    var callbacks = ControlPoint.builder()
        .onTask(request -> CompletableFuture.completedFuture(MessageContent.text("diagnose")))
        .onNegotiation(request -> {
          negotiations.incrementAndGet();
          return CompletableFuture.failedFuture(new AssertionError("must not negotiate"));
        }).build();
    var result = new WorkflowExecutor(workflow, callbacks, client, null, "complaint", "zh").run().join();
    assertFalse(result.isSuccess());
    assertEquals(1, calls.get());
    assertEquals(0, negotiations.get());
    var history = result.getHistory().get(0);
    assertEquals("failed", history.get("status"));
    assertEquals("remote.problem." + status, history.get("errorCode"));
    assertEquals(List.of(), history.get("outputs"));
    assertTrue(history.get("error").toString().contains("OMC拒绝请求"));
    assertEquals(status, ((Map<?, ?>) history.get("errorDetails")).get("status"));
    assertEquals("", ((Map<?, ?>) history.get("errorDetails")).get("type"));
  }

  @Test
  void knownSecretsAreRedactedAndUnrecognizedProviderErrorsRemainPrivate() {
    var problem = RemoteProblemException.fromPayload(
        "{\"status\":400,\"detail\":\"bad request password=private-value accessSession=private-session\"}");
    var result = FailureMapping.from(problem);
    assertFalse(result.toString().contains("private-value"));
    assertFalse(result.toString().contains("private-session"));
    assertFalse(problem.getMessage().contains("private-value"));
    assertEquals("IllegalStateException",
        FailureMapping.from(new IllegalStateException("raw private provider body")).getError());
  }
}
