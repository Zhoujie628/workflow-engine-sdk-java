/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.client;

import dev.openan.workflow.engine.model.SendMessageResult;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Explicit lifecycle handle for one long-lived Notification-T subscription.
 *
 * <p>The acknowledgement and stream completion are intentionally separate: an acknowledgement
 * only means that the subscription is active, while {@link #completion()} represents the lifetime
 * of the SSE channel.
 */
public final class NotificationSubscription implements AutoCloseable {
    private final String agentName;
    private final String contextId;
    private final Instant openedAt = Instant.now();
    private final CompletableFuture<SendMessageResult> acknowledgement = new CompletableFuture<>();
    private final CompletableFuture<Void> completion = new CompletableFuture<>();
    private final CompletableFuture<Void> streamTermination = new CompletableFuture<>();
    private final Runnable closeAction;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong eventCount = new AtomicLong();
    private final AtomicReference<Instant> lastEventAt = new AtomicReference<>();
    private volatile Throwable streamFailure;

    NotificationSubscription(String agentName, String contextId, Runnable closeAction) {
        this.agentName = Objects.requireNonNull(agentName, "agentName");
        this.contextId = Objects.requireNonNull(contextId, "contextId");
        this.closeAction = Objects.requireNonNull(closeAction, "closeAction");
    }

    public String agentName() {
        return agentName;
    }

    public String contextId() {
        return contextId;
    }

    public CompletableFuture<SendMessageResult> acknowledgement() {
        return acknowledgement;
    }

    public CompletableFuture<Void> completion() {
        return completion;
    }

    public boolean isActive() {
        return !closed.get() && !completion.isDone();
    }

    /** Current local liveness snapshot; every protocol event, including heartbeats, refreshes it. */
    public Heartbeat heartbeat() {
        return new Heartbeat(
                openedAt, lastEventAt.get(), eventCount.get(), isActive());
    }

    public boolean isHealthy(Duration maximumIdle) {
        Objects.requireNonNull(maximumIdle, "maximumIdle");
        if (maximumIdle.isNegative()) {
            throw new IllegalArgumentException("maximumIdle must not be negative");
        }
        Instant activity = lastEventAt.get();
        if (!isActive() || activity == null) {
            return false;
        }
        return Duration.between(activity, Instant.now()).compareTo(maximumIdle) <= 0;
    }

    void recordEvent() {
        eventCount.incrementAndGet();
        lastEventAt.set(Instant.now());
    }

    void acknowledge(SendMessageResult result) {
        acknowledgement.complete(result);
    }

    void failAcknowledgement(Throwable error) {
        acknowledgement.completeExceptionally(error);
    }

    void completeStream() {
        closed.set(true);
    }

    void failStream(Throwable error) {
        closed.set(true);
        streamFailure = error;
    }

    /** Internal signal that the thread executing the transport call has actually exited. */
    CompletableFuture<Void> streamTermination() {
        return streamTermination;
    }

    void markStreamTerminated() {
        streamTermination.complete(null);
        if (streamFailure == null) completion.complete(null);
        else completion.completeExceptionally(streamFailure);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            closeAction.run();
        } finally {
            acknowledgement.completeExceptionally(
                    new CancellationException(
                            "Notification-T subscription closed before acknowledgement"));
        }
    }

    public record Heartbeat(
            Instant openedAt, Instant lastEventAt, long eventCount, boolean active) {}
}
