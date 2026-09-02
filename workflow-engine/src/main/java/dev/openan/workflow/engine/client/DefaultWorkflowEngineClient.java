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

import dev.openan.workflow.engine.control.ControlPoint;
import dev.openan.workflow.engine.control.EventCallback;
import dev.openan.workflow.engine.control.EventType;
import dev.openan.workflow.engine.model.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.openan.a2at.sdk.core.model.NegotiationContext;
import net.openan.a2at.sdk.core.model.NegotiationPerformative;
import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.client.MessageEvent;
import org.a2aproject.sdk.client.TaskUpdateEvent;
import org.a2aproject.sdk.jsonrpc.common.wrappers.ListTasksResult;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.ListTasksParams;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Coordinates A2A task interaction; all content generation and semantic validation belong to the
 * host.
 */
public class DefaultWorkflowEngineClient implements WorkflowEngineClient, AutoCloseable {
  private static final Logger log = LoggerFactory.getLogger(DefaultWorkflowEngineClient.class);
  private final A2ATransport transport;
  private final int maxNegotiationExchanges;
  private final boolean closeTransportOnClose;
  private final AtomicBoolean closed = new AtomicBoolean();
  private final Set<Invocation> invocations = java.util.concurrent.ConcurrentHashMap.newKeySet();
  private volatile EventCallback eventCallback = new EventCallback();
  private volatile ControlPoint controlPoint;

  /** Creates a caller-owned transport facade. */
  public DefaultWorkflowEngineClient(A2ATransport transport) {
    this(transport, 3, false);
  }

  /** Uses the configured resource exchange budget, not an SDK protocol round counter. */
  public DefaultWorkflowEngineClient(A2ATransport transport, WorkflowEngineClientConfig config) {
    this(transport, config.getMaxNegotiationExchanges(), false);
  }

  private DefaultWorkflowEngineClient(A2ATransport transport, int maxExchanges, boolean owning) {
    this.transport = Objects.requireNonNull(transport, "transport");
    if (maxExchanges < 1)
      throw new IllegalArgumentException("Negotiation exchange budget must be positive");
    this.maxNegotiationExchanges = maxExchanges;
    this.closeTransportOnClose = owning;
  }

  /** Creates a facade owning the supplied transport. */
  public static DefaultWorkflowEngineClient owning(A2ATransport transport) {
    return new DefaultWorkflowEngineClient(transport, 3, true);
  }

  /** Creates an owning facade with explicit resource settings. */
  public static DefaultWorkflowEngineClient owning(
      A2ATransport transport, WorkflowEngineClientConfig config) {
    return new DefaultWorkflowEngineClient(transport, config.getMaxNegotiationExchanges(), true);
  }

  private static ReceivedMessage negotiationResponse(SendMessageResult result) {
    for (ReceivedMessage received : result.getReceivedMessages()) {
      boolean present =
          received.taskMetadata().containsKey(A2ATExtension.NEGOTIATION_T.uri())
              || received.message() != null
                  && received.message().metadata().containsKey(A2ATExtension.NEGOTIATION_T.uri())
              || received.artifacts().stream()
                  .anyMatch(
                      a ->
                          a.metadata() != null
                              && a.metadata().containsKey(A2ATExtension.NEGOTIATION_T.uri()));
      if (present) return received;
    }
    throw new IllegalArgumentException(
        "Unsupported INPUT_REQUIRED interaction: no Negotiation-T Propose");
  }

  private static NegotiationContext validateReply(
      NegotiationContext original, MessageContent reply) {
    if (!reply.extensions().contains(A2ATExtension.NEGOTIATION_T.uri())
        || !reply.metadata().containsKey(A2ATExtension.NEGOTIATION_T.uri())) {
      throw new IllegalArgumentException("Negotiation reply must carry and activate Negotiation-T");
    }
    NegotiationContext ending = A2atMessages.contextOf(reply.metadata());
    if (!original.id().equals(ending.id())
        || original.round() != ending.round()
        || original.maxRounds() != ending.maxRounds()
        || ending.performative() == NegotiationPerformative.PROPOSE) {
      throw new IllegalArgumentException(
          "Reply does not match the received negotiation context/round");
    }
    return ending;
  }

  @Override
  public void setControlPoint(ControlPoint callbacks) {
    this.controlPoint = callbacks;
  }

  @Override
  public void setEventCallback(EventCallback callback) {
    this.eventCallback = callback == null ? new EventCallback() : callback;
  }

  @Override
  public long callbackTimeoutSeconds() {
    return transport.sendTimeoutSeconds();
  }

  @Override
  public CompletableFuture<SendMessageResult> sendMessage(
      String agentName, MessageContent content) {
    TaskRequest request =
        TaskRequest.builder()
            .agentName(agentName)
            .executionId(UUID.randomUUID().toString())
            .taskId(UUID.randomUUID().toString())
            .build();
    return dispatch(request, content, controlPoint);
  }

