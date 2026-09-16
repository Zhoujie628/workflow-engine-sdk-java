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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.a2aproject.sdk.server.ServerCallContext;
import org.a2aproject.sdk.server.auth.AuthenticatedUser;
import org.a2aproject.sdk.server.auth.TaskOperation;
import org.a2aproject.sdk.spec.A2AError;
import org.a2aproject.sdk.spec.InvalidRequestError;
import org.junit.jupiter.api.Test;

/**
 * SecondaryAuthGuard four-step validation tests.
 *
 * <p>The {@link #md5Sign} / {@link #rsaSign} helpers double as the consumer/MSB-side signature
 * generation reference: signed string = app-id,timestamp,secret (comma-joined).
 */
class SecondaryAuthGuardTest {

  private static final String MSB_SECRET = "msb-assigned-secret";
  private static final String CONSUMER_ID = "waimo-app";
  private static final String CONSUMER_SECRET = "consumer-assigned-secret";

  static String md5Sign(String appId, String timestamp, String secret) throws Exception {
    byte[] digest =
        MessageDigest.getInstance("MD5")
            .digest((appId + "," + timestamp + "," + secret).getBytes(StandardCharsets.UTF_8));
    StringBuilder hex = new StringBuilder();
    for (byte b : digest) {
      hex.append(String.format("%02X", b));
    }
    return hex.toString().toUpperCase(Locale.ROOT);
  }

  static String rsaSign(PrivateKey privateKey, String appId, String timestamp, String secret)
      throws Exception {
    Signature signer = Signature.getInstance("SHA256withRSA");
    signer.initSign(privateKey);
    signer.update((appId + "," + timestamp + "," + secret).getBytes(StandardCharsets.UTF_8));
    byte[] signature = signer.sign();
    StringBuilder hex = new StringBuilder();
    for (byte b : signature) {
      hex.append(String.format("%02X", b));
    }
    return hex.toString().toUpperCase(Locale.ROOT);
  }

  static ServerCallContext contextWith(Map<String, String> headers) {
    Map<String, Object> state = new HashMap<>();
    state.put("headers", headers);
    return new ServerCallContext(new AuthenticatedUser("test"), state, Set.of());
  }

  static SecondaryAuthGuard guard(SecondaryAuthProperties properties) {
    return new SecondaryAuthGuard(properties);
  }

  static SecondaryAuthProperties properties() {
    SecondaryAuthProperties properties = new SecondaryAuthProperties();
    properties.getMsb().setSecret(MSB_SECRET);
    SecondaryAuthProperties.Credential consumer = new SecondaryAuthProperties.Credential();
    consumer.setSecret(CONSUMER_SECRET);
    properties.getConsumers().put(CONSUMER_ID, consumer);
    return properties;
  }

  private static A2AError reject(Map<String, String> headers) {
    SecondaryAuthGuard guard = guard(properties());
    return assertThrows(
        InvalidRequestError.class,
        () -> guard.checkCreate(contextWith(headers), TaskOperation.MESSAGE_SEND_STREAM));
  }

  @Test
  void missingHeadersRejected() {
    assertEquals("未携带合法签名头", reject(Map.of()).getMessage());
  }

  @Test
  void missingSignAndSignatureRejected() {
    assertEquals(
        "未携带合法签名头",
        reject(Map.of("app-id", CONSUMER_ID, "timestamp", String.valueOf(System.currentTimeMillis())))
            .getMessage());
  }

  @Test
  void malformedTimestampRejected() {
    assertEquals(
        "未携带合法签名头",
        reject(
                Map.of(
                    "app-id", CONSUMER_ID,
                    "timestamp", "not-a-number",
                    "signature", "whatever"))
            .getMessage());
  }

  @Test
  void unsupportedAlgorithmRejected() {
    assertEquals(
        "不支持的签名算法：HMAC",
        reject(
                Map.of(
                    "app-id", CONSUMER_ID,
                    "timestamp", String.valueOf(System.currentTimeMillis()),
                    "sign-alg", "HMAC",
                    "signature", "whatever"))
            .getMessage());
  }

  @Test
  void expiredTimestampRejected() {
    long signedAt = System.currentTimeMillis() - 6 * 60_000L;
    A2AError error =
        reject(
            Map.of(
                "app-id", CONSUMER_ID,
                "timestamp", String.valueOf(signedAt),
                "signature", "whatever"));
    assertTrue(error.getMessage().contains("签名过期"), error.getMessage());
    assertTrue(error.getMessage().contains("签名时间戳：" + signedAt), error.getMessage());
  }

  @Test
  void msbMd5SignPassesAllOperations() throws Exception {
    SecondaryAuthGuard guard = guard(properties());
    long now = System.currentTimeMillis();
    String sign = md5Sign("any-consumer", String.valueOf(now), MSB_SECRET);
    Map<String, String> headers =
        Map.of("app-id", "any-consumer", "timestamp", String.valueOf(now), "sign", sign);
    ServerCallContext ctx = contextWith(headers);

    assertTrue(guard.checkCreate(ctx, TaskOperation.MESSAGE_SEND_STREAM));
    assertTrue(guard.checkRead(ctx, "task-1", TaskOperation.GET_TASK));
    assertTrue(guard.checkWrite(ctx, "task-1", TaskOperation.CANCEL_TASK));
  }

