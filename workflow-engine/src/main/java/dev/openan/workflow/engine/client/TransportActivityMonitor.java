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

import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Captures a per-request transport activity callback for asynchronous HTTP body observation. */
final class TransportActivityMonitor {
  private static final Logger log = LoggerFactory.getLogger(TransportActivityMonitor.class);
  private static final ThreadLocal<Runnable> CURRENT = new ThreadLocal<>();

  private TransportActivityMonitor() {}

  static <T> T call(Runnable listener, Supplier<T> action) {
    Runnable previous = CURRENT.get();
    if (listener == null) CURRENT.remove();
    else CURRENT.set(listener);
    try {
      return action.get();
    } finally {
      if (previous == null) CURRENT.remove();
      else CURRENT.set(previous);
    }
  }

  static Runnable capture() {
    return CURRENT.get();
  }

  static void notifyActivity(Runnable listener) {
    if (listener == null) return;
    try {
      listener.run();
    } catch (RuntimeException error) {
      log.warn("Transport activity callback failed", error);
    }
  }
}
