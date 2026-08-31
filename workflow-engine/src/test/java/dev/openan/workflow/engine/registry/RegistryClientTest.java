/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.registry;

import static org.junit.jupiter.api.Assertions.*;
import java.net.InetSocketAddress;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class RegistryClientTest {
  @Test
  void rejectsNonPositiveDeadline() {
    assertThrows(IllegalArgumentException.class, () -> new RegistryClient("http://localhost", true, Duration.ZERO));
  }

  @Test
  void deadlineIncludesStalledResponseBody() throws Exception {
    var server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var release = new java.util.concurrent.CountDownLatch(1);
    server.createContext("/", exchange -> {
      exchange.sendResponseHeaders(200, 100);
      exchange.getResponseBody().write('{');
      exchange.getResponseBody().flush();
      try { release.await(3, java.util.concurrent.TimeUnit.SECONDS); }
      catch (InterruptedException error) { Thread.currentThread().interrupt(); }
      finally { exchange.close(); }
    });
    server.start();
    try {
      var client = new RegistryClient("http://127.0.0.1:" + server.getAddress().getPort(), true, Duration.ofMillis(200));
      assertTimeoutPreemptively(Duration.ofSeconds(2), () ->
          assertThrows(java.net.http.HttpTimeoutException.class, client::fetchAgentCards));
    } finally {
      release.countDown();
      server.stop(0);
    }
  }
}
