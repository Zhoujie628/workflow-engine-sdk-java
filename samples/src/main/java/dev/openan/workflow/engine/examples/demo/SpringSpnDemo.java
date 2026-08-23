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
import dev.openan.workflow.engine.examples.util.EnvResolver;
import dev.openan.workflow.engine.examples.agents.SpnDomainAgentCity1Executor;
import dev.openan.workflow.engine.examples.agents.SpnDomainAgentCity2Executor;
import dev.openan.workflow.engine.examples.server.JdkHttpA2AServer;
import dev.openan.workflow.engine.model.SendMessageResult;

import org.a2aproject.sdk.server.agentexecution.AgentExecutor;
import org.a2aproject.sdk.spec.AgentCard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.File;
import java.util.ArrayList;
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

    private final List<JdkHttpA2AServer> omcServers = new ArrayList<>();

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
            startOmcAgents();
            log.info("[Demo] Waiting {}s for agent ports to bind", STARTUP_WAIT);
            TimeUnit.SECONDS.sleep(STARTUP_WAIT);
            log.info(
                    "[Demo] STAGE_DONE stage=start-omc-agents, count={}, elapsedMs={}",
                    omcServers.size(),
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
                    14);
            log.debug("[Demo] Workbench task text={}", "创建专线业务投诉诊断任务");
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
                    omcServers.size(),
                    ctx != null);
            if (ctx != null) {
                try {
                    ctx.close();
                } catch (Exception e) {
                    log.warn("[Demo] Failed to close Spring context: {}", e.getMessage(), e);
                }
            }
            shutdownOmcAgents();
            log.info(
                    "[Demo] SHUTDOWN_DONE success={}, elapsedMs={}",
                    success,
                    elapsedMillis(demoStarted));
        }
    }

    private void startOmcAgents() throws Exception {
        startOmcAgent("agentcard/spn_domain_agent_city1.json", new SpnDomainAgentCity1Executor());
        startOmcAgent("agentcard/spn_domain_agent_city2.json", new SpnDomainAgentCity2Executor());
    }

    @SuppressWarnings("unchecked")
    private void startOmcAgent(String resourcePath, AgentExecutor executor) throws Exception {
        long started = System.nanoTime();
        String path = getClass().getClassLoader().getResource(resourcePath).getPath();
        Map<String, Object> card = mapper.readValue(new File(path), Map.class);
        List<Map<String, Object>> ifaces =
                (List<Map<String, Object>>) card.getOrDefault("supportedInterfaces", List.of());
        String url =
                ifaces.isEmpty() ? "https://127.0.0.1:0" : String.valueOf(ifaces.get(0).get("url"));
        java.net.URI uri = java.net.URI.create(url);
        String host = uri.getHost() != null ? uri.getHost() : "127.0.0.1";
        int port = Math.max(uri.getPort(), 0);
        JdkHttpA2AServer server = new JdkHttpA2AServer(host, port, card, executor);
        server.start();
        omcServers.add(server);
        log.info(
                "[Demo] OMC_STARTED agent={}, resource={}, endpoint={}, elapsedMs={}",
                card.get("name"), resourcePath, url, elapsedMillis(started));
    }

    /** Northbound Task-T message to the Workbench Agent, mirroring spec case 7.1. */
    private String sendTaskToWorkbench() throws Exception {
        long started = System.nanoTime();
        String cardPath =
                getClass()
                        .getClassLoader()
                        .getResource("agentcard/transport_workbench_agent.json")
                        .getPath();
        AgentCard wbCard = mapper.readValue(new File(cardPath), AgentCard.class);

        String credPath =
                getClass().getClassLoader().getResource("spn_agent_credentials.json").getPath();
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
            // Task-T structured prompt placed in message.metadata (spec §6).
            String taskPrompt =
                    "## 任务类型(Task Type)\n传输专线业务投诉诊断\n\n"
                            + "## 任务描述(Task Description)\n"
                            + "基于<任务对象>、<任务上下文> 进行投诉场景的网络侧故障根因诊断, "
                            + "达成<任务目标>中定义的投诉诊断目标，按照<预期输出>中定义的结构返回任务处理结果。\n\n"
                            + "## 任务目标(Task Target)\n对网络侧故障进行诊断，返回故障根因和修复建议等诊断结果信息。\n\n"
                            + "## 任务对象(Task Object)\n"
                            + "接入端口名称：P781-珠江新城-PTN7900-23-TPA1EG24-17\n\n"
                            + "## 任务上下文(Task Context)\n"
                            + "1. 投诉分类：\"专线质差\"\n"
                            + "2. 问题发生时间：\"2026-05-11T08:21:46Z\"\n"
                            + "3. OSS侧事件流水号：\"event-id-20260511-09013\"\n"
                            + "4. 投诉详情：\"从5月11号早上8点半开始，深圳访问广州的响应延迟从平均12ms骤升至320ms，"
                            + "访问广州机房的核心交易系统非常慢。\"\n\n"
                            + "## 预期输出(Expected Output)\n"
                            + "1. 诊断结果；参数的取值范围包括：成功、失败；(必选)\n"
                            + "2. 诊断结果详情；(必选)\n"
                            + "3. 修复建议；(可选)\n"
                            + "4. 故障根因列表，每个故障根因包含故障根因名称、详细描述、修复建议、故障根因点位置等信息；(可选)";
            String taskTUri =
                    "https://projects.tmforum.org/a2aproject/telecommunication/extensions/Task-T/v1";
            java.util.Map<String, Object> metadata = new java.util.LinkedHashMap<>();
            metadata.put(taskTUri, taskPrompt);
            String partsText = "创建专线业务投诉诊断任务";
            log.info(
                    "[Demo] NORTHBOUND_SEND target={}, contextId={}, endpoint={}, inputChars={}",
                    WB_AGENT_NAME,
                    transport.getContextId(),
                    wbCard.supportedInterfaces().isEmpty()
                            ? "?"
                            : wbCard.supportedInterfaces().get(0).url(),
                    partsText.length());
            SendMessageResult result =
                    client.sendMessage(WB_AGENT_NAME, partsText, null, metadata).join();
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

    private void shutdownOmcAgents() {
        log.info("[Demo] OMC_SHUTDOWN_START count={}", omcServers.size());
        omcServers.forEach(
                s -> {
                    try {
                        s.close();
                    } catch (Exception e) {
                        log.warn("[Demo] Failed to close OMC server: {}", e.getMessage(), e);
                    }
                });
        omcServers.clear();
        log.info("[Demo] OMC_SHUTDOWN_DONE");
    }

    /** Backward-compatible programmatic entry point. */
    public void run() throws Exception {
        run(new String[0]);
    }

    private static long elapsedMillis(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }
}
