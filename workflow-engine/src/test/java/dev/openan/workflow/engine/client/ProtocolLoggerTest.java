/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * SPDX-License-Identifier: Apache-2.0
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
