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

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.client.MessageEvent;
import org.a2aproject.sdk.client.TaskEvent;
import org.a2aproject.sdk.client.TaskUpdateEvent;
import org.a2aproject.sdk.grpc.SendMessageResponse;
import org.a2aproject.sdk.grpc.StreamResponse;
import org.a2aproject.sdk.grpc.utils.ProtoUtils;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.StreamingEventKind;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskArtifactUpdateEvent;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Parses blocking A2A responses and incrementally framed SSE responses from a gateway. */
public final class GatewayA2AResponseParser {
  private static final Logger log = LoggerFactory.getLogger(GatewayA2AResponseParser.class);

  private static boolean looksLikeSse(String body) {
    String stripped = body.stripLeading();
    return stripped.startsWith("data:")
        || stripped.startsWith("event:")
        || stripped.startsWith("id:")
        || stripped.startsWith(":");
  }

  private static String extractSsePayload(String frame) {
    StringBuilder payload = new StringBuilder();
    boolean dataStarted = false;
    for (String line : frame.replace("\r\n", "\n").split("\n", -1)) {
      if (line.startsWith("data:")) {
        appendLine(payload, line.substring(5).stripLeading());
        dataStarted = true;
      } else if (dataStarted && !isSseControlField(line)) {
        // Tolerate gateways that prefix only the first pretty-printed JSON line.
        appendLine(payload, line);
      }
    }
    return payload.toString();
  }

  private static void appendLine(StringBuilder target, String value) {
    if (!target.isEmpty()) {
      target.append('\n');
    }
    target.append(value);
  }

  private static boolean isSseControlField(String line) {
    return line.startsWith("event:")
        || line.startsWith("id:")
        || line.startsWith("retry:")
        || line.startsWith(":");
  }

  private static void emit(Consumer<ClientEvent> eventSink, ClientEvent event) {
    if (eventSink == null) {
      return;
    }
    try {
      eventSink.accept(event);
    } catch (RuntimeException e) {
      log.warn(
          "[GatewayParser] eventSink failed for eventType={}: {}",
          event.getClass().getSimpleName(),
          e.getMessage(),
          e);
    }
  }

  private static boolean isTerminal(ClientEvent event) {
    if (event instanceof TaskEvent taskEvent) {
      return isTerminal(taskEvent.getTask().status().state());
    }
    if (event instanceof TaskUpdateEvent updateEvent
        && updateEvent.getUpdateEvent() instanceof TaskStatusUpdateEvent statusUpdate) {
      return statusUpdate.isFinal() || isTerminal(statusUpdate.status().state());
    }
    return false;
  }

  private static boolean isTerminal(TaskState state) {
    return state == TaskState.TASK_STATE_COMPLETED
        || state == TaskState.TASK_STATE_FAILED
        || state == TaskState.TASK_STATE_CANCELED
        || state == TaskState.TASK_STATE_REJECTED
        || state == TaskState.TASK_STATE_INPUT_REQUIRED
        || state == TaskState.TASK_STATE_AUTH_REQUIRED;
  }

  private static ClientEvent parsePayload(String json, ParseContext context) {
    var problem = dev.openan.workflow.engine.client.RemoteProblemException.fromPayload(json);
    if (problem != null) throw problem;
    try {
      StreamResponse.Builder builder = StreamResponse.newBuilder();
      JsonFormat.parser().merge(json, builder);
      StreamResponse response = builder.build();
      StreamingEventKind eventKind;
      return switch (response.getPayloadCase()) {
        case MESSAGE -> {
          eventKind = ProtoUtils.FromProto.message(response.getMessage());
          yield new MessageEvent((Message) eventKind);
        }
        case TASK -> {
          eventKind = ProtoUtils.FromProto.task(response.getTask());
          context.task = (Task) eventKind;
          yield new TaskEvent(context.task);
        }
        case STATUS_UPDATE -> {
          eventKind = ProtoUtils.FromProto.taskStatusUpdateEvent(response.getStatusUpdate());
          TaskStatusUpdateEvent update = (TaskStatusUpdateEvent) eventKind;
          context.capture(update.taskId(), update.contextId());
          context.task = Task.builder(context.ensureTask()).status(update.status()).build();
          yield new TaskUpdateEvent(context.task, update);
        }
        case ARTIFACT_UPDATE -> {
          eventKind = ProtoUtils.FromProto.taskArtifactUpdateEvent(response.getArtifactUpdate());
          TaskArtifactUpdateEvent update = (TaskArtifactUpdateEvent) eventKind;
          context.capture(update.taskId(), update.contextId());
          yield new TaskUpdateEvent(context.ensureTask(), update);
        }
        default -> null;
      };
    } catch (InvalidProtocolBufferException e) {
      throw new IllegalArgumentException("Invalid A2A gateway response payload", e);
    }
  }

  /** Parses a complete streaming response body. Retained for batch-compatible callers. */
  public List<ClientEvent> parse(String responseBody, Consumer<ClientEvent> eventSink) {
    StreamingSession session = newStreamingSession(eventSink);
    session.accept(responseBody);
    return session.complete();
  }

