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

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.openan.workflow.engine.client.A2ATransport;
import dev.openan.workflow.engine.client.AgentCardJacksonModule;
import dev.openan.workflow.engine.client.DefaultWorkflowEngineClient;
import dev.openan.workflow.engine.client.WorkflowEngineClientConfig;
import dev.openan.workflow.engine.examples.SpringWorkbenchApplication;
import dev.openan.workflow.engine.examples.agents.SpnDomainAgentCity1Executor;
import dev.openan.workflow.engine.examples.agents.SpnDomainAgentCity2Executor;
import dev.openan.workflow.engine.examples.gateway.MockGatewayServer;
import dev.openan.workflow.engine.examples.server.OmcAgentLauncher;
import dev.openan.workflow.engine.examples.util.EnvResolver;
import dev.openan.workflow.engine.examples.workbench.SpringWorkbenchExtensionLifecycle;
import dev.openan.workflow.engine.model.SendMessageResult;
import dev.openan.workflow.engine.spring.A2AController;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.a2aproject.sdk.spec.AgentCard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Demo entry point for the SPN cross-city diagnosis.
 *
 * <p>The Workbench Agent runs as a Spring Boot service. The demo orchestrates:
 *
 * <ol>
 *   <li>Start Spring Boot Workbench Agent (A2A server on port 26337)
 *   <li>Start OMC agents (embedded servers on ports 26335, 26336)
 *   <li>Send a Task-T message to the Workbench Agent via the SDK client
 *   <li>Print the response and shut everything down
 * </ol>
 *
 * <p>Demonstrates heterogeneous architecture: Workbench (Spring Boot) and OMC (JDK HttpServer)
 * communicate via the unified A2A-T protocol. Transport modes ({@code --a2a.transport-mode}):
 * {@code direct} (HTTP+JSON to OMC), {@code mock} (HTTP reverse proxy through a mock instruction
 * center), {@code order} (Eastcom Order SDK forwarding; with the bundled protocol simulator when
 * {@code EASTCOM_ORDER_SIMULATOR_ENABLED=true}).
 */
public class SpringSpnDemo {
  private static final Logger log = LoggerFactory.getLogger(SpringSpnDemo.class);
  private static final ObjectMapper mapper =
      new ObjectMapper().registerModule(new AgentCardJacksonModule());

  private static final String WB_AGENT_NAME = "Transport Workbench Agent";
  private static final long STARTUP_WAIT = 3;

  private final OmcAgentLauncher omc = new OmcAgentLauncher();
  private MockGatewayServer gateway;

  public static void main(String[] args) throws Exception {
    SpringWorkbenchApplication.loadDotEnv();
    new SpringSpnDemo().run(args);
  }

