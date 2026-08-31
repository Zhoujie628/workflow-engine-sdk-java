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

import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Configuration for DefaultWorkflowEngineClient.
 *
 * <p>Mirrors the Python SDK's {@code WorkflowEngineClient.__init__} parameters. Use the builder to
 * create an instance.
 */
@Getter
public class WorkflowEngineClientConfig {

    private final boolean sslVerify;
    private final String caCertsPath;
    private final String clientCertPath;
    private final String clientKeyPath;
    private final String clientKeyPassword;
    private final String crlPath;
    private final long sendTimeoutSeconds;
    private final long notificationAckTimeoutSeconds;
    private final int sendExecutorCoreSize;
    private final int sendExecutorMaxSize;
    private final int sendExecutorQueueCapacity;
    private final AuthProvider authProvider;
    private final String credentialsConfigPath;
    private final String credentialEncryptionKey;
    private final Map<String, Map<String, Map<String, Object>>> credentialsConfig;
    private final int maxNegotiationExchanges;
    private final String preferredProtocol;

    private WorkflowEngineClientConfig(Builder b) {
        this.sslVerify = b.sslVerify;
        this.caCertsPath = b.caCertsPath;
        this.clientCertPath = b.clientCertPath;
        this.clientKeyPath = b.clientKeyPath;
        this.clientKeyPassword = b.clientKeyPassword;
        this.crlPath = b.crlPath;
        this.sendTimeoutSeconds = b.sendTimeoutSeconds;
        this.notificationAckTimeoutSeconds = b.notificationAckTimeoutSeconds;
        this.sendExecutorCoreSize = b.sendExecutorCoreSize;
        this.sendExecutorMaxSize = b.sendExecutorMaxSize;
        this.sendExecutorQueueCapacity = b.sendExecutorQueueCapacity;
        this.authProvider = b.authProvider;
        this.credentialsConfigPath = b.credentialsConfigPath;
        this.credentialEncryptionKey = b.credentialEncryptionKey;
        this.credentialsConfig =
                b.credentialsConfig != null ? copyCredentials(b.credentialsConfig) : null;
        this.maxNegotiationExchanges = b.maxNegotiationExchanges;
        this.preferredProtocol = b.preferredProtocol;
    }

    private static Map<String, Map<String, Map<String, Object>>> copyCredentials(
            Map<String, Map<String, Map<String, Object>>> source) {
        Map<String, Map<String, Map<String, Object>>> top = new LinkedHashMap<>();
        source.forEach(
                (agent, schemes) -> {
                    Map<String, Map<String, Object>> schemeCopy = new LinkedHashMap<>();
                    if (schemes != null) {
                        schemes.forEach(
                                (scheme, values) ->
                                        schemeCopy.put(
                                                scheme,
                                                values != null
                                                        ? immutableObjectMap(values)
                                                        : Map.of()));
                    }
                    top.put(agent, Collections.unmodifiableMap(schemeCopy));
                });
        return Collections.unmodifiableMap(top);
    }

    private static Map<String, Object> immutableObjectMap(Map<String, Object> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(key, immutableValue(value)));
        return Collections.unmodifiableMap(copy);
    }

    private static Object immutableValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> copy = new LinkedHashMap<>();
            map.forEach((key, nested) -> copy.put(key, immutableValue(nested)));
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            list.forEach(item -> copy.add(immutableValue(item)));
            return Collections.unmodifiableList(copy);
        }
        return value;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private boolean sslVerify = true;
        private String caCertsPath = null;
        private String clientCertPath = null;
        private String clientKeyPath = null;
        private String clientKeyPassword = null;
        private String crlPath = null;
        private long sendTimeoutSeconds = 600;
        private long notificationAckTimeoutSeconds = 5;
        private int sendExecutorCoreSize = 4;
        private int sendExecutorMaxSize = 16;
        private int sendExecutorQueueCapacity = 256;
        private AuthProvider authProvider;
        private String credentialsConfigPath = null;
        private String credentialEncryptionKey;
        private Map<String, Map<String, Map<String, Object>>> credentialsConfig = null;
        private int maxNegotiationExchanges = 3;
        private String preferredProtocol = null;

        public Builder sslVerify(boolean v) {
            this.sslVerify = v;
            return this;
        }

        public Builder sendTimeoutSeconds(long v) {
            this.sendTimeoutSeconds = v;
            return this;
        }

        public Builder authProvider(AuthProvider v) {
            this.authProvider = v;
            return this;
        }

        public Builder caCertsPath(String v) {
            this.caCertsPath = v;
            return this;
        }

        public Builder clientCertPath(String v) {
            this.clientCertPath = v;
            return this;
        }

        public Builder clientKeyPath(String v) {
            this.clientKeyPath = v;
            return this;
        }

        public Builder clientKeyPassword(String v) {
            this.clientKeyPassword = v;
            return this;
        }

        public Builder crlPath(String v) {
            this.crlPath = v;
            return this;
        }

        public Builder notificationAckTimeoutSeconds(long v) {
            this.notificationAckTimeoutSeconds = v;
            return this;
        }

        public Builder sendExecutorCoreSize(int v) {
            this.sendExecutorCoreSize = v;
            return this;
        }

        public Builder sendExecutorMaxSize(int v) {
            this.sendExecutorMaxSize = v;
            return this;
        }

        public Builder sendExecutorQueueCapacity(int v) {
            this.sendExecutorQueueCapacity = v;
            return this;
        }

        /** Optional credential decryption key supplied by the host, not loaded from LLM config. */
        public Builder credentialEncryptionKey(String key) {
            this.credentialEncryptionKey = key;
            return this;
        }

        public Builder credentialsConfigPath(String v) {
            this.credentialsConfigPath = v;
            return this;
        }

        public Builder credentialsConfig(Map<String, Map<String, Map<String, Object>>> v) {
            this.credentialsConfig = v;
            return this;
        }

        public Builder maxNegotiationExchanges(int v) {
            this.maxNegotiationExchanges = v;
            return this;
        }

        public Builder preferredProtocol(String v) {
            this.preferredProtocol = v;
            return this;
        }

        public WorkflowEngineClientConfig build() {
            if (sendTimeoutSeconds <= 0 || notificationAckTimeoutSeconds <= 0) {
                throw new IllegalArgumentException("Timeouts must be positive");
            }
            if (sendExecutorCoreSize <= 0
                    || sendExecutorMaxSize < sendExecutorCoreSize
                    || sendExecutorQueueCapacity <= 0) {
                throw new IllegalArgumentException("Invalid send executor configuration");
            }
            if (maxNegotiationExchanges <= 0) {
                throw new IllegalArgumentException("maxNegotiationExchanges must be positive");
            }
            return new WorkflowEngineClientConfig(this);
        }
    }
}
