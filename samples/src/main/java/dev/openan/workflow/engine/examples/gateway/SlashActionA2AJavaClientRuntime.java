/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 *    Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package dev.openan.workflow.engine.examples.gateway;

import dev.openan.workflow.engine.client.DefaultA2AJavaClientRuntime;
import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.a2aproject.sdk.client.http.A2AHttpClient;
import org.a2aproject.sdk.client.http.A2AHttpResponse;
import org.a2aproject.sdk.client.http.ServerSentEvent;

/**
 * Sample caller runtime for gateways that reject the standard colon-style A2A action paths.
 *
 * <p>Only the request URL is adapted. Message serialization, SSE decoding, A2A error mapping,
 * authentication headers, TLS, and runtime lifecycle continue to use the engine and A2A Java SDK
 * implementations. This keeps the gateway workaround outside the workflow and protocol-content
 * layers.
 */
public final class SlashActionA2AJavaClientRuntime extends DefaultA2AJavaClientRuntime {

  public SlashActionA2AJavaClientRuntime(
      boolean sslVerify, String caCertsPath, long sendTimeoutSeconds, String preferredProtocol) {
    super(sslVerify, caCertsPath, sendTimeoutSeconds, preferredProtocol);
  }

  @Override
  protected A2AHttpClient customizeHttpClient(A2AHttpClient httpClient) {
    return slashActionClient(httpClient);
  }

  static A2AHttpClient slashActionClient(A2AHttpClient httpClient) {
    return new SlashActionHttpClient(httpClient);
  }

  static String rewriteActionUrl(String url) {
    int query = url.indexOf('?');
    int fragment = url.indexOf('#');
    int suffix =
        query < 0 ? fragment : fragment < 0 ? query : Math.min(query, fragment);
    String actionPath = suffix < 0 ? url : url.substring(0, suffix);
    String trailing = suffix < 0 ? "" : url.substring(suffix);

    if (actionPath.endsWith("/message:send")) {
      actionPath = actionPath.substring(0, actionPath.length() - "/message:send".length())
          + "/message/send";
    } else if (actionPath.endsWith("/message:stream")) {
      actionPath = actionPath.substring(0, actionPath.length() - "/message:stream".length())
          + "/message/stream";
    } else if (actionPath.contains("/tasks/") && actionPath.endsWith(":cancel")) {
      actionPath = actionPath.substring(0, actionPath.length() - ":cancel".length()) + "/cancel";
    } else if (actionPath.contains("/tasks/") && actionPath.endsWith(":subscribe")) {
      actionPath =
          actionPath.substring(0, actionPath.length() - ":subscribe".length()) + "/subscribe";
    }
    return actionPath + trailing;
  }

  private static final class SlashActionHttpClient implements A2AHttpClient {
    private final A2AHttpClient delegate;

    private SlashActionHttpClient(A2AHttpClient delegate) {
      this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public GetBuilder createGet() {
      return new RewritingGetBuilder(delegate.createGet());
    }

    @Override
    public PostBuilder createPost() {
      return new RewritingPostBuilder(delegate.createPost());
    }

    @Override
    public DeleteBuilder createDelete() {
      return new RewritingDeleteBuilder(delegate.createDelete());
    }
  }

  private static final class RewritingGetBuilder implements A2AHttpClient.GetBuilder {
    private final A2AHttpClient.GetBuilder delegate;

    private RewritingGetBuilder(A2AHttpClient.GetBuilder delegate) {
      this.delegate = delegate;
    }

    @Override
    public A2AHttpClient.GetBuilder url(String url) {
      delegate.url(rewriteActionUrl(url));
      return this;
    }

    @Override
    public A2AHttpClient.GetBuilder addHeaders(Map<String, String> headers) {
      delegate.addHeaders(headers);
      return this;
    }

    @Override
    public A2AHttpClient.GetBuilder addHeader(String name, String value) {
      delegate.addHeader(name, value);
      return this;
    }

    @Override
    public A2AHttpResponse get() throws IOException, InterruptedException {
      return delegate.get();
    }

    @Override
    public CompletableFuture<Void> getAsyncSSE(
        Consumer<ServerSentEvent> messageConsumer,
        Consumer<Throwable> errorConsumer,
        Runnable completeRunnable)
        throws IOException, InterruptedException {
      return delegate.getAsyncSSE(messageConsumer, errorConsumer, completeRunnable);
    }
  }

  private static final class RewritingPostBuilder implements A2AHttpClient.PostBuilder {
    private final A2AHttpClient.PostBuilder delegate;

    private RewritingPostBuilder(A2AHttpClient.PostBuilder delegate) {
      this.delegate = delegate;
    }

    @Override
    public A2AHttpClient.PostBuilder url(String url) {
      delegate.url(rewriteActionUrl(url));
      return this;
    }

    @Override
    public A2AHttpClient.PostBuilder addHeaders(Map<String, String> headers) {
      delegate.addHeaders(headers);
      return this;
    }

    @Override
    public A2AHttpClient.PostBuilder addHeader(String name, String value) {
      delegate.addHeader(name, value);
      return this;
    }

    @Override
    public A2AHttpClient.PostBuilder body(String body) {
      delegate.body(body);
      return this;
    }

    @Override
    public A2AHttpResponse post() throws IOException, InterruptedException {
      return delegate.post();
    }

    @Override
    public CompletableFuture<Void> postAsyncSSE(
        Consumer<ServerSentEvent> messageConsumer,
        Consumer<Throwable> errorConsumer,
        Runnable completeRunnable)
        throws IOException, InterruptedException {
      return delegate.postAsyncSSE(messageConsumer, errorConsumer, completeRunnable);
    }
  }

  private static final class RewritingDeleteBuilder implements A2AHttpClient.DeleteBuilder {
    private final A2AHttpClient.DeleteBuilder delegate;

    private RewritingDeleteBuilder(A2AHttpClient.DeleteBuilder delegate) {
      this.delegate = delegate;
    }

    @Override
    public A2AHttpClient.DeleteBuilder url(String url) {
      delegate.url(rewriteActionUrl(url));
      return this;
    }

    @Override
    public A2AHttpClient.DeleteBuilder addHeaders(Map<String, String> headers) {
      delegate.addHeaders(headers);
      return this;
    }

    @Override
    public A2AHttpClient.DeleteBuilder addHeader(String name, String value) {
      delegate.addHeader(name, value);
      return this;
    }

    @Override
    public A2AHttpResponse delete() throws IOException, InterruptedException {
      return delegate.delete();
    }
  }
}
