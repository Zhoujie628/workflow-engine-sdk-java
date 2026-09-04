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

import dev.openan.workflow.engine.util.SensitiveDataRedactor;
import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;

/** Observes the JDK boundary after the A2A SDK has serialized and added its application headers. */
final class ObservedHttpClient extends HttpClient {
  private final HttpClient delegate;
  private final Consumer<WireLog.Entry> observer;

  ObservedHttpClient(HttpClient delegate) {
    this(delegate, null);
  }

  ObservedHttpClient(HttpClient delegate, Consumer<WireLog.Entry> observer) {
    this.delegate = Objects.requireNonNull(delegate);
    this.observer = observer;
  }

  @Override
  public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
      throws IOException, InterruptedException {
    Exchange exchange = new Exchange(request);
    try {
      return delegate.send(exchange.observedRequest(), exchange.handler(handler));
    } catch (IOException | InterruptedException | RuntimeException error) {
      exchange.failure(error);
      throw error;
    }
  }

  @Override
  public <T> CompletableFuture<HttpResponse<T>> sendAsync(
      HttpRequest request, HttpResponse.BodyHandler<T> handler) {
    Exchange exchange = new Exchange(request);
    try {
      CompletableFuture<HttpResponse<T>> future =
          delegate.sendAsync(exchange.observedRequest(), exchange.handler(handler));
      future.whenComplete(
          (result, error) -> {
            if (error != null) exchange.failure(error);
          });
      return future;
    } catch (RuntimeException error) {
      exchange.failure(error);
      throw error;
    }
  }

  @Override
  public <T> CompletableFuture<HttpResponse<T>> sendAsync(
      HttpRequest request,
      HttpResponse.BodyHandler<T> handler,
      HttpResponse.PushPromiseHandler<T> pushHandler) {
    Exchange exchange = new Exchange(request);
    try {
      CompletableFuture<HttpResponse<T>> future =
          delegate.sendAsync(exchange.observedRequest(), exchange.handler(handler), pushHandler);
      future.whenComplete(
          (result, error) -> {
            if (error != null) exchange.failure(error);
          });
      return future;
    } catch (RuntimeException error) {
      exchange.failure(error);
      throw error;
    }
  }

  @Override
  public Optional<CookieHandler> cookieHandler() {
    return delegate.cookieHandler();
  }

  @Override
  public Optional<Duration> connectTimeout() {
    return delegate.connectTimeout();
  }

  @Override
  public Redirect followRedirects() {
    return delegate.followRedirects();
  }

  @Override
  public Optional<ProxySelector> proxy() {
    return delegate.proxy();
  }

  @Override
  public SSLContext sslContext() {
    return delegate.sslContext();
  }

  @Override
  public SSLParameters sslParameters() {
    return delegate.sslParameters();
  }

  @Override
  public Optional<Authenticator> authenticator() {
    return delegate.authenticator();
  }

  @Override
  public Version version() {
    return delegate.version();
  }

  @Override
  public Optional<Executor> executor() {
    return delegate.executor();
  }

  private final class Exchange {
    final String id = UUID.randomUUID().toString();
    final HttpRequest request;
    final Map<String, String> context = WireLog.context();
    final java.util.concurrent.atomic.AtomicBoolean failureLogged =
        new java.util.concurrent.atomic.AtomicBoolean();
    final java.util.concurrent.atomic.AtomicBoolean cancelledBySubscriber =
        new java.util.concurrent.atomic.AtomicBoolean();

    Exchange(HttpRequest request) {
      this.request = request;
      emit("REQUEST_HEADERS", null, request.headers().map(), "headers", "", "");
    }

    void emit(
        String direction,
        Integer status,
        Map<String, List<String>> headers,
        String representation,
        String body,
        String detail) {
      WireLog.emit(
          observer,
          "DIRECT_HTTP",
          direction,
          id,
          request.uri().toString(),
          request.method(),
          status,
          headers,
          representation,
          body,
          "application-boundary; automatic network headers/TLS/framing=unobserved; " + detail,
          context);
    }

