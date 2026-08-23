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
import dev.openan.workflow.engine.examples.agents.SpnDomainAgentCity1Executor;
import dev.openan.workflow.engine.examples.agents.SpnDomainAgentCity2Executor;
import dev.openan.workflow.engine.examples.server.OmcAgentLauncher;
import dev.openan.workflow.engine.examples.util.EnvResolver;
import dev.openan.workflow.engine.model.SendMessageResult;

import org.a2aproject.sdk.spec.AgentCard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import dev.openan.workflow.engine.examples.SpringWorkbenchApplication;

/**
 * Demo entry point for the SPN cross-city diagnosis (direct HTTP+JSON transport).
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
 * communicate via the unified A2A-T protocol.
 */
public class SpringSpnDemo {
    private static final Logger log = LoggerFactory.getLogger(SpringSpnDemo.class);
    private static final ObjectMapper mapper =
            new ObjectMapper().registerModule(new AgentCardJacksonModule());

    private static final String WB_AGENT_NAME = "Transport Workbench Agent";
    private static final long STARTUP_WAIT = 3;

    private final OmcAgentLauncher omc = new OmcAgentLauncher();

    public static void main(String[] args) throws Exception {
        System.setProperty("jdk.internal.httpclient.disableHostnameVerification", "true");
        SpringWorkbenchApplication.loadDotEnv();
        new SpringSpnDemo().run(args);
    }

    public void run(String... applicationArgs) throws Exception {
        long demoStarted = System.nanoTime();
        ConfigurableApplicationContext ctx = null;
        boolean success = false;
        log.info(
                "[Demo] START mode=direct, "
                        + "workbench=https://127.0.0.1:26337/a2a/json, omcPorts=[26335,26336]");
        try {
            long stageStarted;
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
            String response = sendTaskToWorkbench();
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
                    "[Demo] SHUTDOWN_START omcCount={}, springStarted={}",
                    omc.servers().size(),
                    ctx != null);
            if (ctx != null) {
                try {
                    ctx.close();
                } catch (Exception e) {
                    log.warn("[Demo] Failed to close Spring context: {}", e.getMessage(), e);
                }
            }
            omc.close();
            log.info(
                    "[Demo] SHUTDOWN_DONE success={}, elapsedMs={}",
                    success,
                    elapsedMillis(demoStarted));
        }
    }

    /**
     * Northbound Task-T message to the Workbench Agent, mirroring spec case 7.1.
     *
     * <p>Structured-data track: the demo hands over the raw complaint data + schema and the
     * A2A-T SDK renders the Task-T prompt deterministically (no hand-written prompt text, no
     * LLM call) — the same pattern as the official SDK sample.
     */
    private String sendTaskToWorkbench() throws Exception {
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
                    "[Demo] NORTHBOUND_SEND target={}, contextId={}, endpoint={}, inputChars={}, track=fromData",
                    WB_AGENT_NAME,
                    transport.getContextId(),
                    wbCard.supportedInterfaces().isEmpty()
                            ? "?"
                            : wbCard.supportedInterfaces().get(0).url(),
                    SpnCasePrompts.TASK_TEXT.length());
            SendMessageResult result =
                    client.sendMessageFromData(
                                    WB_AGENT_NAME,
                                    SpnCasePrompts.TASK_TEXT,
                                    SpnCasePrompts.privateLineComplaintData(),
                                    SpnCasePrompts.privateLineComplaintSchema(),
                                    net.openan.a2at.sdk.core.model.StandardTemplates
                                            .PRIVATE_LINE_COMPLAINT)
                            .join();
            log.info(
                    "[Demo] NORTHBOUND_DONE target={}, contextId={}, state={}, responseChars={}, elapsedMs={}",
                    WB_AGENT_NAME,
                    transport.getContextId(),
                    result.getTaskState(),
                    result.getText() != null ? result.getText().length() : 0,
                    elapsedMillis(started));
            return result.getText();
        } finally {
            client.close();
            transport.close();
        }
    }

    /** Backward-compatible programmatic entry point. */
    public void run() throws Exception {
        run(new String[0]);
    }

    private static long elapsedMillis(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }
}
