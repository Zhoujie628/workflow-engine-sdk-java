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
package dev.openan.workflow.engine.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ProtocolLoggerTest {
  private static final String SETTING = "WORKFLOW_ENGINE_PROTOCOL_INCLUDE_SENSITIVE_HEADERS";

  @AfterEach
  void clearSetting() {
    System.clearProperty(SETTING);
  }

  @Test
  void sensitiveHeadersAreRedactedByDefault() {
    System.setProperty(SETTING, "false");
    String formatted =
        ProtocolLogger.formatHeaders(
            Map.of(
                "Authorization", "Bearer secret-token",
                "X-Request-Id", "request-1"));
    assertTrue(formatted.contains("Authorization: ***"));
    assertFalse(formatted.contains("secret-token"));
    assertTrue(formatted.contains("X-Request-Id: request-1"));
  }

  @Test
  void obsoleteSettingCannotDisableMandatoryRedaction() {
    System.setProperty(SETTING, "true");
    String formatted = ProtocolLogger.formatHeaders(Map.of("Authorization", "Bearer visible"));
    assertFalse(formatted.contains("Bearer visible"));
    assertTrue(formatted.contains("Authorization: ***"));
  }
}