    void failure(Throwable error) {
      if (cancelledBySubscriber.get()) return;
      // Body subscriber and sendAsync completion can report the same exchange failure.
      if (!failureLogged.compareAndSet(false, true)) return;
      String message = SensitiveDataRedactor.redact(Objects.toString(error.getMessage(), ""))
          .replace("\r", "\\r")
          .replace("\n", "\\n");
      emit(
          "FAILURE",
          null,
          Map.of(),
          "transport-error",
          "",
          "errorType=" + error.getClass().getSimpleName()
              + (message.isBlank() ? "" : "; errorMessage=" + message));
    }

    HttpRequest observedRequest() {
      return new HttpRequest() {
        @Override
        public Optional<BodyPublisher> bodyPublisher() {
          return request
              .bodyPublisher()
              .map(
                  original ->
                      new BodyPublisher() {
                        @Override
                        public long contentLength() {
                          return original.contentLength();
                        }

                        @Override
                        public void subscribe(Flow.Subscriber<? super ByteBuffer> downstream) {
                          WireLog.Body body =
                              new WireLog.Body(
                                  false,
                                  true,
                                  value ->
                                      emit(
                                          "REQUEST_BODY",
                                          null,
                                          Map.of(),
                                          "serialized-utf8",
                                          value[0],
                                          value[1]));
                          original.subscribe(
                              new Flow.Subscriber<ByteBuffer>() {
                                @Override
                                public void onSubscribe(Flow.Subscription subscription) {
                                  downstream.onSubscribe(subscription);
                                }

                                @Override
                                public void onNext(ByteBuffer item) {
                                  body.accept(item);
                                  downstream.onNext(item);
                                }

                                @Override
                                public void onError(Throwable error) {
                                  body.end(true);
                                  downstream.onError(error);
                                }

                                @Override
                                public void onComplete() {
                                  body.end(false);
                                  downstream.onComplete();
                                }
                              });
                        }
                      });
        }

        @Override
        public String method() {
          return request.method();
        }

        @Override
        public Optional<Duration> timeout() {
          return request.timeout();
        }

        @Override
        public boolean expectContinue() {
          return request.expectContinue();
        }

        @Override
        public URI uri() {
          return request.uri();
        }

        @Override
        public Optional<Version> version() {
          return request.version();
        }

        @Override
        public HttpHeaders headers() {
          return request.headers();
        }
      };
    }

    <T> HttpResponse.BodyHandler<T> handler(HttpResponse.BodyHandler<T> handler) {
      return info -> {
        emit("RESPONSE_HEADERS", info.statusCode(), info.headers().map(), "headers", "", "");
        String type = info.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
        boolean sse = type.contains("text/event-stream");
        WireLog.Body body =
            new WireLog.Body(
                sse,
                type.isEmpty()
                    || type.contains("json")
                    || type.startsWith("text/")
                    || type.contains("xml"),
                value ->
                    emit(
                        "RESPONSE_BODY",
                        info.statusCode(),
                        Map.of(),
                        sse ? "raw-sse-frame" : "raw-body",
                        value[0],
                        value[1]));
        HttpResponse.BodySubscriber<T> target = handler.apply(info);
        return new HttpResponse.BodySubscriber<T>() {
          @Override
          public CompletionStage<T> getBody() {
            return target.getBody();
          }

          @Override
          public void onSubscribe(Flow.Subscription subscription) {
            target.onSubscribe(
                new Flow.Subscription() {
                  @Override
                  public void request(long count) {
                    subscription.request(count);
                  }

                  @Override
                  public void cancel() {
                    cancelledBySubscriber.set(true);
                    body.end(true);
                    subscription.cancel();
                  }
                });
          }

          @Override
          public void onNext(List<ByteBuffer> items) {
            items.forEach(body::accept);
            target.onNext(items);
          }

          @Override
          public void onError(Throwable error) {
            body.end(true);
            failure(error);
            target.onError(error);
          }

          @Override
          public void onComplete() {
            body.end(false);
            target.onComplete();
          }
        };
      };
    }
  }
}
