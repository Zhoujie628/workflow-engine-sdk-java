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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class CredentialCryptoTest {
  private static final String KEY =
      "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
  private static final String OTHER_KEY =
      "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789";

  @AfterEach
  void clearKey() {
    System.clearProperty("A2AT_CRED_KEY");
  }

  @Test
  void encryptedCredentialRoundTrips() {
    System.setProperty("A2AT_CRED_KEY", KEY);
    String encrypted = CredentialCrypto.encrypt("p@ss&中文");
    assertEquals("p@ss&中文", CredentialCrypto.decryptIfNeeded(encrypted));
  }

  @Test
  void explicitEncryptionKeyDoesNotMutateGlobalConfiguration() {
    System.setProperty("A2AT_CRED_KEY", OTHER_KEY);
    String encrypted = CredentialCrypto.encrypt("instance-secret", KEY);
    assertEquals("instance-secret", CredentialCrypto.decryptIfNeeded(encrypted, KEY));
    assertEquals(OTHER_KEY, System.getProperty("A2AT_CRED_KEY"));
  }

  @Test
  void corruptedEncryptedCredentialFailsClosed() {
    System.setProperty("A2AT_CRED_KEY", KEY);
    assertThrows(
        IllegalStateException.class,
        () -> CredentialCrypto.decryptIfNeeded("enc:not-base64:also-not-base64"));
  }

  @Test
  void plaintextRemainsBackwardCompatible() {
    assertEquals("plain", CredentialCrypto.decryptIfNeeded("plain"));
  }

  @Test
  void instanceScopedKeyTakesPrecedenceOverJvmProperty() {
    System.setProperty("A2AT_CRED_KEY", KEY);
    String encrypted = CredentialCrypto.encrypt("city-specific-secret");
    System.setProperty("A2AT_CRED_KEY", OTHER_KEY);

    assertEquals("city-specific-secret", CredentialCrypto.decryptIfNeeded(encrypted, KEY));
  }
}
