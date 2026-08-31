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

import com.google.protobuf.MessageOrBuilder;
import com.google.protobuf.util.JsonFormat;
import io.grpc.*;
import java.util.*;
import java.util.function.Consumer;

/** Captures actual gRPC metadata and protobuf values, never labels them HTTP JSON. */
final class WireGrpcInterceptor implements ClientInterceptor {
    private final Consumer<WireLog.Entry> observer;
    WireGrpcInterceptor() { this(null); }
    WireGrpcInterceptor(Consumer<WireLog.Entry> observer) { this.observer = observer; }

    @Override public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
            MethodDescriptor<ReqT, RespT> method, CallOptions options, Channel channel) {
        String requestId = UUID.randomUUID().toString();
        Map<String, String> context = WireLog.context();
        return new ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(channel.newCall(method, options)) {
            private void emit(String direction, Metadata metadata, Object value, String detail) {
                String body = "";
                String representation = "grpc-metadata";
                if (value instanceof MessageOrBuilder protobuf) {
                    representation = "protobuf-json-view";
                    try { body = JsonFormat.printer().print(protobuf); }
                    catch (com.google.protobuf.InvalidProtocolBufferException error) {
                        detail += "; protobuf-view=unavailable";
                    }
                } else if (value != null) {
                    representation = "unobserved-message";
                    detail += "; non-protobuf message=" + value.getClass().getName();
                }
                WireLog.emit(observer, "DIRECT_GRPC", direction, requestId, channel.authority(),
                        method.getFullMethodName(), null, headers(metadata), representation, body,
                        "grpc-call-boundary; HTTP/2 frames/TLS=unobserved; " + detail, context);
            }
            @Override public void start(Listener<RespT> listener, Metadata headers) {
                emit("REQUEST_HEADERS", headers, null, "");
                super.start(new ForwardingClientCallListener.SimpleForwardingClientCallListener<RespT>(listener) {
                    @Override public void onHeaders(Metadata headers) {
                        emit("RESPONSE_HEADERS", headers, null, "");
                        super.onHeaders(headers);
                    }
                    @Override public void onMessage(RespT value) {
                        emit("RESPONSE_BODY", null, value, "");
                        super.onMessage(value);
                    }
                    @Override public void onClose(Status status, Metadata trailers) {
                        emit("RESPONSE_TRAILERS", trailers, null, "grpcStatus=" + status.getCode());
                        super.onClose(status, trailers);
                    }
                }, headers);
            }
            @Override public void sendMessage(ReqT value) {
                emit("REQUEST_BODY", null, value, "");
                super.sendMessage(value);
            }
        };
    }

    private static Map<String, List<String>> headers(Metadata metadata) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        if (metadata == null) return result;
        for (String name : metadata.keys()) {
            List<String> values = new ArrayList<>();
            if (name.endsWith(Metadata.BINARY_HEADER_SUFFIX)) {
                Iterable<byte[]> raw = metadata.getAll(Metadata.Key.of(name, Metadata.BINARY_BYTE_MARSHALLER));
                if (raw != null) raw.forEach(bytes -> values.add(Base64.getEncoder().encodeToString(bytes)));
            } else {
                Iterable<String> raw = metadata.getAll(Metadata.Key.of(name, Metadata.ASCII_STRING_MARSHALLER));
                if (raw != null) raw.forEach(values::add);
            }
            result.put(name, values);
        }
        return result;
    }
}
