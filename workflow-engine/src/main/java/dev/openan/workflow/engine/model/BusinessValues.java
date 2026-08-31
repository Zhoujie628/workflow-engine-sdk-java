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

package dev.openan.workflow.engine.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

/** Defensive JSON snapshots: payloads are unrestricted JSON, never shared mutable objects. */
public final class BusinessValues {
  private static final ObjectMapper JSON = new ObjectMapper();

  private BusinessValues() {}

  /** Snapshots any JSON value, preserving nested arrays as values rather than outputs. */
  public static Object snapshot(Object value) {
    return freeze(JSON.convertValue(value, Object.class));
  }

  /** Snapshots an ordered JSON object. */
  @SuppressWarnings("unchecked")
  public static Map<String, Object> map(Map<String, ?> value) {
    return (Map<String, Object>) freeze(JSON.convertValue(value, Map.class));
  }

  /** Snapshots ordered outputs without flattening nested arrays. */
  @SuppressWarnings("unchecked")
  public static List<Object> list(List<?> value) {
    return value == null ? List.of() : (List<Object>) freeze(JSON.convertValue(value, List.class));
  }

  private static Object freeze(Object value) {
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> copy = new LinkedHashMap<>();
      map.forEach((k, v) -> copy.put(String.valueOf(k), freeze(v)));
      return Collections.unmodifiableMap(copy);
    }
    if (value instanceof List<?> list) {
      List<Object> copy = new ArrayList<>();
      list.forEach(v -> copy.add(freeze(v)));
      return Collections.unmodifiableList(copy);
    }
    return value;
  }
}
