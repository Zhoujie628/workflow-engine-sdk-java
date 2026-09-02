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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.eastcom.apollo.orders.internal.shaded.io.netty.buffer.ByteBuf;
import com.eastcom.apollo.orders.internal.shaded.io.netty.buffer.ByteBufUtil;
import com.eastcom.apollo.orders.internal.shaded.io.netty.buffer.Unpooled;
import com.eastcom.apollo.orders.internal.shaded.io.netty.channel.ChannelHandler;
import com.eastcom.apollo.orders.internal.shaded.io.netty.channel.ChannelHandlerContext;
import com.eastcom.apollo.orders.internal.shaded.io.netty.channel.ChannelOutboundHandlerAdapter;
import com.eastcom.apollo.orders.internal.shaded.io.netty.channel.ChannelPromise;
import com.eastcom.apollo.orders.internal.shaded.io.netty.channel.embedded.EmbeddedChannel;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class EastcomOrder118ByteBufWorkaroundTest {
  private static ChannelHandler releaseHandler() throws Exception {
    Field field = EastcomOrder118ByteBufWorkaround.class.getDeclaredField("RELEASE_HANDLER");
    field.setAccessible(true);
    return (ChannelHandler) field.get(null);
  }

  @Test
  void releasesMessageOnlyAfterTheConsumingBridgeHasCopiedIt() throws Exception {
    AtomicReference<byte[]> copied = new AtomicReference<>();
    ChannelOutboundHandlerAdapter consumingBridge =
        new ChannelOutboundHandlerAdapter() {
          @Override
          public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            copied.set(ByteBufUtil.getBytes((ByteBuf) msg));
            promise.setSuccess();
            // Models order-shaded-client:1.1.18: consume without forwarding or releasing.
          }
        };
    EmbeddedChannel channel = new EmbeddedChannel(consumingBridge, releaseHandler());
    ByteBuf source = Unpooled.copiedBuffer("request", StandardCharsets.UTF_8);

    channel.pipeline().writeAndFlush(source, channel.voidPromise());

    assertArrayEquals("request".getBytes(StandardCharsets.UTF_8), copied.get());
    assertEquals(0, source.refCnt(), "the consumed source buffer must be released once");
    channel.finishAndReleaseAll();
  }
}
