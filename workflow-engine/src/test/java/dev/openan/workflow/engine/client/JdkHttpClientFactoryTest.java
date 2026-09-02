/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.http.HttpClient;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class JdkHttpClientFactoryTest {
  @Test
  void doesNotAutomaticallyRedirectAuthenticatedProtocolRequests() {
    HttpClient client =
        JdkHttpClientFactory.create(
            true, null, null, null, null, null, Duration.ofSeconds(1), null);

    assertEquals(HttpClient.Redirect.NEVER, client.followRedirects());
  }
}