  @Test
  void msbMd5WrongSecretRejected() throws Exception {
    long now = System.currentTimeMillis();
    String sign = md5Sign("any-consumer", String.valueOf(now), "wrong-secret");
    assertEquals(
        "签名认证失败",
        reject(
                Map.of(
                    "app-id", "any-consumer",
                    "timestamp", String.valueOf(now),
                    "sign", sign))
            .getMessage());
  }

  @Test
  void p2pMd5SignaturePasses() throws Exception {
    long now = System.currentTimeMillis();
    String signature = md5Sign(CONSUMER_ID, String.valueOf(now), CONSUMER_SECRET);
    SecondaryAuthGuard guard = guard(properties());
    assertTrue(
        guard.checkCreate(
            contextWith(
                Map.of(
                    "app-id", CONSUMER_ID,
                    "timestamp", String.valueOf(now),
                    "signature", signature)),
            TaskOperation.MESSAGE_SEND));
  }

  @Test
  void p2pUnknownAppIdRejected() throws Exception {
    long now = System.currentTimeMillis();
    String signature = md5Sign("unregistered-app", String.valueOf(now), CONSUMER_SECRET);
    assertEquals(
        "签名认证失败",
        reject(
                Map.of(
                    "app-id", "unregistered-app",
                    "timestamp", String.valueOf(now),
                    "signature", signature))
            .getMessage());
  }

  @Test
  void signTakesPrecedenceOverSignature() throws Exception {
    long now = System.currentTimeMillis();
    String validSign = md5Sign("app", String.valueOf(now), MSB_SECRET);
    SecondaryAuthGuard guard = guard(properties());
    assertTrue(
        guard.checkCreate(
            contextWith(
                Map.of(
                    "app-id", "app",
                    "timestamp", String.valueOf(now),
                    "sign", validSign,
                    "signature", "garbage")),
            TaskOperation.MESSAGE_SEND_STREAM));
  }

  @Test
  void p2pRsaSignaturePassesAndTamperedFails() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    KeyPair keyPair = generator.generateKeyPair();
    String publicKeyBase64 = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());

    SecondaryAuthProperties properties = properties();
    SecondaryAuthProperties.Credential consumer = properties.getConsumers().get(CONSUMER_ID);
    consumer.setSecret("shared-secret-in-signed-content");
    consumer.setPublicKey(publicKeyBase64);
    SecondaryAuthGuard guard = guard(properties);

    long now = System.currentTimeMillis();
    String timestamp = String.valueOf(now);
    String signature =
        rsaSign(keyPair.getPrivate(), CONSUMER_ID, timestamp, "shared-secret-in-signed-content");
    Map<String, String> headers =
        Map.of(
            "app-id", CONSUMER_ID,
            "timestamp", timestamp,
            "sign-alg", "SHA256WITHRSA",
            "signature", signature);
    assertTrue(guard.checkCreate(contextWith(headers), TaskOperation.MESSAGE_SEND_STREAM));

    String tamperedTimestamp = String.valueOf(now - 1000);
    assertEquals(
        "签名认证失败",
        assertThrows(
                InvalidRequestError.class,
                () ->
                    guard.checkCreate(
                        contextWith(
                            Map.of(
                                "app-id", CONSUMER_ID,
                                "timestamp", tamperedTimestamp,
                                "sign-alg", "SHA256WITHRSA",
                                "signature", signature)),
                        TaskOperation.MESSAGE_SEND_STREAM))
            .getMessage());
  }

  @Test
  void unprotectedOperationSkipsValidation() {
    // Only MESSAGE_SEND_STREAM is protected; other operations pass without auth headers.
    SecondaryAuthProperties properties = properties();
    properties.setProtectedOperations(Set.of(TaskOperation.MESSAGE_SEND_STREAM));
    SecondaryAuthGuard guard = guard(properties);

    // No auth headers at all — would be rejected if this operation were protected.
    assertTrue(guard.checkRead(contextWith(Map.of()), "task-1", TaskOperation.GET_TASK));
    assertTrue(guard.checkWrite(contextWith(Map.of()), "task-1", TaskOperation.CANCEL_TASK));
    assertTrue(guard.checkCreate(contextWith(Map.of()), TaskOperation.MESSAGE_SEND));

    // Protected operation still rejected without headers.
    assertEquals(
        "未携带合法签名头",
        assertThrows(
                InvalidRequestError.class,
                () ->
                    guard.checkCreate(
                        contextWith(Map.of()), TaskOperation.MESSAGE_SEND_STREAM))
            .getMessage());
  }

  @Test
  void emptyProtectedOperationsAuthenticatesAll() {
    // Default: empty set = all operations protected.
    SecondaryAuthProperties properties = properties();
    assertTrue(properties.protects(TaskOperation.MESSAGE_SEND_STREAM));
    assertTrue(properties.protects(TaskOperation.GET_TASK));
    assertTrue(properties.protects(TaskOperation.CANCEL_TASK));
    assertTrue(properties.protects(TaskOperation.SUBSCRIBE_TO_TASK));
  }
}
