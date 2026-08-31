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

package dev.openan.workflow.engine.examples.workbench;

import dev.openan.workflow.engine.client.A2AJavaClientRuntime;
import dev.openan.workflow.engine.client.A2ATransport;
import dev.openan.workflow.engine.client.DefaultWorkflowEngineClient;
import dev.openan.workflow.engine.client.WorkflowEngineClient;
import dev.openan.workflow.engine.client.WorkflowEngineClientConfig;
import dev.openan.workflow.engine.control.EventCallback;
import dev.openan.workflow.engine.control.EventType;
import dev.openan.workflow.engine.examples.negotiation.NegotiationStrategy;
import dev.openan.workflow.engine.model.ExecutionResult;
import dev.openan.workflow.engine.model.JumpCondition;
import dev.openan.workflow.engine.model.StepType;
import dev.openan.workflow.engine.model.Task;
import dev.openan.workflow.engine.model.Workflow;
import dev.openan.workflow.engine.model.WorkflowSearchResult;
import dev.openan.workflow.engine.model.WorkflowStep;
import dev.openan.workflow.engine.registry.LoadPsop;
import dev.openan.workflow.engine.runner.ExecutePsop;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.a2aproject.sdk.spec.AgentCard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Workflow orchestration for the SPN cross-city diagnosis.
 *
 * <p>Single responsibility: coordinate the full orchestration pipeline -- load agent cards,
 * search/load PSOP, create the task-scoped engine client, and run the workflow. Each sub-step
 * delegates to a dedicated collaborator:
 *
 * <ul>
 *   <li>{@link WorkbenchControlPoint} -- workflow decision callbacks (task dispatch, routing)
 *   <li>{@link NegotiationStrategy} -- typed negotiation decisions (injected into the control
 *       point)
 * </ul>
 *
 * <p>This class does NOT handle agent server I/O (that stays in the Spring-managed {@code
 * SpringWorkbenchExecutor}) or the details of any single A2A-T extension protocol.
 */
public class WorkbenchOrchestrator {

  private static final Logger log = LoggerFactory.getLogger(WorkbenchOrchestrator.class);
  private static final String FALLBACK_PSOP_ID = "psop_spn_cross_city_diagnosis";

  private final String orchUrl;
  private final String credentialsPath;
  private final boolean sslVerify;
  private final String a2atEnvPath;
  private final A2AJavaClientRuntime clientRuntime;

  public WorkbenchOrchestrator(
      String orchUrl, String credentialsPath, boolean sslVerify, String a2atEnvPath) {
    this(orchUrl, credentialsPath, sslVerify, a2atEnvPath, null);
  }

  public WorkbenchOrchestrator(
      String orchUrl,
      String credentialsPath,
      boolean sslVerify,
      String a2atEnvPath,
      A2AJavaClientRuntime clientRuntime) {
    this.orchUrl = orchUrl;
    this.credentialsPath = credentialsPath;
    this.sslVerify = sslVerify;
    this.a2atEnvPath = a2atEnvPath;
    this.clientRuntime = clientRuntime;
  }

  static String buildResultText(ExecutionResult result) {
    if (result.isSuccess()) {
      if (result.getHistory() != null) {
        for (int i = result.getHistory().size() - 1; i >= 0; i--) {
          Map<String, Object> last = result.getHistory().get(i);
          if (last.get("outputs") instanceof List<?> outputs && !outputs.isEmpty()) {
            Object step = last.get("step");
            return result.getHistory().stream()
                .filter(entry -> java.util.Objects.equals(step, entry.get("step")))
                .filter(entry -> entry.get("outputs") instanceof List<?>)
                .flatMap(entry -> ((List<?>) entry.get("outputs")).stream())
                .map(WorkbenchOrchestrator::renderOutput)
                .collect(java.util.stream.Collectors.joining("\n\n"));
          }
        }
      }
    }
    StringBuilder sb = new StringBuilder();
    sb.append("Workflow execution ")
        .append(result.isSuccess() ? "succeeded" : "failed")
        .append(".\n");
    if (result.getHistory() != null) {
      for (Map<String, Object> h : result.getHistory()) {
        sb.append("- Step: ")
            .append(h.get("step"))
            .append(", Agent: ")
            .append(h.get("agent"))
            .append(", Status: ")
            .append(h.get("status"))
            .append("\n");
      }
    }
    if (result.getError() != null) {
      sb.append("Error: ").append(result.getError());
    }
    return sb.toString();
  }

