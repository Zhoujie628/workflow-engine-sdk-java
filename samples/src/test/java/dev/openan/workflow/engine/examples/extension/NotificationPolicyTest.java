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
