/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.examples.extension;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Validated Authorization-T whitelist used by the SPN business callback.
 *
 * <p>The protocol prompt itself is deliberately not used for authorization decisions. Only the
 * fields returned by the A2A-T SDK validate-and-fill pipeline can create this value.
 */
public record AuthorizationPolicy(String operationType, List<Rule> rules) {

    public static final String OPERATION_TYPE_FIELD = "授权策略的操作类型";
    public static final String POLICY_LIST_FIELD = "动网操作的授权策略列表";

    public static final String ADD = "新增授权策略";
    public static final String MODIFY = "修改授权策略";
    public static final String DELETE = "删除授权策略";
    public static final String QUERY = "查询授权策略";

    public AuthorizationPolicy {
        operationType = requireText(operationType, OPERATION_TYPE_FIELD);
        if (!ADD.equals(operationType)) {
            throw new IllegalArgumentException("Authorization policy creation requires " + ADD);
        }
        rules = List.copyOf(Objects.requireNonNull(rules, "Authorization rules are required"));
        if (rules.isEmpty()) {
            throw new IllegalArgumentException("Authorization policy must contain at least one rule");
        }
    }

    /** Builds a strict policy from A2A-T SDK validated-and-filled fields. */
    public static AuthorizationPolicy fromValidated(Map<String, Object> data) {
        Objects.requireNonNull(data, "Validated Authorization-T data is required");
        String operationType = operationFromValidated(data);
        if (!ADD.equals(operationType)) {
            throw new IllegalArgumentException(
                    "Only add operations can create an active authorization policy");
        }
        String policyList = requireText(data.get(POLICY_LIST_FIELD), POLICY_LIST_FIELD);
        List<Rule> rules = new ArrayList<>();
        for (String entry : policyList.split("\uff1b", -1)) {
            if (!entry.isBlank()) {
                rules.add(Rule.parse(entry.strip()));
            }
        }
        return new AuthorizationPolicy(operationType, rules);
    }

    /** Returns one of the four operation values defined by the SDK Authorization-T template. */
    public static String operationFromValidated(Map<String, Object> data) {
        Objects.requireNonNull(data, "Validated Authorization-T data is required");
        String operationType = requireText(data.get(OPERATION_TYPE_FIELD), OPERATION_TYPE_FIELD);
        if (!List.of(ADD, MODIFY, DELETE, QUERY).contains(operationType)) {
            throw new IllegalArgumentException(
                    "Unsupported Authorization-T operation: " + operationType);
        }
        return operationType;
    }

    /** Requires a non-empty policy selector/list for operations which need a target. */
    public static String requirePolicyList(Map<String, Object> data) {
        Objects.requireNonNull(data, "Validated Authorization-T data is required");
        return requireText(data.get(POLICY_LIST_FIELD), POLICY_LIST_FIELD);
    }

    /** Exact whitelist match; no substring or fuzzy authorization is permitted. */
    public boolean authorizes(
            String businessScenario, String disposalType, String actionName, LocalDate onDate) {
        Objects.requireNonNull(onDate, "Authorization decision date is required");
        return rules.stream()
                .anyMatch(rule -> rule.authorizes(businessScenario, disposalType, actionName, onDate));
    }

    private static String requireText(Object value, String field) {
        String text = value instanceof String string ? string.strip() : "";
        if (text.isEmpty()) {
            throw new IllegalArgumentException("Authorization field is required: " + field);
        }
        return text;
    }

    /** One exact whitelist rule: scenario / disposal type / action / validity range. */
    public record Rule(
            String businessScenario,
            String disposalType,
            String actionName,
            LocalDate validFrom,
            LocalDate validUntil) {

        public Rule {
            businessScenario = requireText(businessScenario, "业务场景");
            disposalType = requireText(disposalType, "处置类型");
            actionName = requireText(actionName, "操作名称");
            Objects.requireNonNull(validFrom, "Authorization validFrom is required");
            Objects.requireNonNull(validUntil, "Authorization validUntil is required");
            if (validUntil.isBefore(validFrom)) {
                throw new IllegalArgumentException("Authorization validity end precedes start");
            }
        }

        private static Rule parse(String entry) {
            String[] fields = entry.split("\uff0c", -1);
            if (fields.length != 4) {
                throw new IllegalArgumentException(
                        "Authorization rule must contain the four fields defined by the current "
                                + "SDK, separated by full-width commas: "
                                + entry);
            }
            String validity = fields[3].strip();
            if ("永久生效".equals(validity)) {
                return new Rule(
                        fields[0].strip(),
                        fields[1].strip(),
                        fields[2].strip(),
                        LocalDate.MIN,
                        LocalDate.MAX);
            }
            String[] range = validity.split("[~\uff5e]", -1);
            if (range.length != 2) {
                throw new IllegalArgumentException(
                        "Authorization validity must be start~end or 永久生效: "
                                + validity);
            }
            try {
                return new Rule(
                        fields[0].strip(),
                        fields[1].strip(),
                        fields[2].strip(),
                        parseDate(range[0]),
                        parseDate(range[1]));
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException(
                        "Authorization validity must use ISO dates: "
                                + fields[3],
                        e);
            }
        }

        private static LocalDate parseDate(String value) {
            return LocalDate.parse(value.strip());
        }

        private boolean authorizes(
                String scenario, String disposal, String action, LocalDate onDate) {
            return businessScenario.equals(scenario)
                    && disposalType.equals(disposal)
                    && actionName.equals(action)
                    && !onDate.isBefore(validFrom)
                    && !onDate.isAfter(validUntil);
        }
    }
}
