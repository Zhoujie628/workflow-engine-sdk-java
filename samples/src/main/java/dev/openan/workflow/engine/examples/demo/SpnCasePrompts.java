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

package dev.openan.workflow.engine.examples.demo;

import dev.openan.workflow.engine.client.A2ATExtension;
import dev.openan.workflow.engine.examples.extension.SdkSlotSchemaLoader;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.openan.a2at.sdk.core.model.StandardTemplates;

/**
 * Original business data for the SPN private-line complaint scenario.
 *
 * <p>Holds the raw structured fields (not the rendered prompt): the demo passes these to the host
 * business callbacks, which call the A2A-T SDK's FromDataWithSchema API. The SDK renders the Task-T
 * prompt from the data + schema. This bypasses scenario recognition but the SDK's schema-aware slot
 * extraction may invoke its configured LLM. Callers hand over business data, never a pre-rendered
 * protocol prompt.
 *
 * <p>The prompt-fixture variants (blank object / unknown port) for the protocol verification cases
 * are expressed as data too: the same schema with different values.
 */
public final class SpnCasePrompts {

  /** Standard Task-T extension URI. */
  public static final String TASK_T_URI = A2ATExtension.TASK_T.uri();

  /** Northbound user text accompanying the Task-T prompt (spec case 7.1). */
  public static final String TASK_TEXT = "创建专线业务投诉诊断任务";

  private SpnCasePrompts() {}

  /**
   * JSON schema for the private-line complaint data, mirroring the SDK's bundled slot schema
   * ({@code Task-T/network-layer/private-line-complaint/v1}): the slot fields "任务对象" (task
   * object) identify the line and "任务上下文" (task context) carry the complaint context.
   */
  public static Map<String, Object> privateLineComplaintSchema() {
    return SdkSlotSchemaLoader.loadConfigured(StandardTemplates.PRIVATE_LINE_COMPLAINT);
  }

  /** Required business fields returned by Task-T validation for this scenario. */
  @SuppressWarnings("unchecked")
  public static List<String> privateLineComplaintSchemaProperties() {
    Object required = privateLineComplaintSchema().get("required");
    if (!(required instanceof List<?> fields)
        || fields.stream().anyMatch(field -> !(field instanceof String))) {
      throw new IllegalStateException(
          "Current SDK private-line complaint schema has no string required list");
    }
    return (List<String>) fields;
  }

  /** Explicit sample data source, shared by initial task preparation and later negotiation. */
  public static Map<String, Object> complaintData(
      dev.openan.workflow.engine.model.TaskRequest request) {
    Object input = request.getInput() == null ? null : request.getInput().data();
    if (input != null) {
      if (!(input instanceof Map<?, ?>))
        throw new IllegalArgumentException("Complaint input must be an object");
      return new com.fasterxml.jackson.databind.ObjectMapper()
          .convertValue(
              input, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
    }
    if ("SPN Domain Agent City1".equals(request.getAgentName())
        || "diagnosis_city1".equals(request.getStepName())) return privateLineComplaintData();
    if ("SPN Domain Agent City2".equals(request.getAgentName())
        || "diagnosis_city2".equals(request.getStepName())) return privateLineComplaintDataCity2();
    throw new IllegalArgumentException("No complaint input configured for this sample task");
  }

  /** Well-formed complaint (spec case 7.1): known faulty port in City1. */
  public static Map<String, Object> privateLineComplaintData() {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("任务对象", "接入端口名称：P781-珠江新城-PTN7900-23-TPA1EG24-17");
    data.put(
        "任务上下文",
        "投诉分类：\"专线质差\"；问题发生时间：\"2026-05-11T08:21:46Z\"；"
            + "OSS侧事件流水号：\"event-id-20260511-09013\"；"
            + "投诉详情：\"从5月11号早上8点半开始，深圳访问广州的响应延迟从平均12ms骤升至320ms，"
            + "访问广州机房的核心交易系统非常慢。\"");
    return data;
  }

  /** Blank task-object variant (spec case 7.3): triggers blank-slot negotiation. */
  public static Map<String, Object> privateLineComplaintDataBlankObject() {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("任务对象", "");
    data.put(
        "任务上下文",
        "投诉分类：；问题发生时间：\"2026-05-11T08:21:46Z\"；" + "OSS侧事件流水号：\"event-id-20260511-09013\"");
    return data;
  }

  /** Protocol-case fixture intentionally carrying a blank required task object. */
  public static String privateLineComplaintPromptBlankObject() {
    return "## 任务类型(Task Type)\n传输专线业务投诉诊断\n\n"
        + "## 任务对象(Task Object)\n接入端口名称：\n\n"
        + "## 任务上下文(Task Context)\n投诉分类：专线质差；"
        + "问题发生时间：2026-05-11T08:21:46Z；"
        + "OSS侧事件流水号：event-id-20260511-09013";
  }

  /** Unknown-port variant (spec case 7.4): triggers the semantic-error negotiation path. */
  public static Map<String, Object> privateLineComplaintDataUnknownPort() {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("任务对象", "接入端口名称：P781-珠江新城-PTN7900-23-TPA1EG24-18");
    data.put(
        "任务上下文",
        "投诉分类：\"专线质差\"；问题发生时间：\"2026-05-11T08:21:46Z\"；" + "OSS侧事件流水号：\"event-id-20260511-09013\"");
    return data;
  }

  /**
   * City2-scoped complaint for the workbench's southbound dispatch: complete parameters (distinct
   * port in City2's range), no negotiation expected.
   */
  public static Map<String, Object> privateLineComplaintDataCity2() {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("任务对象", "接入端口名称：P882-珠江新城-PTN7900-23-TPA1EG24-11");
    data.put(
        "任务上下文",
        "投诉分类：\"专线质差\"；问题发生时间：\"2026-05-11T08:21:46Z\"；"
            + "OSS侧事件流水号：\"event-id-20260511-09013\"；"
            + "投诉详情：\"从5月11号早上8点半开始，深圳访问广州的响应延迟从平均12ms骤升至320ms，"
            + "访问广州机房的核心交易系统非常慢。\"");
    return data;
  }

  /** Task-T metadata map carrying the given prompt under the standard extension key. */
  public static Map<String, Object> taskTMetadata(String taskPrompt) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put(TASK_T_URI, taskPrompt);
    metadata.put(
        net.openan.a2at.sdk.core.model.MetadataContent.TEMPLATE_URI_METADATA_KEY,
        StandardTemplates.PRIVATE_LINE_COMPLAINT.uri());
    return metadata;
  }

