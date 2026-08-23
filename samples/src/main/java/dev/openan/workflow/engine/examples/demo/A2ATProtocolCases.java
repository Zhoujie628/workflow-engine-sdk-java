/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.examples.demo;

import dev.openan.workflow.engine.client.A2ATExtension;
import dev.openan.workflow.engine.client.A2ATransport;
import dev.openan.workflow.engine.client.DefaultExtensionSender;
import dev.openan.workflow.engine.client.DefaultWorkflowEngineClient;
import dev.openan.workflow.engine.client.WorkflowEngineClientConfig;
import dev.openan.workflow.engine.examples.agents.SpnDomainAgentCity1Executor;
import dev.openan.workflow.engine.examples.server.OmcAgentLauncher;
import dev.openan.workflow.engine.examples.util.EnvResolver;
import dev.openan.workflow.engine.model.SendMessageResult;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Reproduces the 11 protocol verification cases from the A2A-T spec extraction document.
 * Run with --case=7.1 to select individual cases, or --case=all for all.
 */
public class A2ATProtocolCases {
    private static final Logger log = LoggerFactory.getLogger(A2ATProtocolCases.class);

    private static final String NEGOTIATION_T_URI = A2ATExtension.NEGOTIATION_T.uri();

    private static final String AGENT_NAME = "SPN Domain Agent City1";
    private static final long STARTUP_WAIT = 3;

    private final OmcAgentLauncher omc = new OmcAgentLauncher();
    private A2ATransport transport;
    private DefaultWorkflowEngineClient client;
    private DefaultExtensionSender sender;
    private final AtomicReference<String> lastTaskId = new AtomicReference<>();

    public static void main(String[] args) throws Exception {
        System.setProperty("jdk.internal.httpclient.disableHostnameVerification", "true");
        String caseId = "all";
        for (String arg : args) {
            if (arg.startsWith("--case=")) caseId = arg.substring("--case=".length());
        }
        new A2ATProtocolCases().run(caseId);
    }

    public void run(String caseId) throws Exception {
        log.info("[ProtocolCases] START case={}", caseId);
        setup();
        try {
            if ("all".equalsIgnoreCase(caseId)) {
                case_0(); case_7_1(); case_7_2(); case_7_3(); case_7_4();
                case_7_5(); case_7_6(); case_7_7(); case_7_8();
                case_7_9(); case_7_10(); case_7_11();
            } else {
                switch (caseId) {
                    case "0" -> case_0();
                    case "7.1" -> case_7_1();
                    case "7.2" -> case_7_2();
                    case "7.3" -> case_7_3();
                    case "7.4" -> case_7_4();
                    case "7.5" -> case_7_5();
                    case "7.6" -> case_7_6();
                    case "7.7" -> case_7_7();
                    case "7.8" -> case_7_8();
                    case "7.9" -> case_7_9();
                    case "7.10" -> case_7_10();
                    case "7.11" -> case_7_11();
                    default -> log.warn("[ProtocolCases] Unknown case: {}", caseId);
                }
            }
        } finally { teardown(); }
        log.info("[ProtocolCases] DONE");
    }

    private void setup() throws Exception {
        omc.startFromResource(
                "agentcard/spn_domain_agent_city1.json", new SpnDomainAgentCity1Executor());
        TimeUnit.SECONDS.sleep(STARTUP_WAIT);
        log.info("[ProtocolCases] OMC agent {} started on https://127.0.0.1:26335", AGENT_NAME);

        transport = new A2ATransport(
                List.of(OmcAgentLauncher.cardFromResource("agentcard/spn_domain_agent_city1.json")),
                null,
                WorkflowEngineClientConfig.builder()
                        .sslVerify(false)
                        .a2atEnvPath(EnvResolver.resolveEnvPath())
                        .credentialsConfigPath(
                                OmcAgentLauncher.resourcePath("spn_agent_credentials.json"))
                        .build());
        client = new DefaultWorkflowEngineClient(transport);
        sender = new DefaultExtensionSender(transport);
    }

    private void teardown() {
        if (client != null) client.close();
        if (transport != null) transport.close();
        omc.close();
    }

    /** Case 0: enumerate available A2A-T templates via the engine's template-query surface. */
    private void case_0() {
        log.info("[Case 0] Enumerate A2A-T templates");
        var prompts = client.getPrompts();
        log.info("[Case 0] All templates: {}", prompts.size());
        for (var t : prompts) {
            log.info("[Case 0]   {} ({} chars)", t.templateUri().uri(),
                    t.content() != null ? t.content().length() : 0);
        }
        var negPrompts = client.getNegotiationPrompts();
        log.info("[Case 0] Negotiation templates: {}", negPrompts.size());
        for (var t : negPrompts) {
            log.info("[Case 0]   {} -> {}", t.templateUri().uri(), t.description());
        }
        var one = client.getPrompt(net.openan.a2at.sdk.core.model.StandardTemplates.PRIVATE_LINE_COMPLAINT);
        log.info("[Case 0] getTaskTemplate(private-line-complaint): present={}", one.isPresent());
    }

