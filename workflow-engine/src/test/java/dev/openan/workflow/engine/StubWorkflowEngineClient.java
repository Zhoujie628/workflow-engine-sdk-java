/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 *    Licensed under the Apache License, Version 2.0 (the License); you may
 *    not use this file except in compliance with the License. You may obtain
 *    a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an AS IS BASIS, WITHOUT
 *    WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *    License for the specific language governing permissions and limitations
 *    under the License.
 */

package dev.openan.workflow.engine;

import dev.openan.workflow.engine.client.WorkflowEngineClient;
import dev.openan.workflow.engine.control.ControlPoint;
import dev.openan.workflow.engine.control.EventCallback;
import dev.openan.workflow.engine.model.SendMessageResult;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Stub WorkflowEngineClient for testing. Records all sends and returns canned responses. No network
 * access.
 */
public class StubWorkflowEngineClient implements WorkflowEngineClient {

  private final List<SentMessage> sent = Collections.synchronizedList(new ArrayList<>());
  private final Map<String, String> cannedResponses = new HashMap<>();
  private final List<String> agentNames = new ArrayList<>();
  private EventCallback eventCallback = new EventCallback();
  private ControlPoint controlPoint;
  private String defaultResponse = "stub-response";
  private String defaultTaskState = "TASK_STATE_COMPLETED";
  private List<Object> defaultOutputs = List.of();

  public StubWorkflowEngineClient(String... agentNames) {
    this.agentNames.addAll(List.of(agentNames));
  }

  public StubWorkflowEngineClient withDefaultOutputs(List<Object> outputs) {
    defaultOutputs = outputs;
    return this;
  }

  public StubWorkflowEngineClient withResponse(String agentName, String text) {
    cannedResponses.put(agentName, text);
    return this;
  }

  public StubWorkflowEngineClient withDefaultResponse(String text) {
    this.defaultResponse = text;
    return this;
  }

  public StubWorkflowEngineClient withDefaultTaskState(String state) {
    this.defaultTaskState = state;
    return this;
  }

  @Override
  public CompletableFuture<SendMessageResult> dispatch(
      dev.openan.workflow.engine.model.TaskRequest request,
      dev.openan.workflow.engine.model.MessageContent content,
      ControlPoint callbacks) {
    return sendMessage(request.getAgentName(), content);
  }

  @Override
  public CompletableFuture<SendMessageResult> sendMessage(
      String agentName, dev.openan.workflow.engine.model.MessageContent content) {
    String message =
        content.parts().stream()
            .filter(org.a2aproject.sdk.spec.TextPart.class::isInstance)
            .map(org.a2aproject.sdk.spec.TextPart.class::cast)
            .map(org.a2aproject.sdk.spec.TextPart::text)
            .collect(java.util.stream.Collectors.joining());
    String contextId = null;
    Map<String, Object> metadata = content.metadata();
    sent.add(new SentMessage(agentName, message, contextId, metadata));
    String text = cannedResponses.getOrDefault(agentName, defaultResponse);
    if (eventCallback != null) {
      eventCallback.onEvent(
          "agent_request",
          Map.of(
              "agent",
              agentName,
              "request",
              message,
              "metadata",
              metadata != null ? metadata : Map.of()));
    }
    SendMessageResult result =
        SendMessageResult.builder()
            .receivedMessages(
                List.of(
                    new dev.openan.workflow.engine.model.ReceivedMessage(
                        dev.openan.workflow.engine.model.MessageContent.parts(
                            defaultOutputs.isEmpty()
                                ? text.isEmpty()
                                    ? List.of()
                                    : List.of(new org.a2aproject.sdk.spec.TextPart(text))
                                : defaultOutputs.stream()
                                    .<org.a2aproject.sdk.spec.Part<?>>map(
                                        value -> new org.a2aproject.sdk.spec.DataPart(value))
                                    .toList()),
                        Map.of(),
                        List.of())))
            .text(text)
            .taskState(defaultTaskState)
            .metadata(new HashMap<>())
            .build();
    if (eventCallback != null) {
      eventCallback.onEvent("agent_response", Map.of("agent", agentName, "response", text));
    }
    return CompletableFuture.completedFuture(result);
  }

  @Override
  public void setControlPoint(ControlPoint controlPoint) {
    this.controlPoint = controlPoint;
  }

  @Override
  public void setEventCallback(EventCallback callback) {
    this.eventCallback = callback != null ? callback : new EventCallback();
  }

  @Override
  public CompletableFuture<SendMessageResult> getTask(String agentName, String taskId) {
    return cannedResult(agentName, defaultTaskState);
  }

  @Override
  public CompletableFuture<SendMessageResult> cancelTask(String agentName, String taskId) {
    return cannedResult(agentName, "TASK_STATE_CANCELED");
  }

  @Override
  public CompletableFuture<SendMessageResult> subscribeToTask(
      String agentName,
      String taskId,
      java.util.function.Consumer<Map<String, Object>> eventCallback) {
    return cannedResult(agentName, defaultTaskState);
  }

  private CompletableFuture<SendMessageResult> cannedResult(String agentName, String taskState) {
    return CompletableFuture.completedFuture(
        SendMessageResult.builder()
            .text(cannedResponses.getOrDefault(agentName, defaultResponse))
            .taskState(taskState)
            .metadata(Map.of())
            .build());
  }

  @Override
  public void close() {}

  public List<SentMessage> getSentMessages() {
    return new ArrayList<>(sent);
  }

  public int getSentCount() {
    return sent.size();
  }

  public static final class SentMessage {
    public final String agentName;
    public final String message;
    public final String contextId;
    public final Map<String, Object> metadata;

    public SentMessage(
        String agentName, String message, String contextId, Map<String, Object> metadata) {
      this.agentName = agentName;
      this.message = message;
      this.contextId = contextId;
      this.metadata = metadata;
    }
  }
}
