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

package dev.openan.workflow.engine.registry;

import java.net.URI;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Shared request-boundary validation; never renders credential values in errors. */
final class HttpAuthentication {
  private static final Set<String> RESERVED =
      Set.of(
          "host",
          "connection",
          "content-length",
          "transfer-encoding",
          "upgrade",
          "expect",
          "content-type",
          "proxy-authorization");

  private HttpAuthentication() {}

  static String baseUrl(String url) {
    Objects.requireNonNull(url, "url");
    URI uri;
    try {
      uri = URI.create(url);
    } catch (IllegalArgumentException invalidUrl) {
      // URI parsing errors render their input, which may itself contain misplaced credentials.
      throw new IllegalArgumentException("Service URL must be a valid HTTP(S) URL");
    }
    if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
        || uri.getHost() == null
        || uri.getRawUserInfo() != null
        || uri.getRawQuery() != null
        || uri.getRawFragment() != null) {
      throw new IllegalArgumentException(
          "Service URL must be HTTP(S), without credentials, query or fragment");
    }
    return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
  }

  static Map<String, String> headers(HttpAuthProvider auth, URI uri) {
    Map<String, String> supplied;
    try {
      supplied = Objects.requireNonNull(auth.headers(uri), "authentication headers");
    } catch (HttpServiceException | HttpResponseException safeFailure) {
      throw safeFailure;
    } catch (RuntimeException providerFailure) {
      // IAM clients may include credentials or response bodies in their exception messages.
      throw new IllegalStateException(
          "HTTP authentication provider failed ("
              + providerFailure.getClass().getSimpleName()
              + ")");
    }
    Map<String, String> result = new LinkedHashMap<>();
    Set<String> seen = new HashSet<>();
    supplied.forEach(
        (name, value) -> {
          if (name == null
              || !name.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
              || RESERVED.contains(name.toLowerCase(Locale.ROOT))
              || !seen.add(name.toLowerCase(Locale.ROOT))
              || value == null
              || value.isBlank()
              || value.chars().anyMatch(c -> c < 32 || c > 126)) {
            throw new IllegalArgumentException(
                "Invalid, duplicate or reserved authentication header");
          }
          result.put(name, value);
        });
    return Map.copyOf(result);
  }

  static boolean onUnauthorized(HttpAuthProvider auth, URI uri, Map<String, String> headers) {
    try {
      return auth.onUnauthorized(uri, headers);
    } catch (RuntimeException providerFailure) {
      throw new IllegalStateException(
          "HTTP authentication refresh failed ("
              + providerFailure.getClass().getSimpleName()
              + ")");
    }
  }
}