    private void case_7_1() throws Exception {
        log.info("[Case 7.1] Create diagnosis task (Task-T)");
        Map<String, Object> metadata =
                SpnCasePrompts.taskTMetadata(SpnCasePrompts.privateLineComplaintTask());
        SendMessageResult result =
                client.sendMessage(AGENT_NAME, SpnCasePrompts.TASK_TEXT, null, metadata).join();
        log.info("[Case 7.1] state={}, textLen={}", result.getTaskState(), result.getText() != null ? result.getText().length() : 0);
        if (result.getTask() != null) { lastTaskId.set(result.getTask().id()); log.info("[Case 7.1] taskId={}", result.getTask().id()); }
    }

    private void case_7_2() throws Exception {
        String taskId = lastTaskId.get();
        if (taskId == null) { log.info("[Case 7.2] No task, running 7.1 first"); case_7_1(); taskId = lastTaskId.get(); }
        log.info("[Case 7.2] Query task: {}", taskId);
        SendMessageResult result = client.getTask(AGENT_NAME, taskId).join();
        log.info("[Case 7.2] state={}, artifacts={}", result.getTaskState(), result.getTask() != null && result.getTask().artifacts() != null ? result.getTask().artifacts().size() : 0);
    }

    private void case_7_3() {
        log.info("[Case 7.3] Negotiation - param missing (Task-T + Negotiation-T)");
        Map<String, Object> metadata =
                SpnCasePrompts.taskTMetadata(SpnCasePrompts.privateLineComplaintTaskBlankObject());
        metadata.put(NEGOTIATION_T_URI, ""); // activate Negotiation-T extension
        SendMessageResult result = client.sendMessage(AGENT_NAME, SpnCasePrompts.TASK_TEXT + "(参数缺失)", null, metadata).join();
        log.info("[Case 7.3] state={}, metaKeys={}", result.getTaskState(), result.getMetadata() != null ? result.getMetadata().keySet() : "none");
    }

    private void case_7_4() {
        log.info("[Case 7.4] Negotiation - semantic error (Task-T + Negotiation-T)");
        Map<String, Object> metadata =
                SpnCasePrompts.taskTMetadata(SpnCasePrompts.privateLineComplaintTaskUnknownPort());
        metadata.put(NEGOTIATION_T_URI, ""); // activate Negotiation-T extension
        SendMessageResult result = client.sendMessage(AGENT_NAME, SpnCasePrompts.TASK_TEXT + "(语义错误)", null, metadata).join();
        log.info("[Case 7.4] state={}, metaKeys={}", result.getTaskState(), result.getMetadata() != null ? result.getMetadata().keySet() : "none");
    }

    private void case_7_5() {
        log.info("[Case 7.5] Add authorization (Authorization-T)");
        String authPrompt =
                "## 授权策略的操作类型\n新增授权策略\n\n"
                        + "## 授权策略的操作描述\n请根据<授权策略的操作类型>和<动网操作的授权策略列表>完成相应的授权操作，"
                        + "按照<预期输出>中定义的结构返回授权策略的操作执行结果。\n\n"
                        + "## 动网操作的授权策略列表\n"
                        + "  - 动网操作的授权策略1\n"
                        + "    - 动网操作的业务场景：业务投诉诊断\n"
                        + "    - 动网操作的处置类型：业务抢通\n"
                        + "    - 动网操作名称：隧道调优\n"
                        + "    - 有效期：2026-06-01T12:00:00Z ~ 2030-06-18T12:00:00Z\n\n"
                        + "## 预期输出\n"
                        + "1. 授权操作执行结果，取值范围：成功、失败、部分成功；\n"
                        + "2. 授权操作执行成功时，返回执行成功的<动网操作的授权策略列表>；\n"
                        + "3. 授权操作执行失败或部分成功时，返回失败列表，包含授权策略和失败原因；";
        SendMessageResult result = sender.sendExtensionMessage(AGENT_NAME, "新增动网操作授权", authPrompt, A2ATExtension.AUTHORIZATION_T).join();
        log.info("[Case 7.5] state={}", result.getTaskState());
    }

