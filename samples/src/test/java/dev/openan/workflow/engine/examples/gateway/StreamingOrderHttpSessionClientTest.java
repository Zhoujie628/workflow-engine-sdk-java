/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.examples.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.eastcom.apollo.orders.commons.api.HttpSessionService;
import com.eastcom.apollo.orders.internal.shaded.com.google.protobuf.ByteString;
import com.eastcom.apollo.orders.internal.shaded.com.google.protobuf.BoolValue;
import com.eastcom.apollo.orders.internal.shaded.com.google.protobuf.StringValue;
import com.eastcom.apollo.orders.internal.shaded.reactor.core.publisher.Flux;
import com.eastcom.apollo.orders.internal.shaded.reactor.core.publisher.Mono;
import com.eastcom.apollo.orders.internal.shaded.v11x.com.eastcom.apollo.orders.commons.metadata.AuthRequest;
import com.eastcom.apollo.orders.internal.shaded.v11x.com.eastcom.apollo.orders.commons.metadata.AuthResponse;
import com.eastcom.apollo.orders.internal.shaded.v11x.com.eastcom.apollo.orders.commons.metadata.DisconnectRequest;
import com.eastcom.apollo.orders.internal.shaded.v11x.com.eastcom.apollo.orders.commons.metadata.httpsession.OrderHttpSessionInitRequest;
import com.eastcom.apollo.orders.internal.shaded.v11x.com.eastcom.apollo.orders.commons.metadata.httpsession.OrderHttpSessionInitResponse;
import com.eastcom.apollo.orders.internal.shaded.v11x.com.eastcom.apollo.orders.commons.metadata.httpsession.OrderHttpSessionRequest;
import com.eastcom.apollo.orders.internal.shaded.v11x.com.eastcom.apollo.orders.commons.metadata.httpsession.OrderHttpSessionResponse;
import com.eastcom.apollo.orders.internal.shaded.v11x.com.eastcom.apollo.orders.commons.metadata.httpsession.OrderHttpSessionStrRequest;
import com.eastcom.apollo.orders.internal.shaded.v11x.com.eastcom.apollo.orders.commons.metadata.httpsession.OrderHttpSessionStrResponse;
import com.eastcom.apollo.orders.internal.shaded.v11x.com.eastcom.apollo.orders.commons.util.ObjectUtil;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

class StreamingOrderHttpSessionClientTest {
    @Test
    void exposesEveryVendorFluxResponseInOrder() {
        var wireRequests = new ArrayList<OrderHttpSessionRequest>();
        HttpSessionService service =
                new HttpSessionService() {
                    @Override
                    public Flux<OrderHttpSessionResponse> execute(
                            Flux<OrderHttpSessionRequest> requests) {
                        wireRequests.addAll(requests.collectList().block());
                        return Flux.just(response("first"), response("second"));
                    }

                    @Override
                    public Mono<OrderHttpSessionInitResponse> init(
                            OrderHttpSessionInitRequest request) {
                        return Mono.just(OrderHttpSessionInitResponse.getDefaultInstance());
                    }

                    @Override
                    public Mono<AuthResponse> login(AuthRequest request) {
                        return Mono.just(AuthResponse.getDefaultInstance());
                    }

                    @Override
                    public Mono<Void> logout(DisconnectRequest request) {
                        return Mono.empty();
                    }

                    @Override
                    public Mono<BoolValue> isConnected(StringValue sessionId) {
                        return Mono.just(BoolValue.of(true));
                    }

                    @Override
                    public Mono<Void> close(StringValue sessionId) {
                        return Mono.empty();
                    }
                };
        var client = new StreamingOrderHttpSessionClient(service, "session-1");
        var received = new ArrayList<OrderHttpSessionStrResponse>();
        OrderHttpSessionStrRequest request =
                OrderHttpSessionStrRequest.newBuilder()
                        .setUriPath("/a2a/json/message:stream")
                        .setMethod("POST")
                        .setBody("request-body")
                        .build();

        client.executeStreaming(
                request,
                1_000,
                response -> {
                    received.add(response);
                    return false;
                });

        assertEquals(List.of("first", "second"), received.stream().map(r -> r.getBody()).toList());
        assertEquals(1, wireRequests.size());
        assertEquals("session-1", wireRequests.get(0).getSessionId());
        assertEquals("/a2a/json/message:stream", wireRequests.get(0).getUriPath());
    }

    @Test
    void drainsVendorFluxAfterTerminalResponseWithoutCancellingRpcChannel() {
        HttpSessionService service = serviceWithResponses("working", "completed", "unexpected");
        var client = new StreamingOrderHttpSessionClient(service, "session-1");
        var received = new ArrayList<String>();
        OrderHttpSessionStrRequest request =
                OrderHttpSessionStrRequest.newBuilder()
                        .setUriPath("/message:stream")
                        .setMethod("POST")
                        .setBody("request")
                        .build();

        client.executeStreaming(
                request,
                1_000,
                response -> {
                    received.add(response.getBody());
                    return "completed".equals(response.getBody());
                });

        assertEquals(List.of("working", "completed", "unexpected"), received);
    }

    private static HttpSessionService serviceWithResponses(String... bodies) {
        return new HttpSessionService() {
            @Override
            public Flux<OrderHttpSessionResponse> execute(Flux<OrderHttpSessionRequest> requests) {
                return Flux.fromArray(
                        java.util.Arrays.stream(bodies)
                                .map(StreamingOrderHttpSessionClientTest::response)
                                .toArray(OrderHttpSessionResponse[]::new));
            }

            @Override
            public Mono<OrderHttpSessionInitResponse> init(OrderHttpSessionInitRequest request) {
                return Mono.just(OrderHttpSessionInitResponse.getDefaultInstance());
            }

            @Override
            public Mono<AuthResponse> login(AuthRequest request) {
                return Mono.just(AuthResponse.getDefaultInstance());
            }

            @Override
            public Mono<Void> logout(DisconnectRequest request) {
                return Mono.empty();
            }

            @Override
            public Mono<BoolValue> isConnected(StringValue sessionId) {
                return Mono.just(BoolValue.of(true));
            }

            @Override
            public Mono<Void> close(StringValue sessionId) {
                return Mono.empty();
            }
        };
    }

    private static OrderHttpSessionResponse response(String body) {
        return OrderHttpSessionResponse.newBuilder()
                .setStatus(200)
                .setBody(ByteString.copyFrom(ObjectUtil.o2b(body)))
                .build();
    }
}
