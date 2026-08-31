/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.examples.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.openan.workflow.engine.client.A2ATransport;
import dev.openan.workflow.engine.client.AgentCardJacksonModule;
import dev.openan.workflow.engine.client.DefaultWorkflowEngineClient;
import dev.openan.workflow.engine.client.WorkflowEngineClient;
import dev.openan.workflow.engine.client.WorkflowEngineClientConfig;
import dev.openan.workflow.engine.control.ControlPoint;
import dev.openan.workflow.engine.model.*;
import dev.openan.workflow.engine.registry.RegistryClient;
import dev.openan.workflow.engine.runner.ExecutePsop;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.a2aproject.sdk.spec.AgentCard;

/** Minimal plain-A2A host. SpringSpnDemo demonstrates host-owned A2A-T content generation. */
public final class HostQuickStart {
  private HostQuickStart() {}

  /** Arguments: registry URL, target agent name, credentials JSON path. */
  public static void main(String[] args) throws Exception {
    if (args.length != 3) {
      throw new IllegalArgumentException("Expected: registry-url agent-name credentials-json");
    }
    List<AgentCard> cards = loadCards(new RegistryClient(args[0], true));
    try (A2ATransport transport = new A2ATransport(cards, null,
        WorkflowEngineClientConfig.builder().sslVerify(true).credentialsConfigPath(args[2]).build())) {
      ExecutionResult result = execute(workflow(args[1]), cards, new DefaultWorkflowEngineClient(transport));
      if (!result.isSuccess()) throw new IllegalStateException(result.getError());
      System.out.println(result.getStepOutputs());
    }
  }

  /** Converts the registry's JSON maps to the A2A SDK's typed cards. */
  public static List<AgentCard> loadCards(RegistryClient registry) throws Exception {
    ObjectMapper mapper = new ObjectMapper().registerModule(new AgentCardJacksonModule());
    return registry.fetchAgentCards().stream()
        .map(card -> mapper.convertValue(card, AgentCard.class)).toList();
  }

  /** A remote task followed by local aggregation, with unconditional routing. */
  public static Workflow workflow(String agentName) {
    return Workflow.builder().name("host-quick-start").steps(List.of(
        WorkflowStep.builder().name("diagnose").layer(0)
            .subtasks(List.of(Task.builder().agent(agentName).description("Diagnose the supplied issue").build()))
            .next(List.of(JumpCondition.builder().step("aggregate").condition("").build())).build(),
        WorkflowStep.builder().name("aggregate").layer(1).stepType(StepType.SELF_LOOP)
            .subtasks(List.of(Task.builder().agent("host").description("Aggregate results").build()))
            .contextFrom(List.of("diagnose")).build())).build();
  }

  /** The caller owns client/transport; timeout and interruption stop further local dispatch. */
  public static ExecutionResult execute(Workflow workflow, List<AgentCard> cards, WorkflowEngineClient client)
      throws Exception {
    ControlPoint callbacks = ControlPoint.builder()
        .onTask(request -> CompletableFuture.completedFuture(MessageContent.text(request.getInstruction())))
        .onSelfTask(request -> CompletableFuture.completedFuture(TaskResult.success(List.of(
            Map.of("sourceResults", request.getWorkflowInput().upstreamResults())))))
        .onNegotiation(request -> CompletableFuture.completedFuture(
            new NegotiationReply.Stop("manual.required", "Host review required")))
        .build();
    CompletableFuture<ExecutionResult> execution = ExecutePsop.builder().psop(workflow).agentCards(cards)
        .engineClient(client).controlPoint(callbacks).runtimeIntent("Diagnose the supplied issue").execute();
    try {
      return execution.get(10, TimeUnit.MINUTES);
    } finally {
      if (!execution.isDone()) execution.cancel(true);
    }
  }
}
