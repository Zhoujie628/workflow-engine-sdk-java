/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.client;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.a2aproject.sdk.client.http.A2AHttpClient;
import org.a2aproject.sdk.client.http.A2AHttpResponse;
import org.a2aproject.sdk.client.http.ServerSentEvent;
import org.junit.jupiter.api.Test;

class ProblemDetectingHttpClientTest {
  @Test
  void synchronousProblemCancelsUpstreamAndSurvivesAThrowingErrorObserver() throws Exception {
    var upstream = new CompletableFuture<Void>();
    var failures = new AtomicInteger();
    var events = new AtomicInteger();
    var completions = new AtomicInteger();
    var client = client((messages, errors, complete) -> {
      messages.accept(new ServerSentEvent("{\"status\":429,\"detail\":\"capacity reached\"}"));
      messages.accept(new ServerSentEvent("{\"message\":{}}"));
      complete.run();
      errors.accept(new IllegalStateException("late close"));
      return upstream;
    });
    var result = client.createPost().postAsyncSSE(event -> events.incrementAndGet(),
        error -> {
          failures.incrementAndGet();
          throw new IllegalStateException("observer failed");
        }, completions::incrementAndGet);
    var problem = RemoteProblemException.findIn(assertThrows(CompletionException.class, result::join));
    assertNotNull(problem);
    assertEquals(429, problem.getStatus());
    assertEquals(1, problem.getSuppressed().length);
    assertTrue(upstream.isCancelled());
    assertEquals(1, failures.get());
    assertEquals(0, events.get());
    assertEquals(0, completions.get());
  }

  @Test
  void cancellationPropagatesAndLateEventsCannotCompleteTheCall() throws Exception {
    var upstream = new CompletableFuture<Void>();
    var messages = new AtomicReference<Consumer<ServerSentEvent>>();
    var complete = new AtomicReference<Runnable>();
    var callbacks = new AtomicInteger();
    var result = client((m, e, c) -> {
      messages.set(m);
      complete.set(c);
      return upstream;
    }).createPost().postAsyncSSE(event -> callbacks.incrementAndGet(),
        error -> callbacks.incrementAndGet(), callbacks::incrementAndGet);
    assertTrue(result.cancel(true));
    assertTrue(upstream.isCancelled());
    messages.get().accept(new ServerSentEvent("{}"));
    complete.get().run();
    assertEquals(0, callbacks.get());
  }

  @Test
  void successfulDataIsNotRewrittenOrMistakenForANestedProblem() throws Exception {
    String data = "{\"task\":{\"status\":{\"state\":\"TASK_STATE_COMPLETED\"}},\"metadata\":{\"status\":400}}";
    var seen = new AtomicReference<String>();
    var completions = new AtomicInteger();
    var result = client((messages, errors, complete) -> {
      messages.accept(new ServerSentEvent(data));
      complete.run();
      return CompletableFuture.completedFuture(null);
    }).createPost().postAsyncSSE(event -> seen.set(event.data()),
        error -> fail("unexpected error", error), completions::incrementAndGet);
    result.join();
    assertEquals(data, seen.get());
    assertEquals(1, completions.get());
  }

  private static A2AHttpClient client(Start start) {
    return new ProblemDetectingHttpClient(new A2AHttpClient() {
      @Override public GetBuilder createGet() { throw new UnsupportedOperationException(); }
      @Override public DeleteBuilder createDelete() { throw new UnsupportedOperationException(); }
      @Override public PostBuilder createPost() {
        return new PostBuilder() {
          @Override public PostBuilder url(String url) { return this; }
          @Override public PostBuilder body(String body) { return this; }
          @Override public PostBuilder addHeader(String key, String value) { return this; }
          @Override public PostBuilder addHeaders(Map<String, String> values) { return this; }
          @Override public A2AHttpResponse post() { throw new UnsupportedOperationException(); }
          @Override public CompletableFuture<Void> postAsyncSSE(
              Consumer<ServerSentEvent> messages, Consumer<Throwable> errors, Runnable complete) {
            return start.run(messages, errors, complete);
          }
        };
      }
    });
  }

  @FunctionalInterface
  private interface Start {
    CompletableFuture<Void> run(Consumer<ServerSentEvent> messages, Consumer<Throwable> errors, Runnable complete);
  }
}