  @Override
  public CompletableFuture<SendMessageResult> dispatch(
      TaskRequest request, MessageContent content, ControlPoint callbacks) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(content, "onTask returned null content");
    AgentCard card = transport.getCard(request.getAgentName());
    if (closed.get() || card == null)
      return CompletableFuture.failedFuture(
          new IllegalStateException(
              closed.get()
                  ? "Workflow client closed"
                  : "Agent not found: " + request.getAgentName()));
    Invocation invocation = new Invocation(request, content, callbacks);
    invocations.add(invocation);
    invocation.completion.orTimeout(callbackTimeoutSeconds(), TimeUnit.SECONDS);
    invocation.completion.whenComplete(
        (result, error) -> {
          invocations.remove(invocation);
          transport.closeConversation(card, invocation.contextId);
          if (error != null && invocation.exchanges > 0)
            emit(
                EventType.NEGOTIATION_FAILED,
                Map.of(
                    "agent",
                    request.getAgentName(),
                    "exchange",
                    invocation.exchanges,
                    "errorType",
                    error.getClass().getSimpleName()));
        });
    if (closed.get()) invocation.completion.cancel(true);
    send(card, invocation, content, null)
        .thenCompose(result -> advance(card, invocation, result))
        .whenComplete(
            (result, error) -> {
              if (error != null) invocation.completion.completeExceptionally(error);
              else invocation.completion.complete(result);
            });
    return invocation.completion;
  }

  private CompletableFuture<SendMessageResult> send(
      AgentCard card, Invocation invocation, MessageContent content, String taskId) {
    if (!invocation.active())
      return CompletableFuture.failedFuture(
          new java.util.concurrent.CancellationException("Task interaction is no longer active"));
    emit(
        EventType.AGENT_REQUEST,
        Map.of("agent", invocation.task.getAgentName(), "content", content));
    Map<String, String> trace = invocationTrace(invocation);
    return WireLog.call(
        trace,
        () ->
            transport.send(
                card,
                invocation.task.getAgentName(),
                content,
                invocation.contextId,
                taskId,
                event -> forwardIntermediateEvent(event, invocation.task.getAgentName()),
                invocation::active));
  }

  private Map<String, String> invocationTrace(Invocation invocation) {
    Map<String, String> trace = new HashMap<>();
    if (invocation.task.getExecutionId() != null)
      trace.put("executionId", invocation.task.getExecutionId());
    if (invocation.task.getTaskId() != null)
      trace.put("logicalTaskId", invocation.task.getTaskId());
    trace.put("attempt", invocation.attempt);
    trace.put("contextId", invocation.contextId);
    return trace;
  }

  private CompletableFuture<SendMessageResult> advance(
      AgentCard card, Invocation invocation, SendMessageResult result) {
    if (!invocation.active())
      return CompletableFuture.failedFuture(
          new java.util.concurrent.CancellationException("Task interaction is no longer active"));
    if (result.getTask() != null) {
      if (result.getTask().id() == null
          || result.getTask().id().isBlank()
          || !invocation.contextId.equals(result.getTask().contextId())
          || invocation.remoteTaskId != null
              && !invocation.remoteTaskId.equals(result.getTask().id())) {
        return CompletableFuture.failedFuture(
            new IllegalArgumentException("Remote task/context identity changed"));
      }
      invocation.remoteTaskId = result.getTask().id();
    }
    if ("TASK_STATE_SUBMITTED".equals(result.getTaskState())
        || "TASK_STATE_WORKING".equals(result.getTaskState())) {
      return observeTask(card, invocation).thenCompose(next -> advance(card, invocation, next));
    }
    if (!"TASK_STATE_INPUT_REQUIRED".equals(result.getTaskState())) {
      emitAgentResponse(invocation.task.getAgentName(), result);
      return CompletableFuture.completedFuture(result);
    }
    if (result.getTask() == null || result.getTask().id() == null) {
      return CompletableFuture.failedFuture(
          new IllegalArgumentException("INPUT_REQUIRED has no remote task identity"));
    }
    String remoteTask = result.getTask().id();
    ReceivedMessage received = negotiationResponse(result);
    NegotiationContext context = A2atMessages.contextOf(received);
    if (context.performative() != NegotiationPerformative.PROPOSE || context.isExhausted()) {
      return CompletableFuture.failedFuture(
          new IllegalArgumentException("Expected a valid Negotiation-T Propose"));
    }
    NegotiationContext previous = invocation.contexts.put(context.id(), context);
    if (previous != null
        && (context.round() < previous.round() || context.maxRounds() != previous.maxRounds())) {
      return CompletableFuture.failedFuture(
          new IllegalArgumentException("Negotiation round regressed or maxRounds changed"));
    }
    String key = remoteTask + ":" + context.id() + ":" + context.round();
    if (!invocation.answered.add(key)) {
      return observeTask(card, invocation).thenCompose(next -> advance(card, invocation, next));
    }
    if (++invocation.exchanges > maxNegotiationExchanges) {
      return CompletableFuture.failedFuture(
          new IllegalStateException("Negotiation exchange budget exhausted; no Abort generated"));
    }
    List<NegotiationRequest.Exchange> history =
        invocation.history.computeIfAbsent(context.id(), ignored -> new ArrayList<>());
    NegotiationRequest request =
        new NegotiationRequest(
            invocation.task, invocation.original, received, history, invocation.remaining());
    emit(
        EventType.NEGOTIATION_REQUEST,
        Map.of(
            "agent",
            invocation.task.getAgentName(),
            "request",
            request,
            "exchange",
            invocation.exchanges));
    CompletableFuture<NegotiationReply> answer;
    try {
      answer =
          Objects.requireNonNull(
              Objects.requireNonNull(invocation.callbacks, "onNegotiation handler is required")
                  .onNegotiation(request),
              "onNegotiation returned null future");
    } catch (RuntimeException error) {
      return CompletableFuture.failedFuture(error);
    }
    return answer.thenCompose(
        reply -> {
          Objects.requireNonNull(reply, "onNegotiation returned null reply");
          if (!invocation.active())
            return CompletableFuture.failedFuture(
                new java.util.concurrent.CancellationException("Late negotiation reply ignored"));
          history.add(new NegotiationRequest.Exchange(received, reply));
          if (reply instanceof NegotiationReply.Stop stop) {
            return CompletableFuture.failedFuture(
                new BusinessFailure(stop.code(), stop.reason(), Map.of()));
          }
          MessageContent content = ((NegotiationReply.Send) reply).content();
          NegotiationContext ending = validateReply(context, content);
          emit(
              EventType.NEGOTIATION_RESOLVED,
              Map.of(
                  "agent",
                  invocation.task.getAgentName(),
                  "exchange",
                  invocation.exchanges,
                  "reply",
                  reply));
          return send(card, invocation, content, remoteTask)
              .thenCompose(
                  next -> {
                    if (ending.performative() == NegotiationPerformative.ABORT) {
                      // A remote completion/ACK after Abort is not successful diagnosis.
                      next.setFailureCode("negotiation.aborted");
                      next.setFailureMessage(
                          "Business sent Abort; diagnosis was not completed successfully");
                      emitAgentResponse(invocation.task.getAgentName(), next);
                      return CompletableFuture.completedFuture(next);
                    }
                    return advance(card, invocation, next);
                  });
        });
  }

  /** A send ACK is not a task result. Observe without resending the business command. */
  private CompletableFuture<SendMessageResult> observeTask(AgentCard card, Invocation invocation) {
    if (invocation.remoteTaskId == null)
      return CompletableFuture.failedFuture(
          new IllegalArgumentException("Non-terminal response has no remote task identity"));
    return CompletableFuture.supplyAsync(
            () -> {
              if (!invocation.active())
                throw new java.util.concurrent.CancellationException("Task wait ended");
              return Boolean.TRUE;
            },
            CompletableFuture.delayedExecutor(250, TimeUnit.MILLISECONDS))
        .thenCompose(
            ignored -> {
              if (!invocation.active())
                return CompletableFuture.failedFuture(
                    new java.util.concurrent.CancellationException("Task wait ended"));
              return WireLog.call(
                  invocationTrace(invocation),
                  () ->
                      transport.getTask(
                          card, invocation.task.getAgentName(), invocation.remoteTaskId));
            });
  }

  private void emit(String type, Map<String, Object> data) {
    try {
      eventCallback.onEvent(type, data);
    } catch (RuntimeException error) {
      log.warn("Event callback failed for {}", type, error);
    }
  }

  private void emitAgentResponse(String agentName, SendMessageResult result) {
    Map<String, Object> data = new HashMap<>();
    data.put("agent", agentName);
    data.put("response", result.getText());
    data.put("receivedMessages", result.getReceivedMessages());
    emit(EventType.AGENT_RESPONSE, data);
  }

  private void forwardIntermediateEvent(ClientEvent event, String agentName) {
    Map<String, Object> data = ClientEventMapper.toMap(event, agentName);
    if (event instanceof TaskUpdateEvent tue) {
      if (tue.getUpdateEvent() instanceof TaskStatusUpdateEvent sue) {
        String state = sue.status().state().name();
        log.info(
            "[EngineClient] Agent {} status update: {} (final={})",
            agentName,
            state,
            sue.isFinal());
        emit(EventType.AGENT_STATUS_UPDATE, data);
      } else if (tue.getUpdateEvent()
          instanceof org.a2aproject.sdk.spec.TaskArtifactUpdateEvent ae) {
        log.info(
            "[EngineClient] Agent {} artifact update: {} ({})",
            agentName,
            ae.artifact().name(),
            ae.artifact().artifactId());
        emit(EventType.AGENT_ARTIFACT_UPDATE, data);
      }
    } else if (event instanceof MessageEvent) {
      String text = (String) data.getOrDefault("text", "");
      log.info("[EngineClient] Agent {} message event: {} chars", agentName, text.length());
      emit(EventType.AGENT_MESSAGE_EVENT, data);
    }
  }

  @Override
  public CompletableFuture<SendMessageResult> getTask(String agentName, String taskId) {
    if (closed.get())
      return CompletableFuture.failedFuture(new IllegalStateException("Workflow client closed"));
    if (agentName == null || agentName.isBlank() || taskId == null || taskId.isBlank()) {
      return CompletableFuture.failedFuture(
          new IllegalArgumentException("agentName and taskId must not be blank"));
    }
    AgentCard agentCard = transport.getCard(agentName);
    if (agentCard == null) {
      return CompletableFuture.failedFuture(new RuntimeException("Agent not found: " + agentName));
    }
    return transport.getTask(agentCard, agentName, taskId);
  }

  @Override
  public CompletableFuture<ListTasksResult> listTasks(String agentName, ListTasksParams params) {
    if (closed.get())
      return CompletableFuture.failedFuture(new IllegalStateException("Workflow client closed"));
    if (agentName == null || agentName.isBlank() || params == null) {
      return CompletableFuture.failedFuture(
          new IllegalArgumentException("agentName and params must not be null or blank"));
    }
    AgentCard agentCard = transport.getCard(agentName);
    if (agentCard == null) {
      return CompletableFuture.failedFuture(new RuntimeException("Agent not found: " + agentName));
    }
    return transport.listTasks(agentCard, agentName, params);
  }

  @Override
  public CompletableFuture<SendMessageResult> cancelTask(String agentName, String taskId) {
    if (closed.get())
      return CompletableFuture.failedFuture(new IllegalStateException("Workflow client closed"));
    if (agentName == null || agentName.isBlank() || taskId == null || taskId.isBlank()) {
      return CompletableFuture.failedFuture(
          new IllegalArgumentException("agentName and taskId must not be blank"));
    }
    AgentCard agentCard = transport.getCard(agentName);
    if (agentCard == null) {
      return CompletableFuture.failedFuture(new RuntimeException("Agent not found: " + agentName));
    }
    return transport.cancelTask(agentCard, agentName, taskId);
  }

  @Override
  public CompletableFuture<SendMessageResult> subscribeToTask(
      String agentName,
      String taskId,
      java.util.function.Consumer<java.util.Map<String, Object>> eventCallback) {
    if (closed.get())
      return CompletableFuture.failedFuture(new IllegalStateException("Workflow client closed"));
    if (agentName == null || agentName.isBlank() || taskId == null || taskId.isBlank()) {
      return CompletableFuture.failedFuture(
          new IllegalArgumentException("agentName and taskId must not be blank"));
    }
    AgentCard agentCard = transport.getCard(agentName);
    if (agentCard == null) {
      return CompletableFuture.failedFuture(new RuntimeException("Agent not found: " + agentName));
    }
    return transport.subscribeToTask(
        agentCard,
        agentName,
        taskId,
        event -> {
          forwardIntermediateEvent(event, agentName);
          if (eventCallback != null) {
            eventCallback.accept(ClientEventMapper.toMap(event, agentName));
          }
        });
  }

  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) return;
    invocations.forEach(invocation -> invocation.completion.cancel(true));
    if (closeTransportOnClose) transport.close();
  }

  private final class Invocation {
    final TaskRequest task;
    final MessageContent original;
    final ControlPoint callbacks;
    final String contextId = UUID.randomUUID().toString();
    final String attempt = UUID.randomUUID().toString();
    final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(callbackTimeoutSeconds());
    final CompletableFuture<SendMessageResult> completion = new CompletableFuture<>();
    final Map<String, List<NegotiationRequest.Exchange>> history = new HashMap<>();
    final Set<String> answered = new HashSet<>();
    final Map<String, NegotiationContext> contexts = new HashMap<>();
    String remoteTaskId;
    int exchanges;

    Invocation(TaskRequest task, MessageContent content, ControlPoint callbacks) {
      this.task = task;
      this.original = content;
      this.callbacks = callbacks;
    }

    Duration remaining() {
      return Duration.ofNanos(Math.max(0, deadline - System.nanoTime()));
    }

    boolean active() {
      return !closed.get() && !completion.isDone() && !remaining().isZero();
    }
  }
}
