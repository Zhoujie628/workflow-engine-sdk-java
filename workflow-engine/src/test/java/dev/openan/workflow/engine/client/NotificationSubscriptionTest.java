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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.openan.workflow.engine.model.SendMessageResult;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class NotificationSubscriptionTest {

  @Test
  void eventsRefreshHeartbeatAndExplicitCloseIsIdempotent() {
    AtomicInteger closes = new AtomicInteger();
    NotificationSubscription subscription =
        new NotificationSubscription("city1", "ctx-1", closes::incrementAndGet);

    assertTrue(subscription.isActive());
    assertEquals(0, subscription.heartbeat().eventCount());
    assertFalse(subscription.isHealthy(Duration.ofSeconds(1)));

    subscription.recordEvent();
    subscription.acknowledge(
        SendMessageResult.builder().text("ack").taskState("TASK_STATE_WORKING").build());

    assertEquals(1, subscription.heartbeat().eventCount());
    assertTrue(subscription.isHealthy(Duration.ofSeconds(1)));
    subscription.close();
    subscription.close();

    assertEquals(1, closes.get());
    assertFalse(subscription.isActive());
    assertFalse(subscription.completion().isDone());
    assertFalse(subscription.streamTermination().isDone());
    subscription.markStreamTerminated();
    assertTrue(subscription.completion().isDone());
    assertTrue(subscription.streamTermination().isDone());
    assertEquals("ack", subscription.acknowledgement().join().getText());
  }

  @Test
  void closingBeforeAcknowledgementNeverLeavesCallerWaiting() {
    NotificationSubscription subscription =
        new NotificationSubscription("city1", "ctx-1", () -> {});

    subscription.close();

    assertTrue(subscription.acknowledgement().isCompletedExceptionally());
    assertThrows(
        java.util.concurrent.CancellationException.class,
        () -> subscription.acknowledgement().join());
    assertThrows(
        IllegalArgumentException.class, () -> subscription.isHealthy(Duration.ofSeconds(-1)));
  }
}
