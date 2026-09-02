/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.client;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;

/** Synchronous, conversation-free HTTP boundary used by credential login requests. */
final class CredentialHttpTransport {
  private final SSLContext sslContext;
  private final int timeoutMillis;

  private CredentialHttpTransport(SSLContext sslContext, Duration timeout) {
    this.sslContext = sslContext;
    this.timeoutMillis = Math.toIntExact(timeout.toMillis());
  }

  static CredentialHttpTransport create(
      boolean sslVerify,
      String caCertsPath,
      String clientCertPath,
      String clientKeyPath,
      String clientKeyPassword,
      String crlPath,
      Duration timeout) {
    SSLContext context =
        SslContextFactory.create(
                sslVerify, caCertsPath, clientCertPath, clientKeyPath, clientKeyPassword, crlPath)
            .orElse(null);
    return new CredentialHttpTransport(context, timeout);
  }

  Response send(URI uri, String method, String contentType, String body) throws Exception {
    HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
    if (sslContext != null && connection instanceof HttpsURLConnection https) {
      https.setSSLSocketFactory(sslContext.getSocketFactory());
    }
    try {
      connection.setConnectTimeout(timeoutMillis);
      connection.setReadTimeout(timeoutMillis);
      connection.setInstanceFollowRedirects(false);
      connection.setRequestMethod(method);
      connection.setDoOutput(true);
      connection.setRequestProperty("Content-Type", contentType);
      try (OutputStream output = connection.getOutputStream()) {
        output.write(body.getBytes(StandardCharsets.UTF_8));
      }
      int status = connection.getResponseCode();
      InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
      String responseBody;
      if (input == null) {
        responseBody = "";
      } else {
        try (input) {
          responseBody = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
      }
      return new Response(status, responseBody);
    } finally {
      connection.disconnect();
    }
  }

  record Response(int statusCode, String body) {}
}
