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

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Signature verification utilities for the secondary-authentication specification.
 *
 * <p>The signed string is always {@code app-id,timestamp,secret} (comma-joined):
 *
 * <ul>
 *   <li>MD5: digest the signed string, hex-uppercase
 *   <li>SHA256WITHRSA: consumer/MSB signs with private key, hex-uppercase; provider verifies with
 *       public key
 * </ul>
 */
final class SignatureVerifier {

  private static final Logger log = LoggerFactory.getLogger(SignatureVerifier.class);

  private SignatureVerifier() {}

  static String signedContent(String appId, String timestamp, String secret) {
    return appId + "," + timestamp + "," + secret;
  }

  static boolean verifyMd5(String appId, String timestamp, String secret, String provided) {
    if (isBlank(provided) || isBlank(secret)) {
      return false;
    }
    String expected = md5HexUpper(signedContent(appId, timestamp, secret));
    String actual = provided.trim().toUpperCase(Locale.ROOT);
    return MessageDigest.isEqual(
        expected.getBytes(StandardCharsets.US_ASCII), actual.getBytes(StandardCharsets.US_ASCII));
  }

  static boolean verifyRsa(
      String appId, String timestamp, String secret, String publicKeyText, String provided) {
    if (isBlank(provided) || isBlank(secret) || isBlank(publicKeyText)) {
      return false;
    }
    try {
      PublicKey publicKey = decodePublicKey(publicKeyText);
      Signature verifier = Signature.getInstance("SHA256withRSA");
      verifier.initVerify(publicKey);
      verifier.update(signedContent(appId, timestamp, secret).getBytes(StandardCharsets.UTF_8));
      return verifier.verify(hexDecode(provided.trim()));
    } catch (Exception e) {
      log.warn("[SecondaryAuth] RSA verify error: {}", e.getMessage());
      return false;
    }
  }

  private static String md5HexUpper(String content) {
    try {
      byte[] digest = MessageDigest.getInstance("MD5").digest(content.getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder(digest.length * 2);
      for (byte b : digest) {
        hex.append(Character.forDigit((b >> 4) & 0xF, 16));
        hex.append(Character.forDigit(b & 0xF, 16));
      }
      return hex.toString().toUpperCase(Locale.ROOT);
    } catch (Exception e) {
      throw new IllegalStateException("MD5 unavailable", e);
    }
  }

  private static PublicKey decodePublicKey(String publicKeyText) throws Exception {
    String base64 =
        publicKeyText
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replaceAll("\\s", "");
    byte[] decoded = Base64.getDecoder().decode(base64);
    return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(decoded));
  }

  private static byte[] hexDecode(String hex) {
    if (hex.length() % 2 != 0) {
      throw new IllegalArgumentException("Signature hex length must be even");
    }
    byte[] bytes = new byte[hex.length() / 2];
    for (int i = 0; i < bytes.length; i++) {
      int high = Character.digit(hex.charAt(i * 2), 16);
      int low = Character.digit(hex.charAt(i * 2 + 1), 16);
      if (high < 0 || low < 0) {
        throw new IllegalArgumentException("Signature is not valid hex");
      }
      bytes[i] = (byte) ((high << 4) | low);
    }
    return bytes;
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
