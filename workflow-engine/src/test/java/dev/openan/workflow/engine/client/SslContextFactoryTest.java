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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.concurrent.ExecutorService;
import org.junit.jupiter.api.Test;

class SslContextFactoryTest {
  private static final String HOSTNAME_PROPERTY =
      "jdk.internal.httpclient.disableHostnameVerification";

  @Test
  void trustAllContextDoesNotMutateJvmHostnameVerification() {
    String previous = System.getProperty(HOSTNAME_PROPERTY);
    try {
      System.clearProperty(HOSTNAME_PROPERTY);
      SslContextFactory.createTrustAll();
      assertNull(System.getProperty(HOSTNAME_PROPERTY));
    } finally {
      if (previous == null) System.clearProperty(HOSTNAME_PROPERTY);
      else System.setProperty(HOSTNAME_PROPERTY, previous);
    }
  }

  @Test
  void invalidConfiguredCaFailsClosed() {
    assertThrows(
        IllegalStateException.class, () -> SslContextFactory.create(true, "missing-ca-file.pem"));
  }

  @Test
  void insecureServerVerificationStillLoadsClientIdentity() {
    assertThrows(
        IllegalStateException.class,
        () ->
            SslContextFactory.create(
                false, null, "missing-client-cert.pem", "missing-client-key.pem", null, null));
  }

  @Test
  void runtimeClosesItsOwnedExecutor() throws Exception {
    DefaultA2AJavaClientRuntime runtime = new DefaultA2AJavaClientRuntime();
    Field field = DefaultA2AJavaClientRuntime.class.getDeclaredField("httpClientExecutor");
    field.setAccessible(true);
    ExecutorService executor = (ExecutorService) field.get(runtime);
    assertFalse(executor.isShutdown());
    runtime.close();
    assertTrue(executor.isShutdown());
  }
}
