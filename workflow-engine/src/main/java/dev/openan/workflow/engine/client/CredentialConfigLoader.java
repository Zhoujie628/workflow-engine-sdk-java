/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/** Loads and resolves reusable per-agent authentication profiles. */
final class CredentialConfigLoader {
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final TypeReference<Map<String, Object>> ROOT_TYPE = new TypeReference<>() {};
  private static final TypeReference<Map<String, Map<String, Map<String, Object>>>> RESOLVED_TYPE =
      new TypeReference<>() {};

  private CredentialConfigLoader() {}

  /**
   * Loads either the original agent-keyed form or the profile form: {@code {"profiles": {...},
   * "agents": {"agent": {"profile": "name"}}}}.
   */
  static Map<String, Map<String, Map<String, Object>>> load(String path) {
    if (path == null || path.isBlank()) {
      return Map.of();
    }
    try {
      Map<String, Object> root = readRoot(path);
      if (root.containsKey("profiles") || root.containsKey("agents")) {
        return resolveProfiles(root);
      }
      return MAPPER.convertValue(root, RESOLVED_TYPE);
    } catch (Exception e) {
      throw new IllegalStateException(
          "Failed to load credentials from " + path + ": " + e.getMessage(), e);
    }
  }

  private static Map<String, Object> readRoot(String path) throws Exception {
    if (path.startsWith("classpath:")) {
      String resource = path.substring("classpath:".length());
      try (InputStream input =
          CredentialConfigLoader.class.getClassLoader().getResourceAsStream(resource)) {
        if (input == null) {
          throw new IllegalStateException("Credentials classpath resource not found: " + resource);
        }
        return MAPPER.readValue(input, ROOT_TYPE);
      }
    }
    File file = new File(path);
    if (!file.isFile()) {
      throw new IllegalStateException("Credentials file not found: " + path);
    }
    return MAPPER.readValue(file, ROOT_TYPE);
  }

  private static Map<String, Map<String, Map<String, Object>>> resolveProfiles(
      Map<String, Object> root) {
    Map<String, Map<String, Map<String, Object>>> profiles =
        convertProfiles(requiredMap(root.get("profiles"), "profiles"));
    Map<String, Object> agents = requiredMap(root.get("agents"), "agents");
    Map<String, Map<String, Map<String, Object>>> resolved = new LinkedHashMap<>();
    for (Map.Entry<String, Object> entry : agents.entrySet()) {
      Map<String, Object> binding = requiredMap(entry.getValue(), "agents." + entry.getKey());
      String profileName = requiredText(binding.get("profile"), "profile for " + entry.getKey());
      Map<String, Map<String, Object>> profile = profiles.get(profileName);
      if (profile == null) {
        throw new IllegalArgumentException(
            "Unknown credential profile '" + profileName + "' for agent " + entry.getKey());
      }
      Map<String, Map<String, Object>> agentConfig = deepCopy(profile);
      Object overridesValue = binding.get("overrides");
      if (overridesValue != null) {
        Map<String, Object> overrides =
            requiredMap(overridesValue, "agents." + entry.getKey() + ".overrides");
        for (Map.Entry<String, Object> override : overrides.entrySet()) {
          Map<String, Object> schemeConfig =
              agentConfig.computeIfAbsent(override.getKey(), ignored -> new LinkedHashMap<>());
          mergeInto(
              schemeConfig,
              requiredMap(
                  override.getValue(),
                  "agents." + entry.getKey() + ".overrides." + override.getKey()));
        }
      }
      resolved.put(entry.getKey(), agentConfig);
    }
    return resolved;
  }

  private static Map<String, Map<String, Map<String, Object>>> convertProfiles(
      Map<String, Object> rawProfiles) {
    Map<String, Map<String, Map<String, Object>>> result = new LinkedHashMap<>();
    for (Map.Entry<String, Object> entry : rawProfiles.entrySet()) {
      Map<String, Object> rawSchemes = requiredMap(entry.getValue(), "profiles." + entry.getKey());
      Map<String, Map<String, Object>> schemes = new LinkedHashMap<>();
      for (Map.Entry<String, Object> scheme : rawSchemes.entrySet()) {
        schemes.put(
            scheme.getKey(),
            new LinkedHashMap<>(
                requiredMap(
                    scheme.getValue(), "profiles." + entry.getKey() + "." + scheme.getKey())));
      }
      result.put(entry.getKey(), schemes);
    }
    return result;
  }

  private static Map<String, Map<String, Object>> deepCopy(
      Map<String, Map<String, Object>> source) {
    Map<String, Map<String, Object>> copy = new LinkedHashMap<>();
    source.forEach((name, config) -> copy.put(name, deepCopyMap(config)));
    return copy;
  }

  private static void mergeInto(Map<String, Object> target, Map<String, Object> overrides) {
    overrides.forEach(
        (key, value) -> {
          Object existing = target.get(key);
          if (existing instanceof Map<?, ?> existingMap && value instanceof Map<?, ?> overrideMap) {
            Map<String, Object> merged = deepCopyMap(requiredMap(existingMap, key));
            mergeInto(merged, requiredMap(overrideMap, key));
            target.put(key, merged);
          } else {
            target.put(key, deepCopyValue(value));
          }
        });
  }

  private static Map<String, Object> deepCopyMap(Map<String, Object> source) {
    Map<String, Object> copy = new LinkedHashMap<>();
    source.forEach((key, value) -> copy.put(key, deepCopyValue(value)));
    return copy;
  }

  private static Object deepCopyValue(Object value) {
    if (value instanceof Map<?, ?> map) {
      return deepCopyMap(requiredMap(map, "nested value"));
    }
    return value;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> requiredMap(Object value, String name) {
    if (!(value instanceof Map<?, ?> map)) {
      throw new IllegalArgumentException(name + " must be an object");
    }
    return (Map<String, Object>) map;
  }

  private static String requiredText(Object value, String name) {
    if (value == null || String.valueOf(value).isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return String.valueOf(value).trim();
  }
}
