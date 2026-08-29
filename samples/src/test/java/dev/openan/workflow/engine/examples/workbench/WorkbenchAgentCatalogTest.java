/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.examples.workbench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkbenchAgentCatalogTest {

  @TempDir Path tempDir;

  @Test
  void loadsAgentCardFromAnExternalFileWithoutEditingBundledResources() throws Exception {
    Path bundled =
        Path.of(
            getClass()
                .getClassLoader()
                .getResource("agentcard/spn_domain_agent_city1.json")
                .toURI());
    Path external = tempDir.resolve("city1-agent-card.json");
    Files.writeString(
        external, Files.readString(bundled).replace("127.0.0.1:26335", "omc.example.test:443"));

    var cards = new WorkbenchAgentCatalog(List.of(external.toString())).load();

    assertEquals(1, cards.size());
    assertEquals(
        "https://omc.example.test:443/a2a/json", cards.get(0).supportedInterfaces().get(0).url());
  }

  @Test
  void explicitlyConfiguredMissingCardFailsFast() {
    Path missing = tempDir.resolve("missing-agent-card.json");

    assertThrows(
        IllegalStateException.class,
        () -> new WorkbenchAgentCatalog(List.of(missing.toString())).load());
  }
}
