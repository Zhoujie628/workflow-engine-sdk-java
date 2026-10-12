/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */
package dev.openan.workflow.engine.registry;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import javax.net.ssl.HttpsURLConnection;

/** Shared connection-local transport; does not follow redirects or own authentication refresh. */
final class CenterHttpClient {
  private CenterHttpClient() {}

  static Response execute(
      String method,
      URI uri,
      String body,
      HttpAuthProvider auth,
      TlsPolicy tls,
      LoadPsop.Timeouts timeouts,
      int responseLimit)
      throws Exception {
    Map<String, String> headers = HttpAuthentication.headers(auth, uri);
    var connection = (HttpURLConnection) uri.toURL().openConnection();
    try {
      if (connection instanceof HttpsURLConnection https) tls.configure(https);
      connection.setInstanceFollowRedirects(false);
      connection.setConnectTimeout(timeouts.connectTimeoutMillis());
      connection.setReadTimeout(timeouts.readTimeoutMillis());
      connection.setRequestMethod(method);
      headers.forEach(connection::setRequestProperty);
      if (body != null) {
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        try (var output = connection.getOutputStream()) {
          output.write(body.getBytes(StandardCharsets.UTF_8));
        }
      }
      int status = connection.getResponseCode();
      try (InputStream input =
          status >= 400 ? connection.getErrorStream() : connection.getInputStream()) {
        byte[] bytes =
            input == null
                ? new byte[0]
                : responseLimit > 0 ? input.readNBytes(responseLimit + 1) : input.readAllBytes();
        if (responseLimit > 0 && bytes.length > responseLimit) {
          throw new HttpResponseException("Orchestration center", "read response", status);
        }
        return new Response(status, new String(bytes, StandardCharsets.UTF_8), headers);
      }
    } finally {
      connection.disconnect();
    }
  }

  record Response(int statusCode, String body, Map<String, String> credentials) {}
}
