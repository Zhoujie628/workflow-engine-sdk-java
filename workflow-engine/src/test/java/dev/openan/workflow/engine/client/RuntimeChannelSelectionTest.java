/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.a2aproject.sdk.client.transport.spi.interceptors.ClientCallContext;
import org.junit.jupiter.api.Test;

class RuntimeChannelSelectionTest {
  @Test
  void channelSelectionUsesExplicitLocalStateRatherThanBusinessMetadata() {
    ClientCallContext taskContext = new ClientCallContext(new HashMap<>(), new HashMap<>());
    assertFalse(DefaultA2AJavaClientRuntime.isNotificationStream(taskContext));

    ClientCallContext notificationContext =
        new ClientCallContext(
            new HashMap<>(
                Map.of(
                    A2AJavaClientRuntime.CHANNEL_STATE_KEY,
                    A2AJavaClientRuntime.NOTIFICATION_CHANNEL)),
            new HashMap<>());
    assertTrue(DefaultA2AJavaClientRuntime.isNotificationStream(notificationContext));
  }
}
