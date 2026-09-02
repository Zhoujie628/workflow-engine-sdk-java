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
package dev.openan.workflow.engine.examples.workbench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkbenchAgentCatalogTest {

  @TempDir Path tempDir;

  @Test
  void loadsAnExternalAgentCard() throws Exception {
    Path externalCard = tempDir.resolve("city1.json");
    try (var input =
        getClass().getClassLoader().getResourceAsStream("agentcard/spn_domain_agent_city1.json")) {
      Files.copy(input, externalCard, StandardCopyOption.REPLACE_EXISTING);
    }

    var cards = new WorkbenchAgentCatalog(List.of(externalCard.toString())).load();

    assertEquals(1, cards.size());
    assertEquals("SPN Domain Agent City1", cards.get(0).name());
  }

  @Test
  void failsFastWhenAnExplicitCardDoesNotExist() {
    Path missing = tempDir.resolve("missing.json");

    assertThrows(
        IllegalStateException.class,
        () -> new WorkbenchAgentCatalog(List.of(missing.toString())).load());
  }
}
