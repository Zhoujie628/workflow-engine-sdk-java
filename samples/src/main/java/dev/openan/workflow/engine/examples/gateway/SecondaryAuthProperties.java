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
 *    distributed under the License is distributed on an AS IS BASIS, WITHOUT
 *    WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *    License for the specific language governing permissions and limitations
 *    under the License.
 */
package dev.openan.workflow.engine.examples.gateway;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.a2aproject.sdk.server.auth.TaskOperation;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Secondary authentication configuration (prefix {@code secondary-auth}).
 *
 * <p>Credentials are split into two groups:
 *
 * <ul>
 *   <li>{@code msb} — MSB-channel credential. Requests forwarded via MSB carry a {@code sign}
 *       header (MSB verifies the consumer, then re-signs using the secret/private key assigned to
 *       the provider); the provider verifies {@code sign} with this credential.
 *   <li>{@code consumers} — point-to-point consumer credentials, indexed by {@code app-id}.
 *       Point-to-point requests carry a {@code signature} header, verified with the matching
 *       consumer credential.
 * </ul>
 *
 * <p>The specification requires the provider to support both MD5 and SHA256WITHRSA; the signed
 * string is always {@code app-id,timestamp,secret} (comma-joined) for both algorithms, so
 * {@code secret} is mandatory; {@code publicKey} is only needed for RSA verification.
 *
 * <p>{@code protected-operations} controls which A2A operations are subject to secondary
 * authentication. Empty (default) means <b>all</b> operations are protected; list specific
 * operations to authenticate only those (e.g. {@code MESSAGE_SEND_STREAM} for streaming task
 * creation only, leaving task queries and push-notification config endpoints open).
 */
@ConfigurationProperties(prefix = "secondary-auth")
public class SecondaryAuthProperties {

  private boolean enabled = false;
  private long clockSkewMillis = 300_000L;
  private Set<TaskOperation> protectedOperations = Set.of();
  private Credential msb = new Credential();
  private Map<String, Credential> consumers = new LinkedHashMap<>();

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public long getClockSkewMillis() {
    return clockSkewMillis;
  }

  public void setClockSkewMillis(long clockSkewMillis) {
    this.clockSkewMillis = clockSkewMillis;
  }

  public Set<TaskOperation> getProtectedOperations() {
    return protectedOperations;
  }

  public void setProtectedOperations(Set<TaskOperation> protectedOperations) {
    this.protectedOperations =
        protectedOperations == null ? Set.of() : protectedOperations;
  }

  /** Whether the given operation should be authenticated. Empty set means all operations. */
  public boolean protects(TaskOperation operation) {
    return protectedOperations.isEmpty() || protectedOperations.contains(operation);
  }

  public Credential getMsb() {
    return msb;
  }

  public void setMsb(Credential msb) {
    this.msb = msb;
  }

  public Map<String, Credential> getConsumers() {
    return consumers;
  }

  public void setConsumers(Map<String, Credential> consumers) {
    this.consumers = consumers;
  }

  public static class Credential {
    private String secret;
    private String publicKey;

    public String getSecret() {
      return secret;
    }

    public void setSecret(String secret) {
      this.secret = secret;
    }

    public String getPublicKey() {
      return publicKey;
    }

    public void setPublicKey(String publicKey) {
      this.publicKey = publicKey;
    }
  }
}
