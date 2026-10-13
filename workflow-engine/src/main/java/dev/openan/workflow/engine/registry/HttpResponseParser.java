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

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/** Security boundary for both JSON decoding and response-to-model conversion. */
final class HttpResponseParser {
  private static final ObjectMapper JSON =
      new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

  private HttpResponseParser() {}

  @SuppressWarnings("unchecked")
  static <T> T parse(
      String service,
      String operation,
      int status,
      String body,
      Function<Map<String, Object>, T> decode) {
    try {
      Map<String, Object> data = Objects.requireNonNull(JSON.readValue(body, Map.class));
      return decode.apply(data);
    } catch (IOException | RuntimeException invalidResponse) {
      // Do not retain parser/mapping causes or suppressed exceptions; these may contain body data.
      throw new HttpResponseException(service, operation, status);
    }
  }
}
