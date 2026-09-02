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
