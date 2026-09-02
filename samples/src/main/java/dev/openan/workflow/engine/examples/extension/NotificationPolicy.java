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
package dev.openan.workflow.engine.examples.extension;

import java.util.Map;
import java.util.Objects;

/** SDK-validated Notification-T service-recovery subscription parameters. */
public record NotificationPolicy(String condition, String reportFormat) {
  public static final String CONDITION_FIELD = "订阅条件";
  public static final String REPORT_FORMAT_FIELD = "上报通知数据格式";

  public NotificationPolicy {
    condition = condition == null ? "" : condition.strip();
    reportFormat = requireText(reportFormat, REPORT_FORMAT_FIELD);
  }

  public static NotificationPolicy fromValidated(Map<String, Object> data) {
    Objects.requireNonNull(data, "Validated Notification-T data is required");
    Object rawCondition = data.get(CONDITION_FIELD);
    if (rawCondition != null && !(rawCondition instanceof String)) {
      throw new IllegalArgumentException("Notification field must be text: " + CONDITION_FIELD);
    }
    return new NotificationPolicy(
        rawCondition instanceof String text ? text : "",
        requireText(data.get(REPORT_FORMAT_FIELD), REPORT_FORMAT_FIELD));
  }

  private static String requireText(Object value, String field) {
    String text = value instanceof String string ? string.strip() : "";
    if (text.isEmpty()) {
      throw new IllegalArgumentException("Notification field is required: " + field);
    }
    return text;
  }
}
