/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 *    Licensed under the Apache License, Version 2.0 (the License); you may
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

package dev.openan.workflow.engine.examples.workbench;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.openan.workflow.engine.control.DefaultControlPoint;
import dev.openan.workflow.engine.examples.negotiation.NegotiationStrategy;
import dev.openan.workflow.engine.examples.util.EnvResolver;
import dev.openan.workflow.engine.examples.util.LlmHelper;
import dev.openan.workflow.engine.model.MessageContent;
import dev.openan.workflow.engine.model.NegotiationReply;
import dev.openan.workflow.engine.model.NegotiationRequest;
import dev.openan.workflow.engine.model.RouteDecision;
import dev.openan.workflow.engine.model.RouteRequest;
import dev.openan.workflow.engine.model.TaskRequest;
import dev.openan.workflow.engine.model.TaskResult;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ControlPoint for the SPN cross-city diagnosis workflow.
 *
 * <p>Handles task dispatch (with city-specific message enrichment), route decisions (fault-based
 * routing to recovery steps), and negotiation responses. Authorization-T and Notification-T are
 * initiated independently of the workflow, not handled here.
 *
 * <p>SRP: this class only contains workflow decision logic, separating it from the agent executor
 * that handles message I/O.
 */
public class WorkbenchControlPoint extends DefaultControlPoint {
  private static final Logger log = LoggerFactory.getLogger(WorkbenchControlPoint.class);
  private static final ObjectMapper JSON = new ObjectMapper();

  private final String a2atEnvPath;
  private final NegotiationStrategy negotiationStrategy;
  private final net.openan.a2at.sdk.client.A2ATClient contentClient;
  private final boolean demoNegotiationEnabled;

  public WorkbenchControlPoint() {
    this(null, null);
  }

  public WorkbenchControlPoint(String a2atEnvPath) {
    this(a2atEnvPath, null);
  }

  public WorkbenchControlPoint(String a2atEnvPath, NegotiationStrategy negotiationStrategy) {
    this(a2atEnvPath, negotiationStrategy, Boolean.getBoolean("a2at.samples.negotiation"));
  }

  /**
   * Explicit per-host demo setting; ordinary integrations do not enable fault injection by default.
   */
  public WorkbenchControlPoint(
      String a2atEnvPath, NegotiationStrategy negotiationStrategy, boolean demoNegotiationEnabled) {
    this.demoNegotiationEnabled = demoNegotiationEnabled;
    this.a2atEnvPath = a2atEnvPath != null ? a2atEnvPath : EnvResolver.resolveEnvPath();
    this.contentClient =
        dev.openan.workflow.engine.examples.util.A2ATInitialization.create(
            () ->
                new net.openan.a2at.sdk.client.A2ATClient(java.nio.file.Path.of(this.a2atEnvPath)));
    this.negotiationStrategy =
        negotiationStrategy != null
            ? negotiationStrategy
            : new NegotiationStrategy(this.a2atEnvPath);
  }

  private static String serialize(Object value) {
    try {
      return JSON.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Failed to serialize upstream agent results", e);
    }
  }

  @Override
  public CompletableFuture<MessageContent> onTask(TaskRequest request) {
    Map<String, Object> data =
        dev.openan.workflow.engine.examples.demo.SpnCasePrompts.complaintData(request);
    return CompletableFuture.supplyAsync(
        () ->
            dev.openan.workflow.engine.examples.util.BusinessSdkErrors.call(
                "task-generation",
                () -> {
                  var generated =
                      contentClient.generateTaskPromptFromDataWithSchema(
                          data,
                          dev.openan.workflow.engine.examples.demo.SpnCasePrompts
                              .privateLineComplaintSchema(),
                          net.openan.a2at.sdk.core.model.StandardTemplates
                              .PRIVATE_LINE_COMPLAINT_URI);
                  MessageContent content =
                      dev.openan.workflow.engine.client.A2atMessages.from(
                          generated,
                          List.of(new org.a2aproject.sdk.spec.TextPart(request.getInstruction())));
                  if (dev.openan.workflow.engine.examples.demo.SpnCasePrompts.injectNegotiation(
                      request.getStepName(), demoNegotiationEnabled)) {
                    // Explicit local-demo fault injection, not a production generation fallback.
                    Map<String, Object> metadata =
                        new java.util.LinkedHashMap<>(content.metadata());
                    metadata.put(
                        dev.openan.workflow.engine.client.A2ATExtension.TASK_T.uri(),
                        dev.openan.workflow.engine.examples.demo.SpnCasePrompts.withoutTaskObject(
                            generated
                                .buildMetadataContent()
                                .get(dev.openan.workflow.engine.client.A2ATExtension.TASK_T.uri())
                                .toString()));
                    log.info(
                        "[onTask] DEMO_NEGOTIATION agent={}, fault=missing-port, source=explicit-sample-switch",
                        request.getAgentName());
                    return new MessageContent(content.parts(), metadata, content.extensions());
                  }
                  return content;
                }));
  }

  @Override
  public CompletableFuture<TaskResult> onSelfTask(TaskRequest request) {
    String step = request.getStepName();
    log.info(
        "[onSelfTask] Self-loop step={}, agent={} (local merge, no A2A-T)",
        step,
        request.getAgentName());
    String upstreamAgentResults = serialize(request.getWorkflowInput());
    String prompt =
        "Current task:\n"
            + request.getInstruction()
            + "\n\nUpstream agent results:\n"
            + upstreamAgentResults;
    String fallback = "汇总分析（离线模式，保留原始诊断结果，不生成新结论）：\n" + upstreamAgentResults;
    String sys =
        "你是SPN跨城故障协同诊断汇总专家。仅依据两地市结果进行汇总，不补造网元和端口。"
            + "按1.诊断结果、2.诊断结果详情、3.修复建议输出；保留各地市实际结论，失败或证据不足时明确说明。"
            + "全文不超过450个中文字符，不使用表格，最后一句必须完整结束。";
    String result = LlmHelper.text(a2atEnvPath, sys, prompt, fallback);
    log.info("[onSelfTask] Merge result ({} chars): {}", result.length(), result);
    return CompletableFuture.completedFuture(
        TaskResult.builder().success(true).outputs(List.of(result)).build());
  }

  @Override
  public CompletableFuture<RouteDecision> onRoute(RouteRequest request) {
    // merge_analysis has an unconditional next -> endNode, so the executor
    // never calls onRoute for it. Recovery is self-triggered by SPN agents
    // via the active Authorization-T whitelist and reported through
    // the Notification-T channel. Just delegate to the default routing.
    return super.onRoute(request);
  }

  @Override
  public CompletableFuture<NegotiationReply> onNegotiation(NegotiationRequest request) {
    return negotiationStrategy.resolve(request);
  }
}
