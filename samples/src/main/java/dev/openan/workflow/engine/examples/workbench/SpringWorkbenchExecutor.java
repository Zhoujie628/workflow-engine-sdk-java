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

import dev.openan.workflow.engine.client.A2ATExtension;
import dev.openan.workflow.engine.examples.agents.BaseAgentExecutor;
import dev.openan.workflow.engine.examples.config.WorkbenchClientProperties;
import dev.openan.workflow.engine.examples.util.EnvResolver;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.a2aproject.sdk.server.agentexecution.RequestContext;
import org.a2aproject.sdk.server.tasks.AgentEmitter;
import org.a2aproject.sdk.spec.A2AError;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.TextPart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Spring-managed Workbench AgentExecutor.
 *
 * <p>Receives a Task-T from the upper layer, delegates to {@link WorkbenchOrchestrator} for the
 * full pipeline, and returns the result. The A2A server container is provided by the Spring Boot
 * starter's auto-configuration.
 */
@Component
public class SpringWorkbenchExecutor extends BaseAgentExecutor {
  private static final Logger log = LoggerFactory.getLogger(SpringWorkbenchExecutor.class);

  private final WorkbenchClientProperties properties;
  private volatile WorkbenchTaskInputParser taskInputParser;
  private final java.util.concurrent.ConcurrentMap<String, ExecutionCancellation> executions =
      new java.util.concurrent.ConcurrentHashMap<>();

  public SpringWorkbenchExecutor(WorkbenchClientProperties properties) {
    this.properties = properties;
  }

  private static long elapsedMillis(long startedNanos) {
    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
  }

  private String resolveEnvPath() {
    if (properties.getA2atEnvPath() != null && !properties.getA2atEnvPath().isBlank()) {
      return properties.getA2atEnvPath();
    }
    return EnvResolver.resolveEnvPath();
  }

  private String resolveCredentialsPath() {
    if (properties.getCredentialsPath() != null && !properties.getCredentialsPath().isBlank()) {
      return properties.getCredentialsPath();
    }
    return getClass().getClassLoader().getResource("spn_agent_credentials.json") != null
        ? "classpath:spn_agent_credentials.json"
        : null;
  }

  @Override
  public void execute(RequestContext ctx, AgentEmitter emitter) throws A2AError {
    String taskId = ctx.getTaskId();
    String contextId = ctx.getContextId();
    String userText = extractText(ctx.getMessage());
    long started = System.nanoTime();
    ExecutionCancellation cancellation = new ExecutionCancellation();
    executions.put(taskId, cancellation);
    emitter.submit(buildStatusMessage(contextId, taskId, "Task received"));
    emitter.startWork(buildStatusMessage(contextId, taskId, "Processing"));

    try {
      WorkbenchTaskInputParser.ParsedTask taskInput =
          taskInputParser()
              .parse(userText, ctx.getMessage() != null ? ctx.getMessage().metadata() : Map.of());
      String input = taskInput.runtimeIntent();
      log.info(
          "[SpringWorkbench] TASK_START taskId={}, contextId={}, inputChars={}, "
              + "taskTemplate={}, taskParams={}, orchUrl={}, transportMode={}, "
              + "sslVerify={}",
          taskId,
          contextId,
          input.length(),
          taskInput.templateUri().uri(),
          taskInput.parameters().keySet(),
          properties.getOrchUrl(),
          "direct",
          properties.isSslVerify());
      String result =
          new WorkbenchOrchestrator(
                  properties.getOrchUrl(),
                  resolveCredentialsPath(),
                  properties.isSslVerify(),
                  resolveEnvPath())
              .run(input, properties.isDemoNegotiationEnabled(), cancellation);
      cancellation.check();
      Map<String, Object> metadata = new LinkedHashMap<>();
      metadata.put(A2ATExtension.TASK_T.uri(), result);
      List<Part<?>> parts = List.of(new TextPart(result));
      emitter.addArtifact(parts, "result", "cross-city-diagnosis-summary", metadata, false, true);
      emitter.complete(buildStatusMessage(contextId, taskId, "Completed"));
      log.info(
          "[SpringWorkbench] TASK_DONE taskId={}, contextId={}, resultChars={}, elapsedMs={}",
          taskId,
          contextId,
          result.length(),
          elapsedMillis(started));
    } catch (Exception e) {
      if (cancellation.isCancelled()) return;
      log.error(
          "[SpringWorkbench] TASK_FAILED taskId={}, contextId={}, elapsedMs={}, "
              + "errorType={}, message={}",
          taskId,
          contextId,
          elapsedMillis(started),
          e.getClass().getSimpleName(),
          e.getMessage(),
          e);
      emitter.fail(buildStatusMessage(contextId, taskId, "Failed: " + e.getMessage()));
    } finally {
      executions.remove(taskId, cancellation);
    }
  }

  private WorkbenchTaskInputParser taskInputParser() {
    WorkbenchTaskInputParser current = taskInputParser;
    if (current != null) {
      return current;
    }
    synchronized (this) {
      if (taskInputParser == null) {
        taskInputParser = WorkbenchTaskInputParser.fromEnv(resolveEnvPath());
      }
      return taskInputParser;
    }
  }

  @Override
  public void cancel(RequestContext ctx, AgentEmitter emitter) throws A2AError {
    ExecutionCancellation execution = executions.get(ctx.getTaskId());
    if (execution != null) execution.cancel();
    log.warn(
        "[SpringWorkbench] TASK_CANCEL taskId={}, contextId={}",
        ctx.getTaskId(),
        ctx.getContextId());
    emitter.cancel();
  }
}
