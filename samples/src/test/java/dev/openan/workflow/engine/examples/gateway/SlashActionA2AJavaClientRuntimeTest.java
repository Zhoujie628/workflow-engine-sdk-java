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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.a2aproject.sdk.client.http.A2AHttpClient;
import org.a2aproject.sdk.client.http.A2AHttpResponse;
import org.a2aproject.sdk.client.http.ServerSentEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SlashActionA2AJavaClientRuntimeTest {

  @ParameterizedTest
  @CsvSource({
    "https://msb.example/a2a/json/message:send, https://msb.example/a2a/json/message/send",
    "https://msb.example/a2a/json/message:stream, https://msb.example/a2a/json/message/stream",
    "https://msb.example/a2a/json/tasks/t-1:cancel, https://msb.example/a2a/json/tasks/t-1/cancel",
    "https://msb.example/a2a/json/tasks/t-1:subscribe, https://msb.example/a2a/json/tasks/t-1/subscribe",
    "https://msb.example/a2a/json/tasks/t-1, https://msb.example/a2a/json/tasks/t-1",
    "https://msb.example/a2a/json/not-a-task:cancel, https://msb.example/a2a/json/not-a-task:cancel",
    "https://msb.example/a2a/json/message:stream?tenant=x#part, https://msb.example/a2a/json/message/stream?tenant=x#part"
  })
  void rewritesOnlyA2aActionSeparators(String input, String expected) {
    assertEquals(expected, SlashActionA2AJavaClientRuntime.rewriteActionUrl(input));
  }

  @Test
  void delegatesHeadersBodyAndSseLifecycleWhileRewritingOnlyTheUrl() throws Exception {
    var delegate = new CapturingHttpClient();
    A2AHttpClient client = SlashActionA2AJavaClientRuntime.slashActionClient(delegate);
    var messages = new AtomicInteger();
    var errors = new AtomicInteger();
    var completions = new AtomicInteger();

    CompletableFuture<Void> lifecycle =
        client.createPost()
            .url("https://msb.example/a2a/json/message:stream?tenant=x")
            .addHeader("Authorization", "Bearer token")
            .addHeaders(Map.of("A2A-Version", "1.0"))
            .body("{\"message\":{}}")
            .postAsyncSSE(
                ignored -> messages.incrementAndGet(),
                ignored -> errors.incrementAndGet(),
                completions::incrementAndGet);

    assertSame(delegate.lifecycle, lifecycle);
    assertEquals("https://msb.example/a2a/json/message/stream?tenant=x", delegate.url);
    assertEquals(
        Map.of("Authorization", "Bearer token", "A2A-Version", "1.0"), delegate.headers);
    assertEquals("{\"message\":{}}", delegate.body);
    assertEquals(1, messages.get());
    assertEquals(0, errors.get());
    assertEquals(1, completions.get());
  }

  private static final class CapturingHttpClient implements A2AHttpClient {
    private final Map<String, String> headers = new LinkedHashMap<>();
    private final CompletableFuture<Void> lifecycle = CompletableFuture.completedFuture(null);
    private String url;
    private String body;

    @Override
    public GetBuilder createGet() {
      throw new UnsupportedOperationException();
    }

    @Override
    public PostBuilder createPost() {
      return new PostBuilder() {
        @Override
        public PostBuilder url(String value) {
          url = value;
          return this;
        }

        @Override
        public PostBuilder addHeaders(Map<String, String> values) {
          headers.putAll(values);
          return this;
        }

        @Override
        public PostBuilder addHeader(String name, String value) {
          headers.put(name, value);
          return this;
        }

        @Override
        public PostBuilder body(String value) {
          body = value;
          return this;
        }

        @Override
        public A2AHttpResponse post() {
          throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<Void> postAsyncSSE(
            Consumer<ServerSentEvent> messageConsumer,
            Consumer<Throwable> errorConsumer,
            Runnable completeRunnable)
            throws IOException, InterruptedException {
          messageConsumer.accept(new ServerSentEvent("heartbeat"));
          completeRunnable.run();
          return lifecycle;
        }
      };
    }

    @Override
    public DeleteBuilder createDelete() {
      throw new UnsupportedOperationException();
    }
  }
}
