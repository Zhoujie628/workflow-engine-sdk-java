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

package dev.openan.workflow.engine.examples.gateway;

import java.util.*;

/**
 * SDK-visible response. A multi-value header is not collapsed into the vendor protobuf's string
 * map.
 */
record OrderResponse(
    int status, String body, Map<String, List<String>> headers, String representation) {
  OrderResponse {
    body = body == null ? "" : body;
    Map<String, List<String>> copy = new LinkedHashMap<>();
    if (headers != null)
      headers.forEach(
          (key, values) -> {
            if (key != null) copy.put(key, List.copyOf(values));
          });
    headers = Collections.unmodifiableMap(copy);
    Objects.requireNonNull(representation, "representation");
  }
}