  /** Parses the response from the A2A {@code message:send} endpoint. */
  public List<ClientEvent> parseNonStreaming(String responseBody, Consumer<ClientEvent> eventSink) {
    if (responseBody == null || responseBody.isBlank()) {
      return List.of();
    }
    var problem =
        dev.openan.workflow.engine.client.RemoteProblemException.fromPayload(responseBody);
    if (problem != null) throw problem;
    try {
      SendMessageResponse.Builder builder = SendMessageResponse.newBuilder();
      JsonFormat.parser().merge(responseBody, builder);
      SendMessageResponse response = builder.build();
      ClientEvent event =
          switch (response.getPayloadCase()) {
            case MESSAGE -> new MessageEvent(ProtoUtils.FromProto.message(response.getMessage()));
            case TASK -> new TaskEvent(ProtoUtils.FromProto.task(response.getTask()));
            default -> null;
          };
      if (event == null) {
        return List.of();
      }
      emit(eventSink, event);
      return List.of(event);
    } catch (InvalidProtocolBufferException e) {
      throw new IllegalArgumentException("Invalid A2A message:send response payload", e);
    }
  }

  /** Opens a request-scoped parser that preserves task state across streamed response chunks. */
  public StreamingSession newStreamingSession(Consumer<ClientEvent> eventSink) {
    return new StreamingSession(eventSink, null);
  }

  /**
   * Opens a request-scoped parser and notifies {@code completeFrameSink} with each complete SSE
   * frame (or full non-SSE payload) as soon as it is reassembled from raw network chunks.
   */
  public StreamingSession newStreamingSession(
      Consumer<ClientEvent> eventSink, Consumer<String> completeFrameSink) {
    return new StreamingSession(eventSink, completeFrameSink);
  }

  public static final class StreamingSession {
    private final Consumer<ClientEvent> eventSink;
    private final Consumer<String> completeFrameSink;
    private final ParseContext context = new ParseContext();
    private final List<ClientEvent> events = new ArrayList<>();
    private final StringBuilder pending = new StringBuilder();
    private boolean sse;
    private boolean completed;

    private StreamingSession(Consumer<ClientEvent> eventSink, Consumer<String> completeFrameSink) {
      this.eventSink = eventSink;
      this.completeFrameSink = completeFrameSink;
    }

    /** Adds one response body chunk and emits every complete event immediately. */
    public boolean accept(String chunk) {
      ensureOpen();
      int previousSize = events.size();
      if (chunk == null || chunk.isEmpty()) {
        return false;
      }
      pending.append(chunk);
      String candidate = pending.toString();
      if (sse || looksLikeSse(candidate)) {
        sse = true;
        drainSseFrames();
        return hasTerminalEvent(previousSize);
      }
      ClientEvent event = tryParsePayload(candidate);
      if (event != null) {
        pending.setLength(0);
        notifyCompleteFrame(candidate);
        add(event);
      }
      return hasTerminalEvent(previousSize);
    }

    /** Flushes the final partial frame after the upstream response stream completes. */
    public List<ClientEvent> complete() {
      ensureOpen();
      completed = true;
      if (!pending.isEmpty()) {
        if (sse || looksLikeSse(pending.toString())) {
          notifyCompleteFrame(pending.toString());
          String payload = extractSsePayload(pending.toString());
          if (!payload.isBlank()) {
            add(parsePayload(payload, context));
          }
        } else {
          notifyCompleteFrame(pending.toString());
          add(parsePayload(pending.toString(), context));
        }
        pending.setLength(0);
      }
      return List.copyOf(events);
    }

    private void notifyCompleteFrame(String frame) {
      if (completeFrameSink == null || frame == null || frame.isBlank()) {
        return;
      }
      try {
        completeFrameSink.accept(frame);
      } catch (RuntimeException e) {
        log.warn("[GatewayParser] completeFrameSink failed: {}", e.getMessage(), e);
      }
    }

    private void drainSseFrames() {
      String normalized = pending.toString().replace("\r\n", "\n");
      int boundary;
      while ((boundary = normalized.indexOf("\n\n")) >= 0) {
        String frame = normalized.substring(0, boundary);
        normalized = normalized.substring(boundary + 2);
        notifyCompleteFrame(frame);
        String payload = extractSsePayload(frame);
        if (!payload.isBlank()) {
          add(parsePayload(payload, context));
        }
      }
      pending.setLength(0);
      pending.append(normalized);
    }

    private ClientEvent tryParsePayload(String candidate) {
      try {
        return parsePayload(candidate, context);
      } catch (IllegalArgumentException ignored) {
        // A gateway response may split one JSON payload across multiple Flux items.
        return null;
      }
    }

    private void add(ClientEvent event) {
      if (event == null) {
        return;
      }
      events.add(event);
      emit(eventSink, event);
    }

    private void ensureOpen() {
      if (completed) {
        throw new IllegalStateException("Streaming response parser is already complete");
      }
    }

    private boolean hasTerminalEvent(int fromIndex) {
      for (int i = fromIndex; i < events.size(); i++) {
        if (isTerminal(events.get(i))) {
          return true;
        }
      }
      return false;
    }
  }

  private static final class ParseContext {
    private Task task;
    private String taskId;
    private String contextId;

    private void capture(String newTaskId, String newContextId) {
      if (taskId == null) {
        taskId = newTaskId;
      }
      if (contextId == null) {
        contextId = newContextId;
      }
    }

    private Task ensureTask() {
      if (task == null) {
        task =
            Task.builder()
                .id(taskId == null ? "" : taskId)
                .contextId(contextId == null ? "" : contextId)
                .status(new TaskStatus(TaskState.TASK_STATE_SUBMITTED))
                .build();
      }
      return task;
    }
  }
}
