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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.a2aproject.sdk.spec.AgentCard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads agent credentials from config, creates per-agent CredentialService, and builds
 * auth/extension interceptors from AgentCard security schemes.
 *
 * <p>Mirrors the Python SDK's {@code AgentAuthManager} + {@code AuthManager}.
 */
class AgentAuthManager {

    private static final Logger log = LoggerFactory.getLogger(AgentAuthManager.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final Map<String, Map<String, Map<String, Object>>> config;
    private final HttpClient credentialHttpClient;
    private final Map<String, AgentCredentialService> services = new ConcurrentHashMap<>();

    /** Create with a config map (agent name -> scheme name -> scheme config). */
    public AgentAuthManager(Map<String, Map<String, Map<String, Object>>> config) {
        this(config, null);
    }

    /** Create with a config map and an explicitly configured login HTTP client. */
    public AgentAuthManager(
            Map<String, Map<String, Map<String, Object>>> config,
            HttpClient credentialHttpClient) {
        this.config = config != null ? config : new HashMap<>();
        this.credentialHttpClient = credentialHttpClient;
        validateEncryptedCredentials(this.config);
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

    /** Create with no credentials (auth disabled). */
    public AgentAuthManager() {
        this(new HashMap<>());
    }

    private static Map<String, Map<String, Map<String, Object>>> loadFromFile(String path) {
        if (path == null) {
            return new HashMap<>();
        }
        try {
            Map<String, Map<String, Map<String, Object>>> loaded;
            if (path.startsWith("classpath:")) {
                String resource = path.substring("classpath:".length());
                java.io.InputStream is =
                        AgentAuthManager.class.getClassLoader().getResourceAsStream(resource);
                if (is == null) {
                    throw new IllegalStateException(
                            "Credentials classpath resource not found: " + resource);
                }
                loaded =
                        mapper.readValue(
                                is,
                                new TypeReference<
                                        Map<String, Map<String, Map<String, Object>>>>() {});
            } else {
                File file = new File(path);
                if (!file.exists()) {
                    throw new IllegalStateException("Credentials file not found: " + path);
                }
                loaded =
                        mapper.readValue(
                                file,
                                new TypeReference<
                                        Map<String, Map<String, Map<String, Object>>>>() {});
            }
            log.info("[Auth] Loaded credentials for {} agent(s) from {}", loaded.size(), path);
            return loaded;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to load credentials from " + path + ": " + e.getMessage(), e);
        }
    }

    private static List<String> extractExtensionUris(AgentCard agentCard) {
        List<String> uris = new ArrayList<>();
        var extensions = agentCard.capabilities().extensions();
        if (extensions == null) {
            return uris;
        }
        for (var ext : extensions) {
            String uri = ext.uri();
            if (!uri.isEmpty()) {
                uris.add(uri);
            }
        }
        return uris;
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
                    return new AgentCredentialService(name, agentCreds, credentialHttpClient);
                });
    }

    /** Get the raw config for an agent. */
    public Map<String, Map<String, Object>> getConfig(String agentName) {
        return config.get(agentName);
    }

    private static void validateEncryptedCredentials(Map<?, ?> values) {
        for (Object value : values.values()) {
            if (value instanceof Map<?, ?> nested) {
                validateEncryptedCredentials(nested);
            } else if (value instanceof String text && text.startsWith("enc:")) {
                CredentialCrypto.decryptIfNeeded(text);
            }
        }
    }

    /** Build the extension-header contributor for an AgentCard, if it declares extensions. */
    public ExtensionInterceptor buildExtensionInterceptor(AgentCard agentCard) {
        List<String> extUris = extractExtensionUris(agentCard);
        return extUris.isEmpty() ? null : new ExtensionInterceptor(extUris);
    }
}
