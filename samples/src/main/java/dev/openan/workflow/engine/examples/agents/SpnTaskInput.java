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

package dev.openan.workflow.engine.examples.agents;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Sample business input, constructed only from SDK-validated task or negotiation fields. */
public record SpnTaskInput(Map<String, Object> parameters) {
  public SpnTaskInput {
    parameters = Map.copyOf(parameters);
    List<String> invalid = invalidFields(parameters);
    if (!invalid.isEmpty())
      throw new IllegalArgumentException("Incomplete complaint parameters: " + invalid);
  }

  /** Fields requiring clarification; this is SPN business validation, not an engine constraint. */
  public static List<String> invalidFields(Map<String, Object> parameters) {
    List<String> fields = new ArrayList<>();
    if (field(parameters, "任务对象", "接入端口名称").isBlank()) fields.add("任务对象");
    if (field(parameters, "任务上下文", "投诉分类").isBlank()
        || field(parameters, "任务上下文", "OSS侧事件流水号").isBlank()) fields.add("任务上下文");
    return fields;
  }

  static String field(Map<String, Object> parameters, String section, String name) {
    Object raw = parameters.get(section);
    if (!(raw instanceof String text)) return "";
    var match =
        Pattern.compile(Pattern.quote(name) + "[：:]\\s*[\\\"“]?([^；;\\\"”\\r\\n]+)").matcher(text);
    String value = match.find() ? match.group(1).strip() : "";
    return "null".equalsIgnoreCase(value) ? "" : value;
  }

  static Map<String, Object> selected(Map<String, Object> values) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (String field : List.of("任务对象", "任务上下文")) {
      Object value = values.get(field);
      if (value instanceof String text) result.put(field, text);
    }
    return Map.copyOf(result);
  }

  /** The access port belonging to this validated city task. */
  public String accessPort() {
    return field(parameters, "任务对象", "接入端口名称");
  }

  /** The original business incident identifier, not the A2A task identifier. */
  public String incidentId() {
    return field(parameters, "任务上下文", "OSS侧事件流水号");
  }

  /** Business-only diagnostic input; never concatenate raw protocol or unvalidated replies here. */
  public String diagnosisInput() {
    return "已校验投诉参数\n任务对象：" + parameters.get("任务对象") + "\n任务上下文：" + parameters.get("任务上下文");
  }
}
