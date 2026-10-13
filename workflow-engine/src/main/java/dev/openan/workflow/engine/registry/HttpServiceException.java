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

import dev.openan.workflow.engine.util.SensitiveDataRedactor;
import java.util.Map;

/** A management service HTTP rejection, distinguishable from transport and parsing failures. */
public final class HttpServiceException extends RuntimeException {
  private static final long serialVersionUID = 1L;
  private final int statusCode;

  HttpServiceException(String service, int status, String body, Map<String, String> credentials) {
    super(service + " returned " + status + detail(status, body, credentials));
    statusCode = status;
  }

  /** HTTP response status. */
  public int getStatusCode() {
    return statusCode;
  }

  /** Authentication and permission errors must not trigger an alternate business execution. */
  public boolean isAuthenticationFailure() {
    return statusCode == 401 || statusCode == 403;
  }

  private static String detail(int status, String body, Map<String, String> credentials) {
    // Authentication response bodies may echo opaque tokens without a recognizable field name.
    if (status == 401 || status == 403) return "";
    String safe = body == null ? "" : body;
    for (String value : credentials.values()) {
      safe = safe.replace(value, "***");
      if (value.regionMatches(true, 0, "Bearer ", 0, 7)) {
        safe = safe.replace(value.substring(7), "***");
      }
    }
    safe = SensitiveDataRedactor.redact(safe).replace("\r", "\\r").replace("\n", "\\n");
    if (safe.length() > 512) safe = safe.substring(0, 512) + "...";
    return safe.isBlank() ? "" : ": " + safe;
  }
}
