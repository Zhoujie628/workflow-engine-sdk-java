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

package dev.openan.workflow.engine.spring;

import com.google.protobuf.util.JsonFormat;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.a2aproject.sdk.grpc.SendMessageRequest;
import org.a2aproject.sdk.grpc.StreamResponse;
import org.a2aproject.sdk.grpc.utils.ProtoUtils;
import org.a2aproject.sdk.server.ServerCallContext;
import org.a2aproject.sdk.server.auth.TaskOperation;
import org.a2aproject.sdk.server.extensions.A2AExtensions;
import org.a2aproject.sdk.server.requesthandlers.RequestHandler;
import org.a2aproject.sdk.server.version.A2AVersionValidator;
import org.a2aproject.sdk.spec.A2AError;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.InternalError;
import org.a2aproject.sdk.spec.InvalidRequestError;
import org.a2aproject.sdk.spec.MessageSendParams;
import org.a2aproject.sdk.spec.StreamingEventKind;
import org.a2aproject.sdk.spec.TaskIdParams;
import org.a2aproject.sdk.spec.UnsupportedOperationError;
import org.a2aproject.sdk.transport.rest.handler.RestHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Spring MVC controller exposing A2A message and task endpoints.
 *
 * <p>Maps six endpoints (no AgentCard retrieval -- cards come from the registry center):
 *
 * <ul>
 *   <li>{@code POST {prefix}/message:send} - synchronous message send
 *   <li>{@code POST {prefix}/message:stream} - SSE streaming message send
 *   <li>{@code GET {prefix}/tasks/{id}} - query one remote task
 *   <li>{@code GET {prefix}/tasks} - list visible tasks with optional filters
 *   <li>{@code POST {prefix}/tasks/{id}:cancel} - cancel a remote task
 *   <li>{@code POST {prefix}/tasks/{id}:subscribe} - SSE subscription to task updates
 * </ul>
 *
 * <p>The path prefix is configurable via {@code a2at.server.path-prefix}. The controller delegates
 * to {@link RestHandler} (non-streaming) and {@link RequestHandler} (streaming). The {@link
 * ServerCallContext} is built from {@link HttpServletRequest} headers, preserving the
 * A2A-Extensions header. Streaming endpoints return {@link SseEmitter}; a request rejected before
 * the stream is created throws {@link A2AError}, which {@link #a2aError(A2AError)} maps to a
 * non-2xx response with the standard A2A error envelope.
 */
@RestController
public class A2AController {

  private static final Logger log = LoggerFactory.getLogger(A2AController.class);
  private static final MediaType A2A_JSON = MediaType.parseMediaType("application/a2a+json");

  private final RestHandler restHandler;
  private final RequestHandler requestHandler;
  private final AgentCard agentCard;
  private final AtomicInteger activeStreams = new AtomicInteger();
  private final Object streamCompletionMonitor = new Object();

  public A2AController(
      RestHandler restHandler, RequestHandler requestHandler, AgentCard agentCard) {
    this.restHandler = restHandler;
    this.requestHandler = requestHandler;
    this.agentCard = agentCard;
  }

  @PostMapping("${a2at.server.path-prefix}/message:send")
  public ResponseEntity<String> sendMessage(HttpServletRequest req, @RequestBody String body) {
    var ctx = buildContext(req);
    var resp = restHandler.sendMessage(ctx, "", body);
    return toResponse(resp);
  }

  @PostMapping(
      value = "${a2at.server.path-prefix}/message:stream",
      produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter streamMessage(HttpServletRequest req, @RequestBody String body) {
    try {
      var ctx = buildContext(req);
      requireStreamingSupport();
      A2AVersionValidator.validateProtocolVersion(agentCard, ctx);
      A2AExtensions.validateRequiredExtensions(agentCard, ctx);

      SendMessageRequest.Builder builder = SendMessageRequest.newBuilder();
      JsonFormat.parser().merge(body, builder);
      MessageSendParams params = ProtoUtils.FromProto.messageSendParams(builder.build());
      requestHandler.authorizeTaskAccess(
          params.message().taskId(), ctx, TaskOperation.MESSAGE_SEND_STREAM);
      Flow.Publisher<StreamingEventKind> publisher =
          requestHandler.onMessageSendStream(params, ctx);
      return subscribeToEmitter(publisher, ctx);
    } catch (A2AError e) {
      log.warn("[SSE] A2A request rejected before stream creation: {}", e.getMessage());
      throw e;
    } catch (com.google.protobuf.InvalidProtocolBufferException e) {
      log.warn("[SSE] Invalid A2A request body: {}", e.getMessage());
      throw new InvalidRequestError("Invalid A2A request body");
    } catch (Exception e) {
      log.error("[SSE] Setup failed before stream creation: {}", e.getClass().getSimpleName(), e);
      throw new InternalError("Streaming request setup failed");
    }
  }

  /**
   * Maps a pre-stream {@link A2AError} to a non-2xx response with the standard A2A error envelope.
   *
   * <p>Must be an exception handler rather than a {@code ResponseEntity} return so that streaming
   * endpoints can declare {@link SseEmitter} as their return type. A wildcard {@code
   * ResponseEntity<?>} return is not picked up by Spring's emitter handler and the emitter body is
   * rejected by the message converters, turning every stream into a 500 response.
   */
  @ExceptionHandler(A2AError.class)
  ResponseEntity<String> a2aError(A2AError error) {
    var response = restHandler.createErrorResponse(error);
    return toResponse(response);
  }

  private SseEmitter subscribeToEmitter(
      Flow.Publisher<StreamingEventKind> publisher, ServerCallContext context) {
    AtomicReference<Flow.Subscription> subscription = new AtomicReference<>();
    AtomicBoolean released = new AtomicBoolean();
    Runnable release =
        () -> {
          if (released.compareAndSet(false, true)) {
            Flow.Subscription active = subscription.get();
            if (active != null) {
              try {
                active.cancel();
              } catch (RuntimeException error) {
                log.warn("[SSE] Failed to cancel publisher subscription: {}", error.getMessage());
              }
            }
            try {
              context.invokeEventConsumerCancelCallback();
            } catch (RuntimeException error) {
              log.warn("[SSE] Failed to notify SDK event consumer cancellation: {}", error.getMessage());
            }
          }
        };
    SseEmitter emitter = track(new SseEmitter(0L), release);
    final AtomicLong seq = new AtomicLong(0);
    publisher.subscribe(
        new Flow.Subscriber<>() {
          @Override
          public void onSubscribe(Flow.Subscription s) {
            if (!subscription.compareAndSet(null, s) || released.get()) {
              s.cancel();
            } else {
              s.request(1);
            }
          }

          @Override
          public void onNext(StreamingEventKind item) {
            try {
              StreamResponse sr = ProtoUtils.ToProto.streamResponse(item);
              String compact = JsonFormat.printer().omittingInsignificantWhitespace().print(sr);
              emitter.send(
                  SseEmitter.event()
                      .id(Long.toString(seq.incrementAndGet()))
                      .data(compact, MediaType.APPLICATION_JSON));
            } catch (Exception e) {
              log.error("[SSE] Write failed: {}", e.getMessage());
              release.run();
              emitter.completeWithError(e);
              return;
            }
            Flow.Subscription active = subscription.get();
            if (active != null && !released.get()) {
              active.request(1);
            }
          }

          @Override
          public void onError(Throwable t) {
            log.error("[SSE] Stream error: {}", t.getMessage());
            release.run();
            emitter.completeWithError(t);
          }

          @Override
          public void onComplete() {
            release.run();
            emitter.complete();
          }
        });
    return emitter;
  }

  @GetMapping("${a2at.server.path-prefix}/tasks/{id}")
  public ResponseEntity<String> getTask(HttpServletRequest req, @PathVariable("id") String taskId) {
    var ctx = buildContext(req);
    var resp = restHandler.getTask(ctx, "", taskId, null);
    return toResponse(resp);
  }

  @GetMapping("${a2at.server.path-prefix}/tasks")
  public ResponseEntity<String> listTasks(
      HttpServletRequest req,
      @RequestParam(required = false) String contextId,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) Integer pageSize,
      @RequestParam(required = false) String pageToken,
      @RequestParam(required = false) Integer historyLength,
      @RequestParam(required = false) String statusTimestampAfter,
      @RequestParam(required = false) Boolean includeArtifacts) {
    var resp =
        restHandler.listTasks(
            buildContext(req),
            "",
            contextId,
            status,
            pageSize,
            pageToken,
            historyLength,
            statusTimestampAfter,
            includeArtifacts);
    return toResponse(resp);
  }

  @PostMapping("${a2at.server.path-prefix}/tasks/{id}:cancel")
  public ResponseEntity<String> cancelTask(
      HttpServletRequest req,
      @PathVariable("id") String taskId,
      @RequestBody(required = false) String body) {
    var ctx = buildContext(req);
    var resp = restHandler.cancelTask(ctx, "", body != null ? body : "{}", taskId);
    return toResponse(resp);
  }

  @PostMapping(
      value = "${a2at.server.path-prefix}/tasks/{id}:subscribe",
      produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter subscribeToTask(
      HttpServletRequest req, @PathVariable("id") String taskId) {
    try {
      var ctx = buildContext(req);
      requireStreamingSupport();
      requestHandler.authorizeTaskAccess(taskId, ctx, TaskOperation.SUBSCRIBE_TO_TASK);
      Flow.Publisher<StreamingEventKind> publisher =
          requestHandler.onSubscribeToTask(TaskIdParams.builder().id(taskId).tenant("").build(), ctx);
      return subscribeToEmitter(publisher, ctx);
    } catch (A2AError e) {
      log.warn("[SSE] Task subscription rejected before stream creation: {}", e.getMessage());
      throw e;
    } catch (Exception e) {
      log.error(
          "[SSE] Subscribe setup failed before stream creation: {}",
          e.getClass().getSimpleName(),
          e);
      throw new InternalError("Task subscription setup failed");
    }
  }

  private void requireStreamingSupport() throws UnsupportedOperationError {
    if (!agentCard.capabilities().streaming()) {
      throw new UnsupportedOperationError(null, "Streaming is not supported by the agent", null);
    }
  }

  /**
   * Waits until every SSE response has left the Servlet asynchronous context. This is mainly useful
   * for deterministic application shutdown; receiving the terminal A2A event can precede the
   * container's response-completion callback by a few milliseconds.
   *
   * @return {@code true} when all streams drained before the timeout
   */
  public boolean awaitStreamsDrained(Duration timeout) {
    if (timeout == null || timeout.isNegative()) {
      throw new IllegalArgumentException("timeout must not be null or negative");
    }
    long deadline = System.nanoTime() + timeout.toNanos();
    synchronized (streamCompletionMonitor) {
      while (activeStreams.get() > 0) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
          return false;
        }
        try {
          TimeUnit.NANOSECONDS.timedWait(streamCompletionMonitor, remaining);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return false;
        }
      }
    }
    return true;
  }

  /** Number of SSE streams currently open (message streaming plus task subscriptions). */
  public int activeStreamCount() {
    return activeStreams.get();
  }

  private <T extends ResponseBodyEmitter> T track(T emitter, Runnable cleanup) {
    activeStreams.incrementAndGet();
    AtomicBoolean completed = new AtomicBoolean();
    Runnable finish =
        () -> {
          if (completed.compareAndSet(false, true)) {
            cleanup.run();
            activeStreams.decrementAndGet();
            synchronized (streamCompletionMonitor) {
              streamCompletionMonitor.notifyAll();
            }
          }
        };
    emitter.onCompletion(finish);
    emitter.onTimeout(finish);
    emitter.onError(ignored -> finish.run());
    return emitter;
  }

  private static ResponseEntity<String> toResponse(RestHandler.HTTPRestResponse response) {
    return ResponseEntity.status(response.getStatusCode())
        .contentType(A2A_JSON)
        .body(response.getBody());
  }

  private ServerCallContext buildContext(HttpServletRequest req) {
    Map<String, Object> state = new LinkedHashMap<>();
    Map<String, String> headers = new LinkedHashMap<>();
    if (req.getHeaderNames() != null) {
      java.util.Collections.list(req.getHeaderNames())
          .forEach(h -> headers.put(h.toLowerCase(Locale.ROOT), req.getHeader(h)));
    }
    state.put("headers", headers);
    String ext = req.getHeader("A2A-Extensions");
    Set<String> exts;
    if (ext == null || ext.isBlank()) {
      exts = Set.of();
    } else {
      java.util.LinkedHashSet<String> parsed = new java.util.LinkedHashSet<>();
      for (String value : ext.split(",")) {
        if (!value.isBlank()) parsed.add(value.strip());
      }
      exts = java.util.Collections.unmodifiableSet(parsed);
    }
    // A2A-Version carries the requested protocol version; a missing or empty header follows the
    // spec default (0.3). This adapter additionally accepts A2A-Protocol-Version as a local
    // compatibility alias.
    String ver = req.getHeader("A2A-Version");
    if (ver == null || ver.isBlank()) {
      ver = req.getHeader("A2A-Protocol-Version");
    }
    return new ServerCallContext(null, state, exts, ver);
  }
}