  /** Explicit local fault injection; keep the original city complaint context unchanged. */
  public static String withoutTaskObject(String prompt) {
    int start = prompt.indexOf("## 任务对象");
    int end = prompt.indexOf("## 任务上下文", start);
    if (start < 0 || end < 0) throw new IllegalArgumentException("Missing Task-T sections");
    return prompt.substring(0, start) + "## 任务对象(Task Object)\n接入端口名称：\n\n" + prompt.substring(end);
  }

  /** Local Demo defaults to negotiation; external OMCs never receive automatic fault injection. */
  public static boolean demoNegotiationEnabled(boolean embeddedOmc) {
    String value = System.getProperty("a2at.samples.negotiation");
    if (value != null
        && !List.of("true", "false").contains(value.toLowerCase(java.util.Locale.ROOT)))
      throw new IllegalArgumentException("a2at.samples.negotiation must be true or false");
    boolean enabled = value == null ? embeddedOmc : Boolean.parseBoolean(value);
    if (enabled && !embeddedOmc)
      throw new IllegalArgumentException(
          "a2at.samples.negotiation is only allowed with local embedded OMCs");
    if (enabled)
      injectNegotiation("diagnosis_city1", true); // Validate city before starting servers.
    return enabled;
  }

  /** City selection applies only to this host instance's enabled demonstration. */
  public static boolean injectNegotiation(String stepName, boolean enabled) {
    if (!enabled) return false;
    String city = System.getProperty("a2at.samples.negotiation.city", "city1");
    if (!List.of("city1", "city2", "both").contains(city))
      throw new IllegalArgumentException(
          "a2at.samples.negotiation.city must be city1, city2 or both");
    return ("both".equals(city) && List.of("diagnosis_city1", "diagnosis_city2").contains(stepName))
        || ("diagnosis_" + city).equals(stepName);
  }

  // ------------------------------------------------------------------
  // Authorization-T (spec case 7.5: add authorization policy)
  // ------------------------------------------------------------------

  /**
   * Schema mirroring the SDK's Authorization-T slot schema: slot fields "授权策略的操作类型"
   * (operation type) and "策略列表" (policy list).
   */
  public static Map<String, Object> authorizationSchema() {
    return SdkSlotSchemaLoader.loadConfigured(StandardTemplates.AUTHORIZATION_POLICY_MANAGEMENT);
  }

  /** Add-authorization data (spec case 7.5): whitelist the tunnel-tuning recovery action. */
  public static Map<String, Object> addAuthorizationData() {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("授权策略的操作类型", "新增授权策略");
    data.put("动网操作的授权策略列表", "1. 业务场景是业务投诉诊断，处置类型是业务抢通，操作名称是隧道调优，有效期是2026-06-01~2030-06-18");
    return data;
  }

  // ------------------------------------------------------------------
  // Notification-T (spec case 7.8: subscribe service-recovery events)
  // ------------------------------------------------------------------

  /** Schema mirroring the SDK's Notification-T service-recovery slot schema. */
  public static Map<String, Object> serviceRecoverySchema() {
    return SdkSlotSchemaLoader.loadConfigured(StandardTemplates.SERVICE_RECOVERY);
  }

  /** Service-recovery subscription data (spec case 7.8). */
  public static Map<String, Object> subscribeServiceRecoveryData() {
    Map<String, Object> data = new LinkedHashMap<>();
    // The condition is optional in the SDK slot schema, but an empty value renders a bare section
    // header that real-LLM semantic validation rejects as a missing parameter; carry an explicit
    // constraint instead.
    data.put("订阅条件", "子网名称：SPN承载子网");
    data.put(
        "上报通知数据格式",
        "业务抢通事件数据包含：业务抢通方案执行状态（未启动、已结束）、投诉诊断任务流水号、"
            + "OSS侧事件流水号、接入端口名称、是否已授权OMC自动抢通（是、否）、"
            + "业务抢通方案名称、业务抢通方案详情、业务抢通方案执行结束时间、"
            + "业务抢通方案执行结果（成功、失败）及业务抢通方案执行失败原因（执行结果为失败时必填）。");
    return data;
  }
}
