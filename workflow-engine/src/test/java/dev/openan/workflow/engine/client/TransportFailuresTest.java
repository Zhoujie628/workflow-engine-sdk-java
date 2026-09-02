/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.channels.AsynchronousCloseException;
import org.junit.jupiter.api.Test;

class TransportFailuresTest {
  @Test
  void prefersExceptionTypeAndStillSupportsKnownProviderFallbacks() {
    assertTrue(
        TransportFailures.isExpectedLocalClose(
            new RuntimeException("wrapper", new AsynchronousCloseException())));
    assertTrue(
        TransportFailures.isExpectedLocalClose(new IOException("Connection closed locally")));
    assertFalse(TransportFailures.isExpectedLocalClose(new IOException("connection reset by peer")));
  }
}
