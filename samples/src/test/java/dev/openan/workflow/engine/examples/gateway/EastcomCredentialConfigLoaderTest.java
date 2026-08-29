/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.examples.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;

class EastcomCredentialConfigLoaderTest {

  @Test
  void nestedOverridesPreserveSharedRequestFields() {
    var credentials = EastcomCredentialConfigLoader.load("classpath:test-order-credentials.json");

    Map<String, Object> city1 = credentials.get("SPN Domain Agent City1").get("bearerAuth");
    Map<?, ?> requestFields = (Map<?, ?>) city1.get("request_fields");

    assertEquals("omc-user", requestFields.get("userName"));
    assertEquals("city1-password", requestFields.get("value"));
    assertEquals("password", requestFields.get("grantType"));
  }
}