  public void run(String... applicationArgs) throws Exception {
    long demoStarted = System.nanoTime();
    ConfigurableApplicationContext ctx = null;
    boolean success = false;
    String transportMode = resolveTransportMode(applicationArgs);
    boolean embeddedOmc = resolveEmbeddedOmcEnabled(applicationArgs, transportMode);
    // The HTTP mock gateway only serves the adapter-level mock mode. The order mode
    // talks to the real instruction platform (or the bundled protocol simulator on
    // 26401 when EASTCOM_ORDER_SIMULATOR_ENABLED=true) and never routes via 26400.
    boolean mockMode = "mock".equalsIgnoreCase(transportMode);
    log.info(
        "[Demo] START mode={}, embeddedOmc={}, workbench=https://127.0.0.1:26337/a2a/json",
        transportMode,
        embeddedOmc);
    try {
      long stageStarted;
      if (mockMode) {
        stageStarted = System.nanoTime();
        log.info("[Demo] STAGE_START stage=start-mock-gateway");
        gateway =
            new MockGatewayServer(
                "127.0.0.1", 26400, Set.of("https://127.0.0.1:26335", "https://127.0.0.1:26336"));
        gateway.start();
        log.info(
            "[Demo] STAGE_DONE stage=start-mock-gateway, elapsedMs={}",
            elapsedMillis(stageStarted));
      }

      if (embeddedOmc) {
        stageStarted = System.nanoTime();
        log.info("[Demo] STAGE_START stage=start-omc-agents");
        omc.startFromResource(
            "agentcard/spn_domain_agent_city1.json", new SpnDomainAgentCity1Executor());
        omc.startFromResource(
            "agentcard/spn_domain_agent_city2.json", new SpnDomainAgentCity2Executor());
        log.info("[Demo] Waiting {}s for agent ports to bind", STARTUP_WAIT);
        TimeUnit.SECONDS.sleep(STARTUP_WAIT);
        log.info(
            "[Demo] STAGE_DONE stage=start-omc-agents, count={}, elapsedMs={}",
            omc.servers().size(),
            elapsedMillis(stageStarted));
      } else {
        log.info(
            "[Demo] STAGE_SKIP stage=start-omc-agents, reason=external-omc, mode={}",
            transportMode);
      }

      stageStarted = System.nanoTime();
      log.info("[Demo] STAGE_START stage=start-spring-workbench");
      ctx = SpringApplication.run(SpringWorkbenchApplication.class, applicationArgs);
      log.info(
          "[Demo] STAGE_DONE stage=start-spring-workbench, elapsedMs={}",
          elapsedMillis(stageStarted));

      stageStarted = System.nanoTime();
      log.info(
          "[Demo] STAGE_START stage=send-workbench-task, target={}, inputChars={}",
          WB_AGENT_NAME,
          SpnCasePrompts.TASK_TEXT.length());
      log.debug("[Demo] Workbench task text={}", SpnCasePrompts.TASK_TEXT);
      SendMessageResult result = sendTaskToWorkbench();
      requireCompleted(result);
      String response = result.getText();
      log.info(
          "[Demo] STAGE_DONE stage=send-workbench-task, responseChars={}, elapsedMs={}",
          response != null ? response.length() : 0,
          elapsedMillis(stageStarted));
      if (response != null) {
        log.info("[Demo] Workbench response={}", response);
      } else {
        log.warn("[Demo] Workbench returned no response text");
      }
      success = true;
    } catch (Exception e) {
      log.error(
          "[Demo] FAILED elapsedMs={}, errorType={}, message={}",
          elapsedMillis(demoStarted),
          e.getClass().getSimpleName(),
          e.getMessage(),
          e);
      throw e;
    } finally {
      log.info(
          "[Demo] SHUTDOWN_START omcCount={}, springStarted={}, gatewayStarted={}",
          omc.servers().size(),
          ctx != null,
          gateway != null);
      if (ctx != null) {
        try {
          boolean drained =
              ctx.getBean(A2AController.class).awaitStreamsDrained(java.time.Duration.ofSeconds(2));
          if (!drained) {
            log.warn(
                "[Demo] SSE_DRAIN_TIMEOUT activeStreams={}, timeoutSeconds=2",
                ctx.getBean(A2AController.class).activeStreamCount());
          } else {
            log.info("[Demo] SSE_DRAIN_DONE activeStreams=0");
          }
        } catch (Exception e) {
          log.warn("[Demo] Failed to drain SSE responses: {}", e.getMessage(), e);
        }
        try {
          // Drain outbound Notification-T calls before Spring starts stopping Tomcat and
          // the optional Order simulator. The bean's @PreDestroy call is idempotent.
          ctx.getBean(SpringWorkbenchExtensionLifecycle.class).close();
        } catch (Exception e) {
          log.warn("[Demo] Failed to close workbench extension lifecycle: {}", e.getMessage(), e);
        }
        try {
          ctx.close();
        } catch (Exception e) {
          log.warn("[Demo] Failed to close Spring context: {}", e.getMessage(), e);
        }
      }
      omc.close();
      if (gateway != null) {
        try {
          gateway.close();
        } catch (Exception e) {
          log.warn("[Demo] Failed to close mock gateway: {}", e.getMessage(), e);
        }
      }
      log.info(
          "[Demo] SHUTDOWN_DONE success={}, elapsedMs={}", success, elapsedMillis(demoStarted));
    }
  }

