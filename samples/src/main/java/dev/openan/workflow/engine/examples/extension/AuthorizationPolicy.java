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
    for (String entry : numberedEntries(policyList)) {
      rules.add(Rule.parse(entry));
    }
    return new AuthorizationPolicy(operationType, rules);
  }

  /** Returns one of the four operation values defined by the SDK Authorization-T template. */
  public static String operationFromValidated(Map<String, Object> data) {
    Objects.requireNonNull(data, "Validated Authorization-T data is required");
    String operationType = requireText(data.get(OPERATION_TYPE_FIELD), OPERATION_TYPE_FIELD);
    if (!List.of(ADD, MODIFY, DELETE, QUERY).contains(operationType)) {
      throw new IllegalArgumentException("Unsupported Authorization-T operation: " + operationType);
    }
    return operationType;
  }

  /** Requires a non-empty policy selector/list for operations which need a target. */
  public static String requirePolicyList(Map<String, Object> data) {
    Objects.requireNonNull(data, "Validated Authorization-T data is required");
    return requireText(data.get(POLICY_LIST_FIELD), POLICY_LIST_FIELD);
  }

  /** Decodes the sample's single exact-id delete selector; conditions are not supported. */
  public static String policyIdSelector(Map<String, Object> data) {
    List<String> entries = numberedEntries(requirePolicyList(data));
    if (entries.size() != 1 || entries.get(0).contains("，")) {
      throw new IllegalArgumentException("The sample requires one numbered 策略标识是<UUID> selector");
    }
    return labeledValue(entries.get(0), "策略标识");
  }

  private static List<String> numberedEntries(String policyList) {
    String[] lines = policyList.strip().split("\\R", -1);
    List<String> entries = new ArrayList<>();
    for (int i = 0; i < lines.length; i++) {
      String prefix = (i + 1) + ".";
      String line = lines[i].strip();
      if (!line.startsWith(prefix)) {
        throw new IllegalArgumentException(
            "Authorization policies require consecutive numbered lines starting with 1.");
      }
      entries.add(requireText(line.substring(prefix.length()), POLICY_LIST_FIELD));
    }
    return entries;
  }

  private static String labeledValue(String field, String label) {
    String prefix = label + "是";
    if (!field.strip().startsWith(prefix)) {
      throw new IllegalArgumentException("Authorization field must use " + prefix + "<value>");
    }
    return requireText(field.strip().substring(prefix.length()), label);
  }

  private static String requireText(Object value, String field) {
    String text = value instanceof String string ? string.strip() : "";
    if (text.isEmpty()) {
      throw new IllegalArgumentException("Authorization field is required: " + field);
    }
    return text;
  }

  /** Exact whitelist match; no substring or fuzzy authorization is permitted. */
  public boolean authorizes(
      String businessScenario, String disposalType, String actionName, LocalDate onDate) {
    Objects.requireNonNull(onDate, "Authorization decision date is required");
    return rules.stream()
        .anyMatch(rule -> rule.authorizes(businessScenario, disposalType, actionName, onDate));
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
      String scenario = labeledValue(fields[0], "业务场景");
      String disposal = labeledValue(fields[1], "处置类型");
      String action = labeledValue(fields[2], "操作名称");
      String validity = labeledValue(fields[3], "有效期");
      if ("永久生效".equals(validity)) {
        return new Rule(scenario, disposal, action, LocalDate.MIN, LocalDate.MAX);
      }
      String[] range = validity.split("~", -1);
      if (range.length != 2) {
        throw new IllegalArgumentException(
            "Authorization validity must be start~end or 永久生效: " + validity);
      }
      try {
        return new Rule(scenario, disposal, action, parseDate(range[0]), parseDate(range[1]));
      } catch (DateTimeParseException e) {
        throw new IllegalArgumentException(
            "Authorization validity must use ISO dates: " + fields[3], e);
      }
    }

    private static LocalDate parseDate(String value) {
      return LocalDate.parse(value.strip());
    }

    private boolean authorizes(String scenario, String disposal, String action, LocalDate onDate) {
      return businessScenario.equals(scenario)
          && disposalType.equals(disposal)
          && actionName.equals(action)
          && !onDate.isBefore(validFrom)
          && !onDate.isAfter(validUntil);
    }
  }
}
