/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.examples.extension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.openan.workflow.engine.examples.demo.SpnCasePrompts;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AuthorizationPolicyTest {

  @Test
  void exactRuleAndValidityAuthorizeRecovery() {
    AuthorizationPolicy policy =
        AuthorizationPolicy.fromValidated(SpnCasePrompts.addAuthorizationData());

    assertTrue(policy.authorizes("业务投诉诊断", "业务抢通", "隧道调优", LocalDate.of(2026, 8, 25)));
    assertFalse(policy.authorizes("业务投诉诊断", "业务抢通", "光模块重启", LocalDate.of(2026, 8, 25)));
    assertFalse(policy.authorizes("业务投诉诊断", "业务抢通", "隧道调优", LocalDate.of(2031, 1, 1)));
  }

  @Test
  void nonMutatingOperationCannotCreateExecutableWhitelist() {
    Map<String, Object> data = new LinkedHashMap<>(SpnCasePrompts.addAuthorizationData());
    data.put(AuthorizationPolicy.OPERATION_TYPE_FIELD, "查询授权策略");

    assertThrows(IllegalArgumentException.class, () -> AuthorizationPolicy.fromValidated(data));
    assertEquals(AuthorizationPolicy.QUERY, AuthorizationPolicy.operationFromValidated(data));
  }

  @Test
  void sdkDefinedDeleteOperationAndSelectorAreAcceptedAsACommand() {
    Map<String, Object> data =
        Map.of(
            AuthorizationPolicy.OPERATION_TYPE_FIELD,
            AuthorizationPolicy.DELETE,
            AuthorizationPolicy.POLICY_LIST_FIELD,
            "1. 策略标识是7d8c7b00-3c8c-4f8e-9b1e-9b17b6a3e5c3");

    assertEquals(AuthorizationPolicy.DELETE, AuthorizationPolicy.operationFromValidated(data));
    assertEquals(
        "7d8c7b00-3c8c-4f8e-9b1e-9b17b6a3e5c3", AuthorizationPolicy.policyIdSelector(data));
  }

  @Test
  void malformedOrMissingRulesFailClosed() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            AuthorizationPolicy.fromValidated(
                Map.of(
                    AuthorizationPolicy.OPERATION_TYPE_FIELD, "新增授权策略",
                    AuthorizationPolicy.POLICY_LIST_FIELD, "业务抢通")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            AuthorizationPolicy.fromValidated(
                Map.of(AuthorizationPolicy.OPERATION_TYPE_FIELD, "新增授权策略")));
  }

  @Test
  void sdkCanonicalPermanentAndLimitedRulesAreSupported() {
    AuthorizationPolicy permanent =
        AuthorizationPolicy.fromValidated(
            Map.of(
                AuthorizationPolicy.OPERATION_TYPE_FIELD,
                AuthorizationPolicy.ADD,
                AuthorizationPolicy.POLICY_LIST_FIELD,
                "1. 业务场景是载波调度，处置类型是业务抢通，操作名称是载波调度，有效期是永久生效"));
    AuthorizationPolicy limited =
        AuthorizationPolicy.fromValidated(
            Map.of(
                AuthorizationPolicy.OPERATION_TYPE_FIELD,
                AuthorizationPolicy.ADD,
                AuthorizationPolicy.POLICY_LIST_FIELD,
                "1. 业务场景是业务投诉诊断，处置类型是业务抢通，操作名称是隧道调优，有效期是2026-06-01~2030-06-18"));

    assertTrue(permanent.authorizes("载波调度", "业务抢通", "载波调度", LocalDate.of(2099, 1, 1)));
    assertTrue(limited.authorizes("业务投诉诊断", "业务抢通", "隧道调优", LocalDate.of(2028, 1, 1)));
  }

  @Test
  void oldSlashSeparatedRuleIsRejected() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            AuthorizationPolicy.fromValidated(
                Map.of(
                    AuthorizationPolicy.OPERATION_TYPE_FIELD,
                    AuthorizationPolicy.ADD,
                    AuthorizationPolicy.POLICY_LIST_FIELD,
                    "业务投诉诊断/业务抢通/隧道调优/2026-06-01~2030-06-18")));
  }

  @Test
  void actualSdkBundledExampleSupportsMultipleNumberedRules() {
    Map<String, Object> schema = SpnCasePrompts.authorizationSchema();
    Map<?, ?> properties = (Map<?, ?>) schema.get("properties");
    Map<?, ?> listSchema = (Map<?, ?>) properties.get(AuthorizationPolicy.POLICY_LIST_FIELD);
    String example = (String) ((java.util.List<?>) listSchema.get("examples")).get(0);
    AuthorizationPolicy policy = policy(example);
    assertEquals(2, policy.rules().size());
    assertTrue(policy.authorizes("校园专网", "紧急扩容", "天线调整", LocalDate.of(2026, 8, 1)));
    assertTrue(policy.authorizes("校园专网", "紧急扩容", "天线调整", LocalDate.of(2030, 12, 31)));
    assertFalse(policy.authorizes("校园专网", "紧急扩容", "天线调整", LocalDate.of(2026, 7, 31)));
    assertTrue(policy.authorizes("视频保障", "故障切换", "频谱重耕", LocalDate.of(2099, 1, 1)));
    assertFalse(policy.authorizes("视频保障", "紧急扩容", "频谱重耕", LocalDate.of(2026, 8, 31)));
    assertEquals(policy.rules(), policy(example.replace("\n", "\r\n")).rules());
  }

  @Test
  void malformedCanonicalValuesCannotCreateAnAuthorization() {
    String canonical =
        SpnCasePrompts.addAuthorizationData().get(AuthorizationPolicy.POLICY_LIST_FIELD).toString();
    for (String invalid :
        java.util.List.of(
            canonical.replace("1.", "2."),
            canonical.replace("，处置类型是业务抢通", ""),
            canonical.replace("操作名称是隧道调优", "操作名称是"),
            canonical.replace("业务场景是", "业务场景："),
            canonical.replace("2026-06-01", "2026-02-30"),
            canonical.replace("2030-06-18", "2025-06-18"),
            canonical + "；" + canonical.replace("1.", "2."),
            "业务投诉诊断，业务抢通，隧道调优，2026-06-01~2030-06-18")) {
      assertThrows(IllegalArgumentException.class, () -> policy(invalid), invalid);
    }
  }

  private AuthorizationPolicy policy(String rules) {
    return AuthorizationPolicy.fromValidated(
        Map.of(
            AuthorizationPolicy.OPERATION_TYPE_FIELD,
            AuthorizationPolicy.ADD,
            AuthorizationPolicy.POLICY_LIST_FIELD,
            rules));
  }

  @Test
  void currentModifyShapeIsNotMisreadAsAnAddRule() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            AuthorizationPolicy.fromValidated(
                Map.of(
                    AuthorizationPolicy.OPERATION_TYPE_FIELD,
                    AuthorizationPolicy.MODIFY,
                    AuthorizationPolicy.POLICY_LIST_FIELD,
                    "1. 策略标识是7d8c7b00-3c8c-4f8e-9b1e-9b17b6a3e5c3，有效期是永久生效")));
  }
}