  /**
   * Northbound Task-T message to the Workbench Agent, mirroring spec case 7.1.
   *
   * <p>Structured-data track: the demo hands over the raw complaint data + schema and the A2A-T SDK
   * renders the Task-T prompt through its schema-aware fromData pipeline (no hand-written prompt
   * and no scenario-recognition call; slot extraction may use the SDK LLM).
   */
  private SendMessageResult sendTaskToWorkbench() throws Exception {
    long started = System.nanoTime();
    AgentCard wbCard =
        OmcAgentLauncher.cardFromResource("agentcard/transport_workbench_agent.json");
    String credPath = OmcAgentLauncher.resourcePath("spn_agent_credentials.json");
    String envPath = EnvResolver.resolveEnvPath();

    A2ATransport transport =
        new A2ATransport(
            List.of(wbCard),
            null,
            WorkflowEngineClientConfig.builder()
                .sslVerify(false)
                .a2atEnvPath(envPath)
                .credentialsConfigPath(credPath)
                .build());
    DefaultWorkflowEngineClient client = new DefaultWorkflowEngineClient(transport);
    try {
      log.info(
          "[Demo] NORTHBOUND_SEND target={}, contextId={}, endpoint={}, inputChars={},"
              + " track=fromData",
          WB_AGENT_NAME,
          transport.getContextId(),
          wbCard.supportedInterfaces().isEmpty() ? "?" : wbCard.supportedInterfaces().get(0).url(),
          SpnCasePrompts.TASK_TEXT.length());
      SendMessageResult result =
          client
              .sendMessageFromData(
                  WB_AGENT_NAME,
                  SpnCasePrompts.TASK_TEXT,
                  SpnCasePrompts.privateLineComplaintData(),
                  SpnCasePrompts.privateLineComplaintSchema(),
                  net.openan.a2at.sdk.core.model.StandardTemplates.PRIVATE_LINE_COMPLAINT)
              .join();
      log.info(
          "[Demo] NORTHBOUND_DONE target={}, contextId={}, state={}, responseChars={},"
              + " elapsedMs={}",
          WB_AGENT_NAME,
          transport.getContextId(),
          result.getTaskState(),
          result.getText() != null ? result.getText().length() : 0,
          elapsedMillis(started));
      return result;
    } finally {
      client.close();
      transport.close();
    }
  }

  static void requireCompleted(SendMessageResult result) {
    if (result == null
        || result.getTaskState() == null
        || !result.getTaskState().contains("TASK_STATE_COMPLETED")) {
      throw new IllegalStateException(
          "Workbench task failed: state="
              + (result != null ? result.getTaskState() : "null")
              + ", response="
              + (result != null ? result.getText() : "null"));
    }
  }

  private static String resolveTransportMode(String[] args) {
    for (String arg : args) {
      if (arg.startsWith("--a2a.transport-mode=")) {
        return arg.substring("--a2a.transport-mode=".length());
      }
    }
    String configuredMode = System.getProperty("A2A_TRANSPORT_MODE");
    if (configuredMode == null || configuredMode.isBlank()) {
      configuredMode = System.getenv("A2A_TRANSPORT_MODE");
    }
    return configuredMode == null || configuredMode.isBlank() ? "order" : configuredMode;
  }

  static boolean resolveEmbeddedOmcEnabled(String[] args, String transportMode) {
    String configured = argumentValue(args, "--a2a.embedded-omc-enabled=");
    if (configured == null) {
      configured = configuredValue("A2A_EMBEDDED_OMC_ENABLED");
    }
    boolean simulator = resolveOrderSimulatorEnabled(args);
    boolean enabled =
        configured == null
            ? (!"order".equalsIgnoreCase(transportMode) || simulator)
            : parseBoolean(configured, "A2A_EMBEDDED_OMC_ENABLED");
    return enabled;
  }

  private static boolean resolveOrderSimulatorEnabled(String[] args) {
    String configured = argumentValue(args, "--a2a.order.simulator-enabled=");
    if (configured == null) {
      configured = configuredValue("EASTCOM_ORDER_SIMULATOR_ENABLED");
    }
    return configured != null && parseBoolean(configured, "EASTCOM_ORDER_SIMULATOR_ENABLED");
  }

  private static String argumentValue(String[] args, String prefix) {
    for (String arg : args) {
      if (arg.startsWith(prefix)) {
        return arg.substring(prefix.length());
      }
    }
    return null;
  }

  private static String configuredValue(String name) {
    String value = System.getProperty(name);
    if (value == null || value.isBlank()) {
      value = System.getenv(name);
    }
    return value == null || value.isBlank() ? null : value.trim();
  }

  private static boolean parseBoolean(String value, String name) {
    if ("true".equalsIgnoreCase(value)) {
      return true;
    }
    if ("false".equalsIgnoreCase(value)) {
      return false;
    }
    throw new IllegalArgumentException(name + " must be true or false");
  }

  private static long elapsedMillis(long startedNanos) {
    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
  }
}
