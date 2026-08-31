/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 *    Licensed under the Apache License, Version 2.0 (the License); you may
 *    not use this file except in compliance with the License. You may obtain
 *    a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an AS IS BASIS, WITHOUT
 *    WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *    License for the specific language governing permissions and limitations
 *    under the License.
 */

package dev.openan.workflow.engine.client;

import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.a2aproject.sdk.spec.AgentCard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads agent credentials from config, creates per-agent CredentialService, and builds
 * auth/extension interceptors from AgentCard security schemes.
 *
 * <p>Mirrors the Python SDK's {@code AgentAuthManager} + {@code AuthManager}.
 */
class AgentAuthManager {

  private static final Logger log = LoggerFactory.getLogger(AgentAuthManager.class);
  private final Map<String, Map<String, Map<String, Object>>> config;
  private final HttpClient credentialHttpClient;
  private final CredentialHttpTransport credentialHttpTransport;
  private final String credentialEncryptionKey;
  private final Map<String, AgentCredentialService> services = new ConcurrentHashMap<>();

  /** Create with a config map (agent name -> scheme name -> scheme config). */
  public AgentAuthManager(Map<String, Map<String, Map<String, Object>>> config) {
    this(config, null);
  }

  /** Create with a config map and an explicitly configured login HTTP client. */
  public AgentAuthManager(
      Map<String, Map<String, Map<String, Object>>> config, HttpClient credentialHttpClient) {
    this(config, credentialHttpClient, null);
  }

  AgentAuthManager(
      Map<String, Map<String, Map<String, Object>>> config,
      HttpClient credentialHttpClient,
      String credentialEncryptionKey) {
    this(config, credentialHttpClient, null, credentialEncryptionKey);
  }

  AgentAuthManager(
      Map<String, Map<String, Map<String, Object>>> config,
      CredentialHttpTransport credentialHttpTransport,
      String credentialEncryptionKey) {
    this(config, null, credentialHttpTransport, credentialEncryptionKey);
  }

  private AgentAuthManager(
      Map<String, Map<String, Map<String, Object>>> config,
      HttpClient credentialHttpClient,
      CredentialHttpTransport credentialHttpTransport,
      String credentialEncryptionKey) {
    this.config = config != null ? config : new HashMap<>();
    this.credentialHttpClient = credentialHttpClient;
    this.credentialHttpTransport = credentialHttpTransport;
    this.credentialEncryptionKey = credentialEncryptionKey;
    validateEncryptedCredentials(this.config, credentialEncryptionKey);
    if (!this.config.isEmpty()) {
      log.info(
          "[Auth] Loaded credentials for {} agent(s): {}",
          this.config.size(),
          new ArrayList<>(this.config.keySet()));
    }
  }

  /** Create by loading credentials from a JSON file. */
  public AgentAuthManager(String configPath) {
    this(loadFromFile(configPath), null);
  }

  /** Create from a JSON file and use the supplied client for login requests. */
  public AgentAuthManager(String configPath, HttpClient credentialHttpClient) {
    this(loadFromFile(configPath), credentialHttpClient);
  }

  AgentAuthManager(
      String configPath, HttpClient credentialHttpClient, String credentialEncryptionKey) {
    this(loadFromFile(configPath), credentialHttpClient, credentialEncryptionKey);
  }

  AgentAuthManager(
      String configPath,
      CredentialHttpTransport credentialHttpTransport,
      String credentialEncryptionKey) {
    this(loadFromFile(configPath), credentialHttpTransport, credentialEncryptionKey);
  }

  /** Create with no credentials (auth disabled). */
  public AgentAuthManager() {
    this(new HashMap<>());
  }

  private static Map<String, Map<String, Map<String, Object>>> loadFromFile(String path) {
    Map<String, Map<String, Map<String, Object>>> loaded = CredentialConfigLoader.load(path);
    log.info("[Auth] Loaded credentials for {} agent(s) from {}", loaded.size(), path);
    return loaded;
  }

  /** Get or create a credential service for the given agent. */
  public AgentCredentialService getService(String agentName) {
    return services.computeIfAbsent(
        agentName,
        name -> {
          Map<String, Map<String, Object>> agentCreds = config.get(name);
          if (agentCreds == null) {
            return null;
          }
          log.info("[Auth] Created credential service for agent: {}", name);
          return new AgentCredentialService(
              name,
              agentCreds,
              credentialHttpClient,
              credentialHttpTransport,
              credentialEncryptionKey);
        });
  }

  /** Get the raw config for an agent. */
  public Map<String, Map<String, Object>> getConfig(String agentName) {
    return config.get(agentName);
  }

  private static void validateEncryptedCredentials(
      Map<?, ?> values, String credentialEncryptionKey) {
    for (Object value : values.values()) {
      if (value instanceof Map<?, ?> nested) {
        validateEncryptedCredentials(nested, credentialEncryptionKey);
      } else if (value instanceof String text && text.startsWith("enc:")) {
        CredentialCrypto.decryptIfNeeded(text, credentialEncryptionKey);
      }
    }
  }

}
