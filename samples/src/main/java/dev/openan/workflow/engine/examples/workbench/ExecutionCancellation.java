/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.examples.workbench;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;

/** Request-local cancellation; independent extension subscriptions are deliberately not bound. */
final class ExecutionCancellation {
  private final java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean();
  private final java.util.concurrent.atomic.AtomicReference<CompletableFuture<?>> execution = new java.util.concurrent.atomic.AtomicReference<>();

  void bind(CompletableFuture<?> execution) {
    this.execution.set(execution);
    if (cancelled.get()) execution.cancel(true);
  }

  void cancel() {
    cancelled.set(true);
    CompletableFuture<?> active = execution.get();
    if (active != null) active.cancel(true);
  }

  void check() {
    if (cancelled.get()) throw new CancellationException("Workflow execution cancelled");
  }

  boolean isCancelled() {
    return cancelled.get();
  }
}
