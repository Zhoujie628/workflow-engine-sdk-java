/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.examples;

import com.eastcom.apollo.orders.commons.api.HttpSessionService;
import com.eastcom.apollo.orders.internal.shaded.com.google.protobuf.ByteString;
import com.eastcom.apollo.orders.internal.shaded.reactor.core.publisher.Flux;
import com.eastcom.apollo.orders.internal.shaded.v11x.com.eastcom.apollo.orders.client.httpsession.OrderHttpSessionClient;
import com.eastcom.apollo.orders.internal.shaded.v11x.com.eastcom.apollo.orders.commons.metadata.httpsession.OrderHttpSessionRequest;
import com.eastcom.apollo.orders.internal.shaded.v11x.com.eastcom.apollo.orders.commons.metadata.httpsession.OrderHttpSessionResponse;
import com.eastcom.apollo.orders.internal.shaded.v11x.com.eastcom.apollo.orders.commons.metadata.httpsession.OrderHttpSessionStrRequest;
import com.eastcom.apollo.orders.internal.shaded.v11x.com.eastcom.apollo.orders.commons.metadata.httpsession.OrderHttpSessionStrResponse;
import com.eastcom.apollo.orders.internal.shaded.v11x.com.eastcom.apollo.orders.commons.util.ObjectUtil;

import java.time.Duration;
import java.util.function.Predicate;

/**
 * Exposes the response Flux already provided by Order SDK 1.1.18.
 *
 * <p>The vendor's public {@code execute()} blocks with {@code blockLast()} and returns only one
 * response. This adapter is intentionally isolated because it depends on the versioned shaded
 * protocol types. Replace it when Eastcom exposes an official public streaming client method.
 */
final class StreamingOrderHttpSessionClient extends OrderHttpSessionClient {
    StreamingOrderHttpSessionClient() {}

    StreamingOrderHttpSessionClient(HttpSessionService service, String testSessionId) {
        this.orderService = service;
        this.sessionId = testSessionId;
        this.isConnected = true;
    }

    void executeStreaming(
            OrderHttpSessionStrRequest request,
            int timeoutMillis,
            Predicate<OrderHttpSessionStrResponse> responseSink) {
        if (!isConnected()) {
            throw new IllegalStateException("Order HTTP session is not initialized");
        }
        if (!(orderService instanceof HttpSessionService service)) {
            throw new IllegalStateException(
                    "Order SDK 1.1.18 HttpSessionService is unavailable; "
                            + "verify the Eastcom SDK version");
        }
        OrderHttpSessionRequest wireRequest = toWireRequest(request);
        service.execute(Flux.just(wireRequest))
                // Do not cancel the vendor RSocket Flux as soon as INPUT_REQUIRED arrives.
                // The A2A request is terminal for this negotiation round, but the authenticated
                // Order session must remain usable by the follow-up execute. Order SDK 1.1.18 can
                // leave the shared RPC proxy unable to accept that execute after takeUntil sends
                // cancellation. Consume the platform's natural end-of-round completion instead.
                .doOnNext(response -> responseSink.test(toStringResponse(response)))
                .blockLast(Duration.ofMillis(timeoutMillis));
    }

    private OrderHttpSessionRequest toWireRequest(OrderHttpSessionStrRequest request) {
        return OrderHttpSessionRequest.newBuilder()
                .setUriPath(request.getUriPath())
                .setMethod(request.getMethod())
                .putAllCookies(request.getCookiesMap())
                .putAllHeaders(request.getHeadersMap())
                .setBody(ByteString.copyFrom(ObjectUtil.o2b(request.getBody())))
                .putAllParams(request.getParamsMap())
                .setSessionId(getSessionId())
                .putAllAdditionalParams(request.getAdditionalParamsMap())
                .build();
    }

    private static OrderHttpSessionStrResponse toStringResponse(
            OrderHttpSessionResponse response) {
        Object decoded =
                response.getBody().isEmpty()
                        ? null
                        : ObjectUtil.b2o(response.getBody().toByteArray());
        return OrderHttpSessionStrResponse.newBuilder()
                .setStatus(response.getStatus())
                .putAllHeaders(response.getHeadersMap())
                .putAllCookies(response.getCookiesMap())
                .setBody(decoded == null ? "" : decoded.toString())
                .build();
    }
}