  private static String outputText(Map<String, Object> output) {
    if (output == null || output.isEmpty()) {
      return "";
    }
    return output.values().stream()
        .filter(value -> value != null && !String.valueOf(value).isBlank())
        .flatMap(value -> value instanceof List<?> values ? values.stream() : java.util.stream.Stream.of(value))
        .map(WorkbenchOrchestrator::renderOutput)
        .collect(java.util.stream.Collectors.joining("\n\n"));
  }

  private static String renderOutput(Object value) {
    if (value instanceof String text) return text;
    try {
      return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
    } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
      throw new IllegalArgumentException("Cannot serialize business output", error);
    }
  }

  static String buildResultText(ExecutionResult result, Workflow workflow) {
    if (result.isSuccess() && result.getStepOutputs() != null) {
      String terminalOutputs = workflow.getSteps().stream()
          .filter(step -> result.getStepOutputs().containsKey(step.getName()))
          .filter(step -> step.getNext() == null || step.getNext().stream()
              .noneMatch(next -> result.getStepOutputs().containsKey(next.getStep())))
          .map(step -> outputText(result.getStepOutputs().get(step.getName())))
          .filter(output -> !output.isBlank())
          .collect(java.util.stream.Collectors.joining("\n\n"));
      if (!terminalOutputs.isBlank()) return terminalOutputs;
    }
    return buildResultText(result);
  }

  static Workflow fallbackWorkflow() {
    WorkflowStep city1 =
        WorkflowStep.builder()
            .name("diagnosis_city1")
            .layer(0)
            .subtasks(
                List.of(
                    Task.builder()
                        .agent("SPN Domain Agent City1")
                        .description("SPN专线故障诊断")
                        .build()))
            .next(List.of(JumpCondition.builder().step("merge_analysis").condition("").build()))
            .build();
    WorkflowStep city2 =
        WorkflowStep.builder()
            .name("diagnosis_city2")
            .layer(0)
            .subtasks(
                List.of(
                    Task.builder()
                        .agent("SPN Domain Agent City2")
                        .description("SPN专线故障诊断")
                        .build()))
            .next(List.of(JumpCondition.builder().step("merge_analysis").condition("").build()))
            .build();
    WorkflowStep merge =
        WorkflowStep.builder()
            .name("merge_analysis")
            .layer(1)
            .stepType(StepType.SELF_LOOP)
            .contextFrom(List.of("diagnosis_city1", "diagnosis_city2"))
            .subtasks(
                List.of(Task.builder().agent("Workbench").description("汇总两地市OMC诊断结论").build()))
            .next(List.of(JumpCondition.builder().step("endNode").condition("").build()))
            .build();
    return Workflow.builder()
        .id(FALLBACK_PSOP_ID)
        .name("SPN跨地市专线投诉诊断")
        .description("两个地市并行诊断后由工作台本地汇总")
        .steps(List.of(city1, city2, merge))
        .build();
  }

  private static long elapsedMillis(long startedNanos) {
    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
  }

  /** Run the full orchestration pipeline and return the result text. */
  public String run(String messageText) throws Exception {
    return run(messageText, Boolean.getBoolean("a2at.samples.negotiation"));
  }

  /** Runs with an explicit sample-host negotiation setting, without changing global properties. */
  public String run(String messageText, boolean demoNegotiationEnabled) throws Exception {
    return run(messageText, demoNegotiationEnabled, new ExecutionCancellation());
  }

  String run(String messageText, boolean demoNegotiationEnabled, ExecutionCancellation cancellation)
      throws Exception {
    cancellation.check();
    long runStarted = System.nanoTime();
    String runtimeName =
        clientRuntime != null
            ? clientRuntime.getClass().getSimpleName()
            : "DefaultA2AJavaClientRuntime";
    log.info(
        "[Orchestrator] START orchUrl={}, runtime={}, sslVerify={}, inputChars={}",
        orchUrl,
        runtimeName,
        sslVerify,
        messageText != null ? messageText.length() : 0);

    long stageStarted = System.nanoTime();
    log.info("[Orchestrator] STAGE_START stage=load-agent-cards");
    List<AgentCard> agentCards = loadAgentCards();
    cancellation.check();
    log.info(
        "[Orchestrator] STAGE_DONE stage=load-agent-cards, count={}, agents={}, elapsedMs={}",
        agentCards.size(),
        agentCards.stream().map(AgentCard::name).toList(),
        elapsedMillis(stageStarted));

    stageStarted = System.nanoTime();
    log.info("[Orchestrator] STAGE_START stage=search-load-psop");
    ResolvedWorkflow resolvedWorkflow = resolveWorkflow(messageText);
    cancellation.check();
    String psopId = resolvedWorkflow.psopId();
    Workflow workflow = resolvedWorkflow.workflow();
    log.info(
        "[Orchestrator] STAGE_DONE stage=search-load-psop, psopId={}, workflow={}, "
            + "steps={}, elapsedMs={}",
        psopId,
        workflow.getName(),
        workflow.getSteps().size(),
        elapsedMillis(stageStarted));

    stageStarted = System.nanoTime();
    log.info("[Orchestrator] STAGE_START stage=create-engine-client, runtime={}", runtimeName);
    A2ATransport transport =
        new A2ATransport(
            agentCards,
            clientRuntime,
            WorkflowEngineClientConfig.builder()
                .sslVerify(sslVerify)
                .credentialsConfigPath(credentialsPath)
                .build());
    log.info(
        "[Orchestrator] STAGE_DONE stage=create-engine-client, contextId={}, elapsedMs={}",
        transport.getContextId(),
        elapsedMillis(stageStarted));
    try {
      WorkflowEngineClient engineClient = new DefaultWorkflowEngineClient(transport);

      stageStarted = System.nanoTime();
      log.info(
          "[Orchestrator] STAGE_START stage=execute-workflow, contextId={}, "
              + "psopId={}, workflow={}",
          transport.getContextId(),
          psopId,
          workflow.getName());
      WorkbenchControlPoint controlPoint =
          new WorkbenchControlPoint(
              a2atEnvPath, new NegotiationStrategy(a2atEnvPath), demoNegotiationEnabled);
      cancellation.check();
      CompletableFuture<ExecutionResult> execution =
          ExecutePsop.builder()
              .psop(workflow)
              .agentCards(agentCards)
              .controlPoint(controlPoint)
              .engineClient(engineClient)
              .runtimeIntent(messageText)
              .lang("zh")
              .sslVerify(sslVerify)
              .credentialsConfigPath(credentialsPath)
              .eventCallback(createLogCallback())
              .onFinish(
                  (r, events) -> {
                    log.info(
                        "[Orchestrator] WORKFLOW_FINISH success={}, history={}, events={}",
                        r.isSuccess(),
                        r.getHistory() != null ? r.getHistory().size() : 0,
                        events.size());
                    return CompletableFuture.completedFuture(null);
                  })
              .execute();
      cancellation.bind(execution);
      ExecutionResult result = execution.join();
      cancellation.check();
      log.info(
          "[Orchestrator] STAGE_DONE stage=execute-workflow, contextId={}, success={}, "
              + "history={}, elapsedMs={}",
          transport.getContextId(),
          result.isSuccess(),
          result.getHistory() != null ? result.getHistory().size() : 0,
          elapsedMillis(stageStarted));
      String resultText = buildResultText(result, workflow);
      log.info(
          "[Orchestrator] DONE contextId={}, success={}, resultChars={}, elapsedMs={}",
          transport.getContextId(),
          result.isSuccess(),
          resultText.length(),
          elapsedMillis(runStarted));
      if (!result.isSuccess()) {
        throw new IllegalStateException(resultText);
      }
      return resultText;
    } catch (Exception e) {
      log.error(
          "[Orchestrator] FAILED contextId={}, elapsedMs={}, errorType={}, message={}",
          transport.getContextId(),
          elapsedMillis(runStarted),
          e.getClass().getSimpleName(),
          e.getMessage(),
          e);
      throw e;
    } finally {
      log.info("[Orchestrator] TRANSPORT_CLOSE contextId={}", transport.getContextId());
      transport.close();
    }
  }

  private List<AgentCard> loadAgentCards() {
    return new WorkbenchAgentCatalog().load();
  }

  private ResolvedWorkflow resolveWorkflow(String messageText) {
    try {
      List<WorkflowSearchResult> results =
          LoadPsop.search(orchUrl, messageText, 3, null, sslVerify);
      if (!results.isEmpty()) {
        String psopId = results.get(0).getWorkflowId();
        log.info("[Orchestrator] Found PSOP: {} (score={})", psopId, results.get(0).getScore());
        return new ResolvedWorkflow(psopId, LoadPsop.load(orchUrl, psopId, null, sslVerify));
      }
      log.warn("[Orchestrator] PSOP search returned no candidates");
    } catch (Exception e) {
      log.warn("[Orchestrator] PSOP search failed, using fallback: {}", e.getMessage());
    }
    log.warn("[Orchestrator] PSOP_FALLBACK psopId={}", FALLBACK_PSOP_ID);
    return new ResolvedWorkflow(FALLBACK_PSOP_ID, fallbackWorkflow());
  }

  private EventCallback createLogCallback() {
    return new EventCallback() {
      @Override
      public void onEvent(String type, Map<String, Object> data) {
        switch (type) {
          case EventType.START -> log.info("  [START] {}", data.get("workflow"));
          case EventType.STEP_START -> log.info("  [STEP_START] {}", data.get("step"));
          case EventType.TASK_REQUEST -> log.info("  [TASK_REQUEST] agent={}", data.get("agent"));
          case EventType.TASK_RESPONSE -> log.info("  [TASK_RESPONSE] agent={}", data.get("agent"));
          case EventType.AGENT_STATUS_UPDATE ->
              log.info(
                  "  [STATUS_UPDATE] agent={}, state={}, final={}",
                  data.get("agent"),
                  data.get("state"),
                  data.get("is_final"));
          case EventType.AGENT_ARTIFACT_UPDATE ->
              log.info(
                  "  [ARTIFACT_UPDATE] agent={}, artifact={}",
                  data.get("agent"),
                  data.get("artifact_name"));
          case EventType.AGENT_MESSAGE_EVENT ->
              log.info(
                  "  [MESSAGE] agent={}, {} chars",
                  data.get("agent"),
                  data.get("text") != null ? ((String) data.get("text")).length() : 0);
          case EventType.STEP_COMPLETE -> log.info("  [STEP_COMPLETE] {}", data.get("step"));
          case EventType.ROUTE_DECISION ->
              log.info("  [ROUTE] {} -> {}", data.get("step"), data.get("next"));
          case EventType.COMPLETE -> log.info("  [COMPLETE]");
          case EventType.ERROR -> log.error("  [ERROR] {}", data.get("error"));
          case EventType.CLOSE -> log.info("  [CLOSE]");
          default -> {}
        }
      }
    };
  }

  private record ResolvedWorkflow(String psopId, Workflow workflow) {}
}
