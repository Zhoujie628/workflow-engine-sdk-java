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
            .followRedirects(HttpClient.Redirect.NEVER);
    if (executor != null) builder.executor(executor);
    SslContextFactory.create(
            sslVerify, caCertsPath, clientCertPath, clientKeyPath, clientKeyPassword, crlPath)
        .ifPresent(builder::sslContext);
    return builder.build();
  }
}
