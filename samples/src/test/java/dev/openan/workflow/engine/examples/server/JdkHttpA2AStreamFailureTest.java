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
package dev.openan.workflow.engine.examples.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.a2aproject.sdk.server.ServerCallContext;
import org.a2aproject.sdk.spec.StreamingEventKind;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class JdkHttpA2AStreamFailureTest {

  @ParameterizedTest
  @ValueSource(strings = {
      "before-subscribe", "after-subscribe", "during-request", "on-error", "invalid-event"})
  void committedStreamFailureClosesWithoutEscapingToTheHttpErrorHandler(String phase)
      throws Exception {
    AtomicInteger subscriptionCancellations = new AtomicInteger();
    AtomicInteger eventConsumerCancellations = new AtomicInteger();
    AtomicReference<Throwable> escaped = new AtomicReference<>();
    CountDownLatch finished = new CountDownLatch(1);
    ServerCallContext context = new ServerCallContext(null, Map.of(), Set.of(), "1.0");
    context.setEventConsumerCancelCallback(eventConsumerCancellations::incrementAndGet);
    Flow.Publisher<StreamingEventKind> publisher = receiver -> {
      if (phase.equals("before-subscribe")) {
        throw new IllegalStateException("Subscribe failed before registration");
      }
      receiver.onSubscribe(new Flow.Subscription() {
        @Override
        public void request(long count) {
          if (phase.equals("during-request")) {
            throw new IllegalStateException("Request failed");
          }
        }

        @Override
        public void cancel() {
          subscriptionCancellations.incrementAndGet();
        }
      });
      switch (phase) {
        case "on-error" -> receiver.onError(new IllegalStateException("Publisher failed"));
        case "invalid-event" -> receiver.onNext(null);
        default -> throw new IllegalStateException("Subscribe failed after registration");
      }
    };
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/message:stream", exchange -> {
      try {
        JdkHttpA2AServer.writeStream(exchange, publisher, context);
      } catch (Throwable error) {
        // The production outer handler would attempt a second HTTP error response.
        escaped.set(error);
      } finally {
        exchange.close();
        finished.countDown();
      }
    });
    server.start();
    try {
      HttpResponse<String> response = HttpClient.newHttpClient().send(
          HttpRequest.newBuilder(URI.create(
                  "http://127.0.0.1:" + server.getAddress().getPort() + "/message:stream"))
              .timeout(Duration.ofSeconds(5))
              .POST(HttpRequest.BodyPublishers.noBody())
              .build(),
          HttpResponse.BodyHandlers.ofString());

      assertTrue(finished.await(5, TimeUnit.SECONDS));
      assertEquals(200, response.statusCode());
      assertEquals("text/event-stream", response.headers().firstValue("Content-Type").orElseThrow());
      assertEquals("", response.body());
      assertNull(escaped.get());
      assertEquals(1, eventConsumerCancellations.get());
      assertEquals(phase.equals("before-subscribe") ? 0 : 1, subscriptionCancellations.get());
    } finally {
      server.stop(0);
    }
  }
}
