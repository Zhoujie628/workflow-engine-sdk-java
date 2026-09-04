/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 *    Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package dev.openan.workflow.engine.client;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.a2aproject.sdk.client.http.A2AHttpClient;
import org.a2aproject.sdk.client.http.A2AHttpResponse;
import org.a2aproject.sdk.client.http.ServerSentEvent;

/** Detects standard A2A error envelopes before the SDK attempts to parse them as task events. */
final class A2AErrorDetectingHttpClient implements A2AHttpClient {
  private final A2AHttpClient delegate;

  A2AErrorDetectingHttpClient(A2AHttpClient delegate) {
    this.delegate = delegate;
  }

  @Override public GetBuilder createGet() { return new Get(delegate.createGet()); }
  @Override public PostBuilder createPost() { return new Post(delegate.createPost()); }
  @Override public DeleteBuilder createDelete() { return new Delete(delegate.createDelete()); }

  private static A2AHttpResponse check(A2AHttpResponse response) {
    var error = RemoteA2AErrorException.fromResponse(
        response.status(), response.body(), response.headers().toMap());
    if (error != null) throw error;
    return response;
  }

  private abstract static class Wrapped<T extends Builder<T>> implements Builder<T> {
    final T target;
    Wrapped(T target) { this.target = target; }
    abstract T self();
    @Override public T url(String url) { target.url(url); return self(); }
    @Override public T addHeader(String name, String value) { target.addHeader(name, value); return self(); }
    @Override public T addHeaders(Map<String, String> headers) { target.addHeaders(headers); return self(); }
  }

  private static final class Get extends Wrapped<GetBuilder> implements GetBuilder {
    Get(GetBuilder target) { super(target); }
    @Override GetBuilder self() { return this; }
    @Override public A2AHttpResponse get() throws IOException, InterruptedException { return check(target.get()); }
    @Override public CompletableFuture<Void> getAsyncSSE(
        Consumer<ServerSentEvent> messages, Consumer<Throwable> errors, Runnable complete)
        throws IOException, InterruptedException {
      return stream(target::getAsyncSSE, messages, errors, complete);
    }
  }

  private static final class Post extends Wrapped<PostBuilder> implements PostBuilder {
    Post(PostBuilder target) { super(target); }
    @Override PostBuilder self() { return this; }
    @Override public PostBuilder body(String body) { target.body(body); return this; }
    @Override public A2AHttpResponse post() throws IOException, InterruptedException { return check(target.post()); }
    @Override public CompletableFuture<Void> postAsyncSSE(
        Consumer<ServerSentEvent> messages, Consumer<Throwable> errors, Runnable complete)
        throws IOException, InterruptedException {
      return stream(target::postAsyncSSE, messages, errors, complete);
    }
  }

  private static final class Delete extends Wrapped<DeleteBuilder> implements DeleteBuilder {
    Delete(DeleteBuilder target) { super(target); }
    @Override DeleteBuilder self() { return this; }
    @Override public A2AHttpResponse delete() throws IOException, InterruptedException { return check(target.delete()); }
  }

  private static CompletableFuture<Void> stream(
      StreamStarter starter, Consumer<ServerSentEvent> messages,
      Consumer<Throwable> errors, Runnable complete) throws IOException, InterruptedException {
    var result = new CompletableFuture<Void>();
    var upstream = new AtomicReference<CompletableFuture<Void>>();
    var ended = new AtomicBoolean();
    var eventReceived = new AtomicBoolean();
    Consumer<Throwable> fail = error -> {
      if (!ended.compareAndSet(false, true)) return;
      try { errors.accept(error); }
      catch (RuntimeException observerError) {
        if (observerError != error) error.addSuppressed(observerError);
      }
      finally { result.completeExceptionally(error); }
    };
    result.whenComplete((ignored, error) -> {
      if (error != null) {
        ended.set(true);
        var active = upstream.get();
        if (active != null) active.cancel(true);
      }
    });
    var active = starter.start(event -> {
      if (ended.get()) return;
      var remoteError = RemoteA2AErrorException.fromPayload(event.data());
      if (remoteError != null) fail.accept(remoteError);
      else {
        eventReceived.set(true);
        try { messages.accept(event); }
        catch (RuntimeException callbackError) { fail.accept(callbackError); }
      }
    }, fail, () -> {
      if (!eventReceived.get()) {
        fail.accept(new IOException("A2A streaming response closed without an event"));
        return;
      }
      if (ended.compareAndSet(false, true)) {
        try { complete.run(); result.complete(null); }
        catch (RuntimeException error) { result.completeExceptionally(error); }
      }
    });
    upstream.set(active);
    if (result.isCompletedExceptionally()) active.cancel(true);
    active.whenComplete((ignored, error) -> {
      if (error != null) fail.accept(error);
    });
    return result;
  }

  @FunctionalInterface
  private interface StreamStarter {
    CompletableFuture<Void> start(Consumer<ServerSentEvent> messages, Consumer<Throwable> errors,
        Runnable complete) throws IOException, InterruptedException;
  }
}
