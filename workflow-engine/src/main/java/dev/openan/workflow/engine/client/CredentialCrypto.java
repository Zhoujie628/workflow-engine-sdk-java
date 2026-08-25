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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-GCM credential encryption/decryption utility.
 *
 * <p>Supports encrypted values in credential config files using the {@code enc:} prefix. The
 * encryption key is read from the {@code A2AT_CRED_KEY} environment variable (32-byte hex string).
 *
 * <p>Usage in credentials JSON:
 *
 * <pre>{@code
 * {
 *   "value": "enc:<base64-iv>:<base64-ciphertext>"
 * }
 * }</pre>
 *
 * <p>To generate an encrypted value, use the {@link #encrypt} method with the same key. Plaintext
 * values (no {@code enc:} prefix) are returned as-is for backward compatibility.
 */
public final class CredentialCrypto {

    private static final Logger log = LoggerFactory.getLogger(CredentialCrypto.class);
    private static final String ENV_KEY = "A2AT_CRED_KEY";
    private static final String PREFIX = "enc:";
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH = 128;
    private static final int IV_LENGTH = 12;

    private CredentialCrypto() {}

    /**
     * CLI entry point for encrypting a plaintext password.
     *
     * <p>Usage:
     * <pre>
     *   # Option 1: set env var, then encrypt
     *   set A2AT_CRED_KEY=0123456789abcdef...
     *   java -cp workflow-engine.jar dev.openan.workflow.engine.client.CredentialCrypto "Admin@123"
     *
     *   # Option 2: pass key as second argument
     *   java -cp workflow-engine.jar dev.openan.workflow.engine.client.CredentialCrypto "Admin@123" 0123456789abcdef...
     * </pre>
     *
     * <p>Output: the encrypted string (e.g. {@code enc:<iv>:<ciphertext>}) to stdout.
     * Copy this value into the credentials JSON file's {@code value} field.
     *
     * @param args {@code args[0]} = plaintext, {@code args[1]} = optional key-hex
     */
    public static void main(String[] args) {
        if (args.length == 0) {
            System.err.println("Usage: java -cp workflow-engine.jar dev.openan.workflow.engine.client.CredentialCrypto <plaintext> [key-hex]");
            System.err.println("  Set A2AT_CRED_KEY env var (32-byte hex) or pass as second argument.");
            System.exit(1);
        }
        String plaintext = args[0];
        if (args.length >= 2) {
            System.setProperty(ENV_KEY, args[1]);
        }
        try {
            String encrypted = encrypt(plaintext);
            System.out.println(encrypted);
        } catch (IllegalStateException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(1);
        }
    }

    /**
     * Decrypt a credential value if it has the {@code enc:} prefix. Values without the prefix are
     * returned as-is (plaintext fallback).
     *
     * @param value the raw value from config (may be {@code enc:...} or plaintext)
     * @return decrypted plaintext, or the original value if not encrypted
     * @throws IllegalStateException when an encrypted value cannot be decrypted
     */
    public static String decryptIfNeeded(String value) {
        return decryptIfNeeded(value, null);
    }

    /** Decrypts with an optional instance-scoped key read from a configured SDK env file. */
    static String decryptIfNeeded(String value, String configuredKeyHex) {
        if (value == null || !value.startsWith(PREFIX)) {
            return value;
        }
        String keyHex = resolveKey(configuredKeyHex);
        if (keyHex == null || keyHex.isBlank()) {
            throw new IllegalStateException(
                    "Encrypted credential found but "
                            + ENV_KEY
                            + " is not set (environment variable or system property)");
        }
        try {
            String encoded = value.substring(PREFIX.length());
            String[] parts = encoded.split(":", 2);
            if (parts.length != 2) {
                throw new IllegalArgumentException(
                        "Invalid encrypted credential format; expected enc:<iv>:<ciphertext>");
            }
            byte[] iv = Base64.getDecoder().decode(parts[0]);
            byte[] cipherText = Base64.getDecoder().decode(parts[1]);
            byte[] keyBytes = hexToBytes(keyHex);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    new SecretKeySpec(keyBytes, "AES"),
                    new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] plainBytes = cipher.doFinal(cipherText);
            return new String(plainBytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Credential decryption failed; verify " + ENV_KEY + " and encrypted value", e);
        }
    }

    /**
     * Encrypt a plaintext value using AES-GCM with the key from the {@code A2AT_CRED_KEY}
     * environment variable.
     *
     * @param plaintext the value to encrypt
     * @return encrypted string in format {@code enc:<base64-iv>:<base64-ciphertext>}
     * @throws IllegalStateException if the key env var is not set
     */
    public static String encrypt(String plaintext) {
        String keyHex = resolveKey();
        if (keyHex == null || keyHex.isBlank()) {
            throw new IllegalStateException(ENV_KEY + " environment variable not set");
        }
        try {
            byte[] keyBytes = hexToBytes(keyHex);
            byte[] iv = new byte[IV_LENGTH];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(
                    Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(keyBytes, "AES"),
                    new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] cipherText = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return PREFIX
                    + Base64.getEncoder().encodeToString(iv)
                    + ":"
                    + Base64.getEncoder().encodeToString(cipherText);
        } catch (Exception e) {
            throw new RuntimeException("Encryption failed: " + e.getMessage(), e);
        }
    }

    /**
     * Resolve the encryption key from OS environment variable first, then from system property (set
     * by {@code .env} file loader).
     *
     * @return the hex key string, or null if not found
     */
    private static String resolveKey() {
        return resolveKey(null);
    }

    private static String resolveKey(String configuredKeyHex) {
        if (configuredKeyHex != null && !configuredKeyHex.isBlank()) {
            return configuredKeyHex;
        }
        String key = System.getenv(ENV_KEY);
        if (key != null && !key.isBlank()) {
            return key;
        }
        key = System.getProperty(ENV_KEY);
        if (key != null && !key.isBlank()) {
            return key;
        }
        return null;
    }

    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        if (len != 64) {
            throw new IllegalArgumentException(
                    "A2AT_CRED_KEY must contain 64 hexadecimal characters");
        }
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            int high = Character.digit(hex.charAt(i), 16);
            int low = Character.digit(hex.charAt(i + 1), 16);
            if (high < 0 || low < 0) {
                throw new IllegalArgumentException(
                        "A2AT_CRED_KEY must contain hexadecimal characters only");
            }
            data[i / 2] = (byte) ((high << 4) + low);
        }
        return data;
    }
}
