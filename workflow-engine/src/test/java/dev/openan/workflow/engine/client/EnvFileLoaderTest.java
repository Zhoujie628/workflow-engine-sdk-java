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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EnvFileLoaderTest {
  private static final String ISOLATED_KEY = "A2AT_TEST_INSTANCE_SCOPED_KEY";

  @TempDir Path tempDirectory;

  @Test
  void readParsesValuesWithoutMutatingJvmProperties() throws Exception {
    Path envFile = tempDirectory.resolve("city-one.env");
    Files.writeString(
        envFile,
        "# city-specific configuration\n"
            + ISOLATED_KEY
            + "=city-one\n"
            + "QUOTED_VALUE=\"with spaces\"\n");
    System.clearProperty(ISOLATED_KEY);

    Map<String, String> values = EnvFileLoader.read(envFile);

    assertEquals("city-one", values.get(ISOLATED_KEY));
    assertEquals("with spaces", values.get("QUOTED_VALUE"));
    assertNull(System.getProperty(ISOLATED_KEY));
  }
}
