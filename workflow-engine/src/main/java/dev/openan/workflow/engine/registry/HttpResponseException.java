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

/**
 * A successful management HTTP response with invalid JSON or an incompatible payload. Deliberately
 * contains neither the body nor the original exception: parsers can echo credentials in messages.
 */
public final class HttpResponseException extends RuntimeException {
  private static final long serialVersionUID = 1L;
  private final int statusCode;

  HttpResponseException(String service, String operation, int statusCode) {
    super(
        service + " returned an invalid response for " + operation + " (HTTP " + statusCode + ")");
    this.statusCode = statusCode;
  }

  /** The successful HTTP status whose response could not be interpreted. */
  public int getStatusCode() {
    return statusCode;
  }
}
