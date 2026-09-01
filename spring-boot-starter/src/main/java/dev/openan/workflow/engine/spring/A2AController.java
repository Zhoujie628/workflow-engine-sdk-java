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
import org.a2aproject.sdk.grpc.SendMessageRequest;
import org.a2aproject.sdk.grpc.StreamResponse;
import org.a2aproject.sdk.grpc.utils.ProtoUtils;
import org.a2aproject.sdk.server.ServerCallContext;
import org.a2aproject.sdk.server.requesthandlers.RequestHandler;
import org.a2aproject.sdk.spec.MessageSendParams;
import org.a2aproject.sdk.spec.StreamingEventKind;
import org.a2aproject.sdk.transport.rest.handler.RestHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Spring MVC controller exposing A2A message endpoints.
 *
 * <p>Maps two endpoints (no AgentCard retrieval -- cards come from the registry center):
 *
 * <ul>
 *   <li>{@code POST /a2a/json/message:send} - synchronous message send
 *   <li>{@code POST /a2a/json/message:stream} - SSE streaming message send
 * </ul>
 *
 * <p>The path prefix is configurable via {@code a2at.server.path-prefix}. The controller delegates
 * to {@link RestHandler} (non-streaming) and {@link RequestHandler} (streaming). The {@link
 * ServerCallContext} is built from {@link HttpServletRequest} headers, preserving the
 * A2A-Extensions header.
 */
@RestController
public class A2AController {

  private static final Logger log = LoggerFactory.getLogger(A2AController.class);

  private final RestHandler restHandler;
  private final RequestHandler requestHandler;
  private final AtomicInteger activeStreams = new AtomicInteger();
  private final Object streamCompletionMonitor = new Object();

  public A2AController(RestHandler restHandler, RequestHandler requestHandler) {
    this.restHandler = restHandler;
    this.requestHandler = requestHandler;
  }

  @PostMapping("${a2at.server.path-prefix}/message:send")
  public ResponseEntity<String> sendMessage(HttpServletRequest req, @RequestBody String body) {
    var ctx = buildContext(req);
    var resp = restHandler.sendMessage(ctx, "", body);
    return ResponseEntity.status(resp.getStatusCode())
        .contentType(
            MediaType.parseMediaType(
                resp.getContentType() != null
                    ? resp.getContentType()
                    : MediaType.APPLICATION_JSON_VALUE))
        .body(resp.getBody());
  }

  @PostMapping(
      value = "${a2at.server.path-prefix}/message:stream",
      produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter streamMessage(HttpServletRequest req, @RequestBody String body) {
    SseEmitter emitter = track(new SseEmitter(0L));
    try {
      SendMessageRequest.Builder builder = SendMessageRequest.newBuilder();
      JsonFormat.parser().merge(body, builder);
      MessageSendParams params = ProtoUtils.FromProto.messageSendParams(builder.build());

      var ctx = buildContext(req);
      Flow.Publisher<StreamingEventKind> publisher =
          requestHandler.onMessageSendStream(params, ctx);

      final AtomicLong seq = new AtomicLong(0);
      publisher.subscribe(
          new Flow.Subscriber<>() {
            private Flow.Subscription sub;

            @Override
            public void onSubscribe(Flow.Subscription s) {
              sub = s;
              s.request(1);
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
                sub.cancel();
                emitter.completeWithError(e);
                return;
              }
              sub.request(1);
            }

            @Override
            public void onError(Throwable t) {
              log.error("[SSE] Stream error: {}", t.getMessage());
              emitter.completeWithError(t);
            }

            @Override
            public void onComplete() {
              emitter.complete();
            }
          });
    } catch (Exception e) {
      log.error("[SSE] Setup failed: {}", e.getMessage(), e);
      emitter.completeWithError(e);
    }
    return emitter;
  }

