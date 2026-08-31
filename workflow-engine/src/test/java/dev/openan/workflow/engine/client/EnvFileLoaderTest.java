/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * SPDX-License-Identifier: Apache-2.0
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
