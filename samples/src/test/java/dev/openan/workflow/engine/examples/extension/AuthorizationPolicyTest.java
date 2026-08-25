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

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

class AuthorizationPolicyTest {

    @Test
    void exactRuleAndValidityAuthorizeRecovery() {
        AuthorizationPolicy policy =
                AuthorizationPolicy.fromValidated(SpnCasePrompts.addAuthorizationData());

        assertTrue(
                policy.authorizes(
                        "业务投诉诊断", "业务抢通", "隧道调优", LocalDate.of(2026, 8, 25)));
        assertFalse(
                policy.authorizes(
                        "业务投诉诊断", "业务抢通", "光模块重启", LocalDate.of(2026, 8, 25)));
        assertFalse(
                policy.authorizes(
                        "业务投诉诊断", "业务抢通", "隧道调优", LocalDate.of(2031, 1, 1)));
    }

    @Test
    void nonMutatingOperationCannotCreateExecutableWhitelist() {
        Map<String, Object> data = new LinkedHashMap<>(SpnCasePrompts.addAuthorizationData());
        data.put(AuthorizationPolicy.OPERATION_TYPE_FIELD, "查询授权策略");

        assertThrows(IllegalArgumentException.class, () -> AuthorizationPolicy.fromValidated(data));
        assertEquals(
                AuthorizationPolicy.QUERY,
                AuthorizationPolicy.operationFromValidated(data));
    }

    @Test
    void sdkDefinedDeleteOperationAndSelectorAreAcceptedAsACommand() {
        Map<String, Object> data = Map.of(
                AuthorizationPolicy.OPERATION_TYPE_FIELD,
                AuthorizationPolicy.DELETE,
                AuthorizationPolicy.POLICY_LIST_FIELD,
                "7d8c7b00-3c8c-4f8e-9b1e-9b17b6a3e5c3");

        assertEquals(AuthorizationPolicy.DELETE, AuthorizationPolicy.operationFromValidated(data));
        assertEquals(
                "7d8c7b00-3c8c-4f8e-9b1e-9b17b6a3e5c3",
                AuthorizationPolicy.requirePolicyList(data));
    }

    @Test
    void malformedOrMissingRulesFailClosed() {
        assertThrows(
                IllegalArgumentException.class,
                () -> AuthorizationPolicy.fromValidated(Map.of(
                        AuthorizationPolicy.OPERATION_TYPE_FIELD, "新增授权策略",
                        AuthorizationPolicy.POLICY_LIST_FIELD, "业务抢通")));
        assertThrows(
                IllegalArgumentException.class,
                () -> AuthorizationPolicy.fromValidated(Map.of(
                        AuthorizationPolicy.OPERATION_TYPE_FIELD, "新增授权策略")));
    }

    @Test
    void sdkPermanentAndFullSevenFieldRulesAreSupported() {
        AuthorizationPolicy permanent = AuthorizationPolicy.fromValidated(Map.of(
                AuthorizationPolicy.OPERATION_TYPE_FIELD, AuthorizationPolicy.ADD,
                AuthorizationPolicy.POLICY_LIST_FIELD,
                "载波调度/业务抢通/载波调度/永久生效"));
        AuthorizationPolicy full = AuthorizationPolicy.fromValidated(Map.of(
                AuthorizationPolicy.OPERATION_TYPE_FIELD, AuthorizationPolicy.ADD,
                AuthorizationPolicy.POLICY_LIST_FIELD,
                "policy-1/业务投诉诊断/业务抢通/隧道调优/"
                        + "2026-06-01T12:00:00Z~2030-06-18T12:00:00Z/"
                        + "2026-06-01T12:00:00Z/2026-06-18T12:00:00Z"));

        assertTrue(permanent.authorizes(
                "载波调度", "业务抢通", "载波调度", LocalDate.of(2099, 1, 1)));
        assertTrue(full.authorizes(
                "业务投诉诊断", "业务抢通", "隧道调优", LocalDate.of(2028, 1, 1)));
    }

    @Test
    void sdkLimitedValidityWrapperIsSupported() {
        AuthorizationPolicy policy = AuthorizationPolicy.fromValidated(Map.of(
                AuthorizationPolicy.OPERATION_TYPE_FIELD, AuthorizationPolicy.ADD,
                AuthorizationPolicy.POLICY_LIST_FIELD,
                "业务投诉诊断/业务抢通/隧道调优/限期生效（2026-06-01~2030-06-18）"));

        assertTrue(policy.authorizes(
                "业务投诉诊断", "业务抢通", "隧道调优", LocalDate.of(2028, 1, 1)));
    }
}