  @GetMapping("${a2at.server.path-prefix}/tasks/{id}")
  public ResponseEntity<String> getTask(HttpServletRequest req, @PathVariable("id") String taskId) {
    var ctx = buildContext(req);
    var resp = restHandler.getTask(ctx, "", taskId, null);
    return ResponseEntity.status(resp.getStatusCode())
        .contentType(
            MediaType.parseMediaType(
                resp.getContentType() != null
                    ? resp.getContentType()
                    : MediaType.APPLICATION_JSON_VALUE))
        .body(resp.getBody());
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
    return ResponseEntity.status(resp.getStatusCode())
        .contentType(
            MediaType.parseMediaType(
                resp.getContentType() != null
                    ? resp.getContentType()
                    : MediaType.APPLICATION_JSON_VALUE))
        .body(resp.getBody());
  }

  @PostMapping("${a2at.server.path-prefix}/tasks/{id}:cancel")
  public ResponseEntity<String> cancelTask(
      HttpServletRequest req,
      @PathVariable("id") String taskId,
      @RequestBody(required = false) String body) {
    var ctx = buildContext(req);
    var resp = restHandler.cancelTask(ctx, "", body != null ? body : "{}", taskId);
    return ResponseEntity.status(resp.getStatusCode())
        .contentType(
            MediaType.parseMediaType(
                resp.getContentType() != null
                    ? resp.getContentType()
                    : MediaType.APPLICATION_JSON_VALUE))
        .body(resp.getBody());
  }

  @PostMapping(
      value = "${a2at.server.path-prefix}/tasks/{id}:subscribe",
      produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter subscribeToTask(HttpServletRequest req, @PathVariable("id") String taskId) {
    SseEmitter emitter = track(new SseEmitter(0L));
    try {
      var ctx = buildContext(req);
      var resp = restHandler.subscribeToTask(ctx, "", taskId);
      if (resp instanceof RestHandler.HTTPRestStreamingResponse streaming) {
        final AtomicLong seq = new AtomicLong(0);
        streaming
            .getPublisher()
            .subscribe(
                new Flow.Subscriber<String>() {
                  private Flow.Subscription sub;

                  @Override
                  public void onSubscribe(Flow.Subscription s) {
                    sub = s;
                    s.request(1);
                  }

                  @Override
                  public void onNext(String item) {
                    try {
                      emitter.send(
                          SseEmitter.event()
                              .id(Long.toString(seq.incrementAndGet()))
                              .data(item, MediaType.APPLICATION_JSON));
                    } catch (Exception e) {
                      log.error("[SSE] Subscribe write failed: {}", e.getMessage());
                      sub.cancel();
                      emitter.completeWithError(e);
                      return;
                    }
                    sub.request(1);
                  }

                  @Override
                  public void onError(Throwable t) {
                    log.error("[SSE] Subscribe stream error: {}", t.getMessage());
                    emitter.completeWithError(t);
                  }

                  @Override
                  public void onComplete() {
                    emitter.complete();
                  }
                });
      } else {
        emitter.send(SseEmitter.event().id("1").data(resp.getBody(), MediaType.APPLICATION_JSON));
        emitter.complete();
      }
    } catch (Exception e) {
      log.error("[SSE] Subscribe setup failed: {}", e.getMessage(), e);
      emitter.completeWithError(e);
    }
    return emitter;
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

  public int activeStreamCount() {
    return activeStreams.get();
  }

  private <T extends ResponseBodyEmitter> T track(T emitter) {
    activeStreams.incrementAndGet();
    AtomicBoolean completed = new AtomicBoolean();
    Runnable finish =
        () -> {
          if (completed.compareAndSet(false, true)) {
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
    // OMC spec uses A2A-Version; SDK also supports A2A-Protocol-Version alias
    String ver = req.getHeader("A2A-Version");
    if (ver == null || ver.isBlank()) {
      ver = req.getHeader("A2A-Protocol-Version");
    }
    return new ServerCallContext(null, state, exts, ver);
  }
}
