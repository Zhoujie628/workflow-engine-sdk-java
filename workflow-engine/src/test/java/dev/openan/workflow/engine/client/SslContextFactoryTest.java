/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.ExecutorService;

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
                IllegalStateException.class,
                () -> SslContextFactory.create(true, "missing-ca-file.pem"));
    }

    @Test
    void insecureServerVerificationStillLoadsClientIdentity() {
        assertThrows(
                IllegalStateException.class,
                () ->
                        SslContextFactory.create(
                                false,
                                null,
                                "missing-client-cert.pem",
                                "missing-client-key.pem",
                                null,
                                null));
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
