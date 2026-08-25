/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.examples.demo;

import dev.openan.workflow.engine.client.A2ATExtension;
import dev.openan.workflow.engine.client.A2ATransport;
import dev.openan.workflow.engine.client.DefaultExtensionSender;
import dev.openan.workflow.engine.client.DefaultWorkflowEngineClient;
import dev.openan.workflow.engine.client.NotificationSubscription;
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
import java.util.concurrent.atomic.AtomicBoolean;
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
    private A2ATransport taskTransport;
    private A2ATransport authorizationTransport;
    private A2ATransport notificationTransport;
    private DefaultWorkflowEngineClient client;
    private DefaultWorkflowEngineClient notificationControlClient;
    private DefaultExtensionSender authorizationSender;
    private DefaultExtensionSender notificationSender;
    private final AtomicReference<String> lastTaskId = new AtomicReference<>();
    private final AtomicReference<String> notificationTaskId = new AtomicReference<>();
    private final AtomicBoolean recoveryNotificationReceived = new AtomicBoolean();
    private NotificationSubscription notificationSubscription;

    public static void main(String[] args) throws Exception {
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

        List<org.a2aproject.sdk.spec.AgentCard> cards =
                List.of(OmcAgentLauncher.cardFromResource(
                        "agentcard/spn_domain_agent_city1.json"));
        WorkflowEngineClientConfig config =
                WorkflowEngineClientConfig.builder()
                        .sslVerify(false)
                        .a2atEnvPath(EnvResolver.resolveEnvPath())
                        .credentialsConfigPath(
                                OmcAgentLauncher.resourcePath("spn_agent_credentials.json"))
                        .build();
        // Task-T, Authorization-T and Notification-T deliberately use independent transport,
        // runtime and context instances. Notification-T owns the only long-lived stream.
        taskTransport = new A2ATransport(cards, null, config);
        authorizationTransport = new A2ATransport(cards, null, config);
        notificationTransport = new A2ATransport(cards, null, config);
        client = new DefaultWorkflowEngineClient(taskTransport);
        notificationControlClient = new DefaultWorkflowEngineClient(notificationTransport);
        authorizationSender = new DefaultExtensionSender(authorizationTransport);
        notificationSender = new DefaultExtensionSender(notificationTransport);
    }

    private void teardown() {
        if (notificationSubscription != null) notificationSubscription.close();
        if (client != null) client.close();
        if (notificationControlClient != null) notificationControlClient.close();
        if (taskTransport != null) taskTransport.close();
        if (authorizationTransport != null) authorizationTransport.close();
        if (notificationTransport != null) notificationTransport.close();
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
        log.info("[Case 7.1] Create diagnosis task (Task-T, fromData track)");
        SendMessageResult result =
                client.sendMessageFromData(
                                AGENT_NAME,
                                SpnCasePrompts.TASK_TEXT,
                                SpnCasePrompts.privateLineComplaintData(),
                                SpnCasePrompts.privateLineComplaintSchema(),
                                net.openan.a2at.sdk.core.model.StandardTemplates
                                        .PRIVATE_LINE_COMPLAINT)
                        .join();
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
        log.info("[Case 7.3] Negotiation - param missing (Task-T fromData + Negotiation-T)");
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(A2ATExtension.TASK_DATA_META_KEY, SpnCasePrompts.privateLineComplaintDataBlankObject());
        metadata.put(A2ATExtension.TASK_SCHEMA_META_KEY, SpnCasePrompts.privateLineComplaintSchema());
        metadata.put(NEGOTIATION_T_URI, ""); // activate Negotiation-T extension
        SendMessageResult result = client.sendMessage(AGENT_NAME, SpnCasePrompts.TASK_TEXT + "(参数缺失)", null, metadata).join();
        log.info("[Case 7.3] state={}, metaKeys={}", result.getTaskState(), result.getMetadata() != null ? result.getMetadata().keySet() : "none");
    }

    private void case_7_4() {
        log.info("[Case 7.4] Negotiation - semantic error (Task-T fromData + Negotiation-T)");
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(A2ATExtension.TASK_DATA_META_KEY, SpnCasePrompts.privateLineComplaintDataUnknownPort());
        metadata.put(A2ATExtension.TASK_SCHEMA_META_KEY, SpnCasePrompts.privateLineComplaintSchema());
        metadata.put(NEGOTIATION_T_URI, ""); // activate Negotiation-T extension
        SendMessageResult result = client.sendMessage(AGENT_NAME, SpnCasePrompts.TASK_TEXT + "(语义错误)", null, metadata).join();
        log.info("[Case 7.4] state={}, metaKeys={}", result.getTaskState(), result.getMetadata() != null ? result.getMetadata().keySet() : "none");
    }

    private void case_7_5() {
        log.info("[Case 7.5] Add authorization (Authorization-T)");
        SendMessageResult result =
                authorizationSender.sendExtensionMessageFromData(
                                AGENT_NAME,
                                "新增动网操作授权",
                                SpnCasePrompts.addAuthorizationData(),
                                SpnCasePrompts.authorizationSchema(),
                                A2ATExtension.AUTHORIZATION_T)
                        .join();
        log.info("[Case 7.5] state={}", result.getTaskState());
    }

    private void case_7_6() {
        log.info("[Case 7.6] Delete authorization (Authorization-T)");
        SendMessageResult result =
                authorizationSender.sendExtensionMessageFromData(
                                AGENT_NAME,
                                "删除动网操作的授权",
                                Map.of(
                                        "授权策略的操作类型",
                                        "删除授权策略",
                                        "动网操作的授权策略列表",
                                        "7d8c7b00-3c8c-4f8e-9b1e-9b17b6a3e5c3"),
                                SpnCasePrompts.authorizationSchema(),
                                A2ATExtension.AUTHORIZATION_T)
                        .join();
        log.info("[Case 7.6] state={}", result.getTaskState());
    }

    private void case_7_7() {
        log.info("[Case 7.7] Query authorization (Authorization-T)");
        SendMessageResult result =
                authorizationSender.sendExtensionMessageFromData(
                                AGENT_NAME,
                                "查询业务抢通操作授权信息",
                                Map.of(
                                        "授权策略的操作类型",
                                        "查询授权策略",
                                        "动网操作的授权策略列表",
                                        "业务投诉诊断/业务抢通/隧道调优"),
                                SpnCasePrompts.authorizationSchema(),
                                A2ATExtension.AUTHORIZATION_T)
                        .join();
        log.info("[Case 7.7] state={}", result.getTaskState());
    }

    private void case_7_8() {
        log.info("[Case 7.8] Subscribe notification (Notification-T)");
        recoveryNotificationReceived.set(false);
        notificationSubscription =
                notificationSender.openNotificationFromData(
                                AGENT_NAME,
                                "订阅业务抢通事件",
                                SpnCasePrompts.subscribeServiceRecoveryData(),
                                SpnCasePrompts.serviceRecoverySchema(),
                                data -> {
                                    log.info("[Case 7.8] callback: keys={}", data.keySet());
                                    if ("recovery-result".equals(data.get("artifact_name"))) {
                                        recoveryNotificationReceived.set(true);
                                    }
                                })
                        .join();
        SendMessageResult result = notificationSubscription.acknowledgement().join();
        if (result.getTask() != null) {
            notificationTaskId.set(result.getTask().id());
        }
        log.info("[Case 7.8] ack state={}", result.getTaskState());
    }

    private void case_7_9() throws Exception {
        log.info("[Case 7.9] Notification report (Notification-T)");
        if (notificationTaskId.get() == null) {
            log.info("[Case 7.9] No notification task, running 7.8 first");
            case_7_8();
        }
        log.info("[Case 7.9] Waiting 5s for pushed events...");
        TimeUnit.SECONDS.sleep(5);
        if (recoveryNotificationReceived.get() && notificationSubscription != null) {
            notificationSubscription.close();
            notificationSubscription = null;
            log.info("[Case 7.9] Recovery result received; Notification-T stream closed");
        } else {
            log.info("[Case 7.9] No recovery result yet; Notification-T stream remains active");
        }
    }

    private void case_7_10() {
        if (notificationTaskId.get() == null) {
            log.info("[Case 7.10] No notification task, running 7.8 first");
            case_7_8();
        }
        String taskId = notificationTaskId.getAndSet(null);
        log.info("[Case 7.10] Cancel subscription: {}", taskId);
        SendMessageResult result = notificationControlClient.cancelTask(AGENT_NAME, taskId).join();
        if (notificationSubscription != null) {
            notificationSubscription.close();
            notificationSubscription = null;
        }
        log.info("[Case 7.10] state={}", result.getTaskState());
    }

    private void case_7_11() {
        String taskId = notificationTaskId.get();
        if (taskId == null) {
            log.info("[Case 7.11] No notification task, running 7.8 first");
            case_7_8();
            taskId = notificationTaskId.get();
        }
        if (notificationSubscription != null) {
            notificationSubscription.close();
            notificationSubscription = null;
        }
        log.info("[Case 7.11] Reconnect: {}", taskId);
        SendMessageResult result =
                notificationControlClient
                        .subscribeToTask(
                                AGENT_NAME,
                                taskId,
                                data ->
                                        log.info(
                                                "[Case 7.11] callback: keys={}", data.keySet()))
                        .join();
        log.info("[Case 7.11] state={}", result.getTaskState());
    }
}
