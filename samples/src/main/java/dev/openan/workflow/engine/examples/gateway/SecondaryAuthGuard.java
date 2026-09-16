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

import java.util.Map;
import org.a2aproject.sdk.server.ServerCallContext;
import org.a2aproject.sdk.server.auth.TaskAuthorizationProvider;
import org.a2aproject.sdk.server.auth.TaskOperation;
import org.a2aproject.sdk.spec.A2AError;
import org.a2aproject.sdk.spec.InvalidRequestError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Provider-side secondary-authentication guard, wired into the spring-boot-starter as a
 * {@link TaskAuthorizationProvider} bean. Every A2A operation (message send/stream, task query,
 * list, cancel, subscribe) runs the specification's four-step validation before the agent
 * executor.
 *
 * <p>Authentication headers are read from {@code ServerCallContext.getState().get("headers")} — the
 * starter's A2AController already places all request headers (lower-cased keys) into that map.
 * Requests forwarded via MSB carry a {@code sign} header (verified with
 * {@code secondary-auth.msb}); point-to-point requests carry a {@code signature} header (verified
 * with {@code secondary-auth.consumers[app-id]}).
 */
@Component
@ConditionalOnProperty(prefix = "secondary-auth", name = "enabled", havingValue = "true")
public class SecondaryAuthGuard implements TaskAuthorizationProvider {

  private static final Logger log = LoggerFactory.getLogger(SecondaryAuthGuard.class);
  private static final String ALG_MD5 = "MD5";
  private static final String ALG_RSA = "SHA256WITHRSA";

  private final SecondaryAuthProperties properties;

  public SecondaryAuthGuard(SecondaryAuthProperties properties) {
    this.properties = properties;
  }

  @Override
  public boolean checkCreate(ServerCallContext ctx, TaskOperation op) throws A2AError {
    validate(ctx, op);
    return true;
  }

  @Override
  public boolean checkRead(ServerCallContext ctx, String taskId, TaskOperation op) throws A2AError {
    validate(ctx, op);
    return true;
  }

  @Override
  public boolean checkWrite(ServerCallContext ctx, String taskId, TaskOperation op) throws A2AError {
    validate(ctx, op);
    return true;
  }

  @Override
  public boolean isTaskRecorded(String taskId) {
    return true;
  }

  @Override
  public void recordOwnership(ServerCallContext ctx, String taskId, TaskOperation op) {
    // Default: no ownership tracking. Override to maintain app-id -> taskId maps if task-level
    // visibility isolation is required.
  }

  private void validate(ServerCallContext ctx, TaskOperation op) throws A2AError {
    Map<String, String> headers = headersOf(ctx);
    String appId = headers.get("app-id");
    String timestamp = headers.get("timestamp");
    String signAlg = headers.getOrDefault("sign-alg", ALG_MD5);
    String sign = headers.get("sign");
    String signature = headers.get("signature");

    if (isBlank(appId) || isBlank(timestamp) || (isBlank(sign) && isBlank(signature))) {
      log.warn("[SecondaryAuth] rejected step1: missing headers, appId={}, operation={}", appId, op);
      throw new InvalidRequestError("未携带合法签名头");
    }
    long signedAt;
    try {
      signedAt = Long.parseLong(timestamp.trim());
    } catch (NumberFormatException e) {
      log.warn("[SecondaryAuth] rejected step1: bad timestamp, appId={}", appId);
      throw new InvalidRequestError("未携带合法签名头");
    }

    if (!ALG_MD5.equals(signAlg) && !ALG_RSA.equals(signAlg)) {
      log.warn("[SecondaryAuth] rejected step2: unsupported alg, appId={}, alg={}", appId, signAlg);
      throw new InvalidRequestError("不支持的签名算法：" + signAlg);
    }

    long now = System.currentTimeMillis();
    if (Math.abs(now - signedAt) > properties.getClockSkewMillis()) {
      log.warn("[SecondaryAuth] rejected step3: expired, appId={}, signedAt={}", appId, signedAt);
      throw new InvalidRequestError("签名过期，当前时间戳：" + now + "，签名时间戳：" + signedAt);
    }

    boolean viaMsb = !isBlank(sign);
    SecondaryAuthProperties.Credential credential =
        viaMsb ? properties.getMsb() : properties.getConsumers().get(appId.trim());
    String provided = viaMsb ? sign : signature;
    boolean verified = verify(credential, signAlg, appId.trim(), timestamp.trim(), provided);
    if (!verified) {
      log.warn(
          "[SecondaryAuth] rejected step4: signature mismatch, appId={}, alg={}, channel={}, operation={}",
          appId, signAlg, viaMsb ? "msb" : "p2p", op);
      throw new InvalidRequestError("签名认证失败");
    }
    log.info(
        "[SecondaryAuth] passed: appId={}, alg={}, channel={}, operation={}",
        appId, signAlg, viaMsb ? "msb" : "p2p", op);
  }

  private boolean verify(
      SecondaryAuthProperties.Credential credential,
      String signAlg,
      String appId,
      String timestamp,
      String provided) {
    if (credential == null || isBlank(credential.getSecret())) {
      return false;
    }
    if (ALG_MD5.equals(signAlg)) {
      return SignatureVerifier.verifyMd5(appId, timestamp, credential.getSecret(), provided);
    }
    return SignatureVerifier.verifyRsa(
        appId, timestamp, credential.getSecret(), credential.getPublicKey(), provided);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, String> headersOf(ServerCallContext ctx) {
    Object headers = ctx.getState().get("headers");
    if (headers instanceof Map) {
      return (Map<String, String>) headers;
    }
    return Map.of();
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
