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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.openan.workflow.engine.examples.util.EnvResolver;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import net.openan.a2at.sdk.core.model.A2ATConfig;
import net.openan.a2at.sdk.core.model.TemplateUri;

/** Loads the slot schema bundled with the active A2A-T SDK version. */
public final class SdkSlotSchemaLoader {
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private SdkSlotSchemaLoader() {}

  /** Loads a template's schema using the language configured for the sample SDK runtime. */
  public static Map<String, Object> loadConfigured(TemplateUri templateUri) {
    String language = "zh-CN";
    String envPath = EnvResolver.resolveEnvPath();
    if (envPath != null && !envPath.isBlank()) {
      language = A2ATConfig.load(Path.of(envPath)).prompt().language();
    }
    return load(templateUri, language);
  }

  /** Loads a template's schema for an explicit SDK resource language. */
  public static Map<String, Object> load(TemplateUri templateUri, String language) {
    Objects.requireNonNull(templateUri, "templateUri");
    if (language == null
        || language.isBlank()
        || language.contains("/")
        || language.contains("\\")) {
      throw new IllegalArgumentException("A2A-T prompt language must be a simple path segment");
    }
    String resourcePath =
        "/prompt_resources/slots/" + templateUri.uri() + "/" + language.strip() + "/slot.json";
    try (InputStream stream = SdkSlotSchemaLoader.class.getResourceAsStream(resourcePath)) {
      if (stream == null) {
        throw new IllegalStateException(
            "A2A-T SDK slot schema resource not found: " + resourcePath);
      }
      return MAPPER.readValue(stream, new TypeReference<Map<String, Object>>() {});
    } catch (IOException e) {
      throw new IllegalStateException("Failed to load A2A-T SDK slot schema: " + resourcePath, e);
    }
  }
}
