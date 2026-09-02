/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.client;

import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.ClosedChannelException;
import java.util.Locale;
import java.util.concurrent.CancellationException;

/** Classifies transport termination without making provider-specific messages the primary signal. */
final class TransportFailures {
  private TransportFailures() {}

  static boolean isExpectedLocalClose(Throwable error) {
    for (Throwable current = error; current != null; current = current.getCause()) {
      if (current instanceof InterruptedException
          || current instanceof CancellationException
          || current instanceof AsynchronousCloseException
          || current instanceof ClosedChannelException) {
        return true;
      }
      String message = current.getMessage();
      if (message != null) {
        String normalized = message.toLowerCase(Locale.ROOT);
        if (normalized.contains("connection closed locally")
            || normalized.contains("chunked transfer encoding, state: reading_length")) {
          return true;
        }
      }
    }
    return false;
  }
}
