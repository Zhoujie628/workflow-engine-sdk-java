/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.openan.workflow.engine.model.SendMessageResult;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

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
                IllegalArgumentException.class,
                () -> subscription.isHealthy(Duration.ofSeconds(-1)));
    }
}
