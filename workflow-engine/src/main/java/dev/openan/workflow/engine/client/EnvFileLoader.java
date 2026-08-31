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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Explicit application-bootstrap utility that copies key-value pairs from a {@code .env} file to
 * system properties (only if not already present in the OS environment or JVM).
 *
 * <p>{@link A2ATransport} deliberately does not call this utility: SDK clients read their explicit
 * env path per instance, preserving channel isolation. Applications may call it once, before
 * creating any clients, only when a separate application component must read a value such as {@code
 * A2AT_CRED_KEY} through {@link System#getenv} or {@link System#getProperty}.
 */
public final class EnvFileLoader {

  private static final Logger log = LoggerFactory.getLogger(EnvFileLoader.class);

  private EnvFileLoader() {}

  /**
   * Parse a {@code .env} file and set each key as a system property, unless the key already exists
   * as an OS environment variable.
   *
   * @param envFilePath path to the {@code .env} file
   */
  public static void loadToSystemProperties(Path envFilePath) {
    Map<String, String> values = read(envFilePath);
    int count = 0;
    for (Map.Entry<String, String> entry : values.entrySet()) {
      String key = entry.getKey();
      if (System.getenv(key) != null || System.getProperty(key) != null) {
        continue;
      }
      System.setProperty(key, entry.getValue());
      count++;
    }
    if (count > 0) {
      log.info(
          "[EnvLoader] Loaded {} property(ies) from {} into system properties", count, envFilePath);
    }
  }

  /** Reads an env file without mutating process-global state. */
  static Map<String, String> read(Path envFilePath) {
    if (envFilePath == null) {
      return Map.of();
    }
    if (!Files.exists(envFilePath)) {
      log.debug("[EnvLoader] File not found: {}", envFilePath);
      return Map.of();
    }
    List<String> lines;
    try {
      lines = Files.readAllLines(envFilePath);
    } catch (IOException e) {
      log.warn("[EnvLoader] Failed to read {}: {}", envFilePath, e.getMessage());
      return Map.of();
    }
    Map<String, String> values = new LinkedHashMap<>();
    for (String line : lines) {
      String trimmed = line.trim();
      if (trimmed.isEmpty() || trimmed.startsWith("#")) {
        continue;
      }
      int eq = trimmed.indexOf('=');
      if (eq <= 0) {
        continue;
      }
      String key = trimmed.substring(0, eq).trim();
      String value = trimmed.substring(eq + 1).trim();
      if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
        value = value.substring(1, value.length() - 1);
      }
      values.putIfAbsent(key, value);
    }
    return java.util.Collections.unmodifiableMap(values);
  }
}