    private void case_7_6() {
        log.info("[Case 7.6] Delete authorization (Authorization-T)");
        String delPrompt =
                "## 授权策略的操作类型\n删除授权策略\n\n"
                        + "## 授权策略的操作描述\n请根据<授权策略的操作类型>和<动网操作的授权策略列表>完成相应的授权操作，"
                        + "按照<预期输出>中定义的结构返回授权策略的操作执行结果。\n\n"
                        + "## 动网操作的授权策略列表\n"
                        + "  - 动网操作的授权策略1\n"
                        + "    - 动网操作的授权策略标识：7d8c7b00-3c8c-4f8e-9b1e-9b17b6a3e5c3\n\n"
                        + "## 预期输出\n"
                        + "1. 授权操作执行结果，取值范围：成功、失败、部分成功；\n"
                        + "2. 授权操作执行失败或部分成功时，返回失败列表，包含授权策略和失败原因；";
        SendMessageResult result = sender.sendExtensionMessage(AGENT_NAME, "删除动网操作的授权", delPrompt, A2ATExtension.AUTHORIZATION_T).join();
        log.info("[Case 7.6] state={}", result.getTaskState());
    }

    private void case_7_7() {
        log.info("[Case 7.7] Query authorization (Authorization-T)");
        String queryPrompt =
                "## 授权策略的操作类型\n查询授权策略\n\n"
                        + "## 授权策略的操作描述\n请根据<授权策略的操作类型>和<动网操作的授权策略列表>完成相应的授权操作，"
                        + "按照<预期输出>中定义的结构返回授权策略的操作执行结果。\n\n"
                        + "## 动网操作的授权策略列表\n"
                        + "   - 动网操作的授权策略1\n"
                        + "      - 动网操作的业务场景：业务投诉诊断\n"
                        + "      - 动网操作的处置类型：业务抢通\n"
                        + "      - 动网操作名称：隧道调优\n\n"
                        + "## 预期输出\n"
                        + "1. 授权操作执行结果，取值范围：成功、失败；\n"
                        + "2. 授权操作执行成功时，返回执行成功的<动网操作的授权策略列表>；\n"
                        + "3. 授权操作执行失败时，返回失败原因；";
        SendMessageResult result = sender.sendExtensionMessage(AGENT_NAME, "查询业务抢通操作授权信息", queryPrompt, A2ATExtension.AUTHORIZATION_T).join();
        log.info("[Case 7.7] state={}", result.getTaskState());
    }

    private void case_7_8() {
        log.info("[Case 7.8] Subscribe notification (Notification-T)");
        String notifPrompt =
                "## 订阅描述\n请根据以下 <通知主题>、<订阅条件>、<上报通知数据格式> 及 <预期输出> 信息，"
                        + "完成网络侧业务抢通事件的订阅与上报任务。\n\n"
                        + "## 通知主题\n业务抢通事件\n\n"
                        + "## 订阅条件\n\n"
                        + "## 上报通知数据格式\n### 业务抢通事件\n"
                        + "1. 业务抢通方案执行状态，取值范围：未启动、已结束；举例：已结束（必选）\n"
                        + "2. 投诉诊断任务流水号（必选）\n"
                        + "3. OSS侧事件流水号（必选）\n"
                        + "4. 接入端口名称（必选）\n"
                        + "5. 是否已授权OMC自动抢通，取值范围：是、否；举例：是（必选）\n"
                        + "6. 业务抢通方案名称（必选）\n"
                        + "7. 业务抢通方案详情（必选）\n"
                        + "8. 业务抢通方案执行结束时间（可选）\n"
                        + "9. 业务抢通方案执行结果，取值范围：成功、失败（可选）\n"
                        + "10. 业务抢通方案执行失败原因（可选）\n\n"
                        + "## 预期输出\n"
                        + "1. 订阅结果，取值范围：成功、失败\n"
                        + "2. 订阅失败原因（可选）\n"
                        + "3. 订阅成功后，按照<上报通知数据格式>上报消息";
        SendMessageResult result = sender.sendNotification(AGENT_NAME, "订阅业务抢通事件", notifPrompt, data -> log.info("[Case 7.8] callback: keys={}", data.keySet())).join();
        log.info("[Case 7.8] ack state={}", result.getTaskState());
    }

    private void case_7_9() throws Exception {
        log.info("[Case 7.9] Notification report (Notification-T)");
        log.info("[Case 7.9] Waiting 5s for pushed events...");
        TimeUnit.SECONDS.sleep(5);
        log.info("[Case 7.9] Done");
    }

    private void case_7_10() {
        String taskId = lastTaskId.get();
        if (taskId == null) { log.info("[Case 7.10] No task, skipping"); return; }
        log.info("[Case 7.10] Cancel subscription: {}", taskId);
        SendMessageResult result = client.cancelTask(AGENT_NAME, taskId).join();
        log.info("[Case 7.10] state={}", result.getTaskState());
    }

    private void case_7_11() {
        String taskId = lastTaskId.get();
        if (taskId == null) { log.info("[Case 7.11] No task, skipping"); return; }
        log.info("[Case 7.11] Reconnect: {}", taskId);
        SendMessageResult result = client.subscribeToTask(AGENT_NAME, taskId, data -> log.info("[Case 7.11] callback: keys={}", data.keySet())).join();
        log.info("[Case 7.11] state={}", result.getTaskState());
    }
}
