/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.examples.extension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import org.junit.jupiter.api.Test;

class NotificationPolicyTest {

  @Test
  void acceptsSdkValidatedRecoverySubscription() {
    NotificationPolicy policy =
        NotificationPolicy.fromValidated(
            Map.of(
                NotificationPolicy.CONDITION_FIELD,
                "业务抢通完成",
                NotificationPolicy.REPORT_FORMAT_FIELD,
                "投诉诊断任务流水号、执行状态、执行结果"));

    assertEquals("业务抢通完成", policy.condition());
    assertEquals("投诉诊断任务流水号、执行状态、执行结果", policy.reportFormat());
  }

  @Test
  void missingReportSchemaOrWrongConditionTypeFailsClosed() {
    assertThrows(
        IllegalArgumentException.class,
        () -> NotificationPolicy.fromValidated(Map.of("订阅条件", "业务抢通完成")));
    assertThrows(
        IllegalArgumentException.class,
        () -> NotificationPolicy.fromValidated(Map.of("订阅条件", 1, "上报通知数据格式", "执行结果")));
  }
}
