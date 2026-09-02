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
package dev.openan.workflow.engine.examples.gateway;

import com.eastcom.apollo.orders.internal.shaded.io.netty.channel.ChannelHandler;
import com.eastcom.apollo.orders.internal.shaded.io.netty.channel.ChannelHandlerContext;
import com.eastcom.apollo.orders.internal.shaded.io.netty.channel.ChannelOutboundHandlerAdapter;
import com.eastcom.apollo.orders.internal.shaded.io.netty.channel.ChannelPipeline;
import com.eastcom.apollo.orders.internal.shaded.io.netty.channel.ChannelPromise;
import com.eastcom.apollo.orders.internal.shaded.io.netty.util.ReferenceCountUtil;
import com.eastcom.apollo.orders.internal.shaded.reactor.netty.Connection;
import com.eastcom.apollo.orders.internal.shaded.reactor.netty.http.client.HttpClientRequest;
import com.eastcom.apollo.orders.internal.shaded.v11x.com.eastcom.apollo.orders.client.core.common.ServerInfo;
import com.eastcom.apollo.orders.internal.shaded.v11x.com.eastcom.apollo.orders.client.http.HttpClient;
import com.eastcom.apollo.orders.internal.shaded.v11x.com.eastcom.apollo.orders.client.http.HttpClientConfig;
import com.eastcom.apollo.orders.internal.shaded.v11x.com.eastcom.apollo.orders.client.http.HttpRequestConfig;
import com.eastcom.apollo.orders.internal.shaded.v11x.com.eastcom.apollo.orders.client.http.internal.ReactorNettyBridgeHandler;
import java.lang.reflect.Field;
import java.util.function.BiConsumer;

/**
 * Isolates a reference-count ownership defect in Eastcom {@code order-shaded-client:1.1.18}.
 *
 * <p>That version's {@link ReactorNettyBridgeHandler#write(ChannelHandlerContext, Object,
 * ChannelPromise)} converts an outbound {@code ByteBuf} to an {@code OrderHttpRequest} and consumes
 * the write, but does not release the source buffer. The vendor's public {@code HttpClient} creates
 * that buffer with {@code PooledByteBufAllocator.DEFAULT}, so every request body can otherwise leak
 * direct memory.
 *
 * <p>The handler installed here sits immediately after the vendor bridge in outbound traversal. It
 * delegates synchronously to the bridge and releases only after the bridge has copied the bytes. No
 * vendor class or jar is replaced. Remove this class after upgrading to a vendor release whose
 * bridge releases the consumed message itself.
 */
final class EastcomOrder118ByteBufWorkaround {
  private static final String RELEASE_HANDLER_NAME =
      EastcomOrder118ByteBufWorkaround.class.getName() + ".releaseAfterBridge";
  private static final Field HTTP_CLIENT_CONFIGURATION = configurationField();
  private static final ChannelHandler RELEASE_HANDLER = new ReleaseAfterVendorBridge();

  private EastcomOrder118ByteBufWorkaround() {}

  /** Creates the public vendor client and installs the narrowly scoped 1.1.18 ownership fix. */
  static HttpClient createClient(ServerInfo serverInfo, HttpRequestConfig requestConfig) {
    HttpClient client = HttpClient.create(serverInfo, requestConfig);
    ReleasingHttpClientConfig configuration = new ReleasingHttpClientConfig();
    configuration.setServerInfo(serverInfo);
    configuration.setRequestConfig(requestConfig);
    try {
      HTTP_CLIENT_CONFIGURATION.set(client, configuration);
    } catch (IllegalAccessException e) {
      throw new IllegalStateException(
          "Cannot install Eastcom 1.1.18 ByteBuf ownership workaround", e);
    }
    return client;
  }

  private static Field configurationField() {
    try {
      Field field = HttpClient.class.getDeclaredField("configuration");
      field.setAccessible(true);
      return field;
    } catch (ReflectiveOperationException | RuntimeException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private static void installReleaseHandler(Connection connection) {
    ChannelPipeline pipeline = connection.channel().pipeline();
    if (pipeline.context(RELEASE_HANDLER_NAME) != null) {
      return;
    }
    ChannelHandlerContext bridge = pipeline.context(ReactorNettyBridgeHandler.class);
    if (bridge == null) {
      throw new IllegalStateException(
          "Eastcom 1.1.18 ReactorNettyBridgeHandler is absent; review or remove its ByteBuf workaround");
    }
    pipeline.addAfter(bridge.name(), RELEASE_HANDLER_NAME, RELEASE_HANDLER);
  }

  private static final class ReleasingHttpClientConfig extends HttpClientConfig {
    @Override
    public BiConsumer<HttpClientRequest, Connection> httpClientRequest() {
      BiConsumer<HttpClientRequest, Connection> vendorCallback = super.httpClientRequest();
      return (request, connection) -> {
        vendorCallback.accept(request, connection);
        installReleaseHandler(connection);
      };
    }
  }

  @ChannelHandler.Sharable
  private static final class ReleaseAfterVendorBridge extends ChannelOutboundHandlerAdapter {
    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise)
        throws Exception {
      try {
        // The immediately following vendor bridge copies and consumes msg synchronously.
        ctx.write(msg, promise);
      } finally {
        ReferenceCountUtil.safeRelease(msg);
      }
    }
  }
}
