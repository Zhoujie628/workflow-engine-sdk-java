/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
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
