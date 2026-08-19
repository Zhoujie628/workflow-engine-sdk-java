/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.openan.workflow.engine.client;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.Executor;

/** Builds JDK HTTP clients from the engine's outbound TLS policy. */
final class JdkHttpClientFactory {

    private JdkHttpClientFactory() {}

    static HttpClient create(
            boolean sslVerify,
            String caCertsPath,
            String clientCertPath,
            String clientKeyPath,
            String clientKeyPassword,
            String crlPath,
            Duration connectTimeout,
            Executor executor) {
        HttpClient.Builder builder =
                HttpClient.newBuilder()
                        .version(HttpClient.Version.HTTP_1_1)
                        .connectTimeout(connectTimeout)
                        .followRedirects(HttpClient.Redirect.ALWAYS);
        if (executor != null) builder.executor(executor);
        SslContextFactory.create(
                        sslVerify,
                        caCertsPath,
                        clientCertPath,
                        clientKeyPath,
                        clientKeyPassword,
                        crlPath)
                .ifPresent(builder::sslContext);
        return builder.build();
    }
}
