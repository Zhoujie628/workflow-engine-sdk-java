/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.openan.workflow.engine.examples;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.openan.workflow.engine.client.A2ATransport;
import dev.openan.workflow.engine.client.AgentCardJacksonModule;
import dev.openan.workflow.engine.client.DefaultExtensionSender;
import dev.openan.workflow.engine.client.DefaultWorkflowEngineClient;
import dev.openan.workflow.engine.client.ExtensionSender;
import dev.openan.workflow.engine.client.NotificationSubscription;
import dev.openan.workflow.engine.client.WorkflowEngineClientConfig;
import dev.openan.workflow.engine.control.EventCallback;
import dev.openan.workflow.engine.control.EventType;
import dev.openan.workflow.engine.examples.agents.SpnDomainAgentCity1Executor;
import dev.openan.workflow.engine.examples.agents.SpnDomainAgentCity2Executor;
import dev.openan.workflow.engine.examples.demo.SpnCasePrompts;
import dev.openan.workflow.engine.examples.extension.PrePositionedExtensionHandler;
import dev.openan.workflow.engine.examples.testsupport.OfflineA2ATLlmClient;
import dev.openan.workflow.engine.examples.workbench.WorkbenchControlPoint;
import dev.openan.workflow.engine.examples.server.OmcAgentLauncher;
import dev.openan.workflow.engine.model.ExecutionResult;
import dev.openan.workflow.engine.model.JumpCondition;
import dev.openan.workflow.engine.model.StepType;
import dev.openan.workflow.engine.model.Task;
import dev.openan.workflow.engine.model.Workflow;
import dev.openan.workflow.engine.model.WorkflowStep;
import dev.openan.workflow.engine.runner.ExecutePsop;

import org.a2aproject.sdk.spec.AgentCard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * End-to-end business integration test for the SPN cross-city diagnosis workflow. Starts two real
 * SPN agents (JdkHttpA2AServer + A2A-T protocol over HTTPS+SSE), pre-positions Authorization-T and
 * Notification-T, runs a 3-step workflow (diagnose x2 + SelfLoop merge), and asserts the full
 * business path: parallel diagnosis -> whitelist self-recovery -> Notification-T report -> local
 * merge (no A2A-T to self). The diagnosis tasks carry complete input parameters; Negotiation-T is
 * covered by its independent protocol case and is not part of this workflow.
 *
 * <p>The A2A-T client/server paths use an offline structured-LLM provider, so the real SDK
 * generation and validation pipelines run without network access. The demo's unrelated business
 * prose LLM remains disabled by the Maven test profile.
 */
class SpnCrossCityE2ETest {

    private static final ObjectMapper mapper =
            new ObjectMapper().registerModule(new AgentCardJacksonModule());

    private final OmcAgentLauncher omc = new OmcAgentLauncher();
    private DefaultWorkflowEngineClient client;
    private ExtensionSender authorizationSender;
    private ExtensionSender notificationSender;
    private A2ATransport taskTransport;
    private A2ATransport authorizationTransport;
    private A2ATransport notificationTransport;
    private final List<NotificationSubscription> subscriptions = new CopyOnWriteArrayList<>();
    private int port1;
    private int port2;
    private String sdkEnvPath;

    @BeforeAll
    static void registerMockProvider() {
        OfflineA2ATLlmClient.install();
    }

    private static AgentCard cardFor(String name, int port) {
        Map<String, Object> card =
                Map.of(
                        "name", name,
                        "description", "test",
                        "provider", Map.of("organization", "test", "url", ""),
                        "version", "1.0.0",
                        "capabilities",
                                Map.of(
                                        "streaming",
                                        true,
                                        "pushNotifications",
                                        false,
                                        "extendedAgentCard",
                                        false,
                                        "extensions",
                                        List.of(
                                                Map.of(
                                                        "uri",
                                                        "https://projects.tmforum.org/a2aproject/telecommunication/extensions/Task-T/v1",
                                                        "required",
                                                        false),
                                                Map.of(
                                                        "uri",
                                                        "https://projects.tmforum.org/a2aproject/telecommunication/extensions/Negotiation-T/v1",
                                                        "required",
                                                        false),
                                                Map.of(
                                                        "uri",
                                                        "https://projects.tmforum.org/a2aproject/telecommunication/extensions/Authorization-T/v1",
                                                        "required",
                                                        false),
                                                Map.of(
                                                        "uri",
                                                        "https://projects.tmforum.org/a2aproject/telecommunication/extensions/Notification-T/v1",
                                                        "required",
                                                        false))),
                        "defaultInputModes", List.of("text/plain"),
                        "defaultOutputModes", List.of("text/plain"),
                        "skills",
                                List.of(
                                        Map.of(
                                                "id",
                                                "test",
                                                "name",
                                                "test",
                                                "description",
                                                "test",
                                                "tags",
                                                List.of())),
                        "supportedInterfaces",
                                List.of(
                                        Map.of(
                                                "protocolBinding",
                                                "HTTP+JSON",
                                                "protocolVersion",
                                                "1.0",
                                                "url",
                                                "https://127.0.0.1:" + port,
                                                "tenant",
                                                "")));
        return mapper.convertValue(card, AgentCard.class);
    }

    @BeforeEach
    void setUp() throws Exception {
        sdkEnvPath = OfflineA2ATLlmClient.envPath();
        System.setProperty("a2at.env.path", sdkEnvPath);
        port1 = 28200 + (int) (Math.random() * 500);
        port2 = port1 + 1;
        AgentCard c1 = cardFor("SPN Domain Agent City1", port1);
        AgentCard c2 = cardFor("SPN Domain Agent City2", port2);
        omc.startFromCard(
                mapper.convertValue(c1, Map.class),
                new SpnDomainAgentCity1Executor(new PrePositionedExtensionHandler()));
        omc.startFromCard(
                mapper.convertValue(c2, Map.class),
                new SpnDomainAgentCity2Executor(new PrePositionedExtensionHandler()));
        Thread.sleep(600);
        WorkflowEngineClientConfig config =
                WorkflowEngineClientConfig.builder()
                        .sslVerify(false)
                        .a2atEnvPath(sdkEnvPath)
                        .build();
        taskTransport = new A2ATransport(List.of(c1, c2), null, config);
        authorizationTransport = new A2ATransport(List.of(c1, c2), null, config);
        notificationTransport = new A2ATransport(List.of(c1, c2), null, config);
        client = new DefaultWorkflowEngineClient(taskTransport);
        authorizationSender = new DefaultExtensionSender(authorizationTransport);
        notificationSender = new DefaultExtensionSender(notificationTransport);
    }

    @AfterEach
    void tearDown() {
        subscriptions.forEach(NotificationSubscription::close);
        subscriptions.clear();
        if (client != null) client.close();
        if (taskTransport != null) taskTransport.close();
        if (authorizationTransport != null) authorizationTransport.close();
        if (notificationTransport != null) notificationTransport.close();
        omc.close();
        System.clearProperty("a2at.env.path");
    }

    private Workflow crossCityWorkflow() {
        Task t1 = Task.builder().agent("SPN Domain Agent City1").description("SPN专线故障诊断").build();
        Task t2 = Task.builder().agent("SPN Domain Agent City2").description("SPN专线故障诊断").build();
        Task merge = Task.builder().agent("Workbench").description("汇总两地市OMC诊断结论").build();
        WorkflowStep s1 =
                WorkflowStep.builder()
                        .name("diagnosis_city1")
                        .layer(0)
                        .subtasks(List.of(t1))
                        .next(
                                List.of(
                                        JumpCondition.builder()
                                                .step("merge_analysis")
                                                .condition("")
                                                .build()))
                        .build();
        WorkflowStep s2 =
                WorkflowStep.builder()
                        .name("diagnosis_city2")
                        .layer(0)
                        .subtasks(List.of(t2))
                        .next(
                                List.of(
                                        JumpCondition.builder()
                                                .step("merge_analysis")
                                                .condition("")
                                                .build()))
                        .build();
        WorkflowStep s3 =
                WorkflowStep.builder()
                        .name("merge_analysis")
                        .layer(1)
                        .stepType(StepType.SELF_LOOP)
                        .contextFrom(List.of("diagnosis_city1", "diagnosis_city2"))
                        .subtasks(List.of(merge))
                        .next(
                                List.of(
                                        JumpCondition.builder()
                                                .step("endNode")
                                                .condition("")
                                                .build()))
                        .build();
        return Workflow.builder().name("spn-e2e").steps(List.of(s1, s2, s3)).build();
    }

    @Test
    void fullBusinessPathDiagnosisRecoveryAndSelfLoopMerge() {
        assertNotEquals(taskTransport.getContextId(), authorizationTransport.getContextId());
        assertNotEquals(taskTransport.getContextId(), notificationTransport.getContextId());
        assertNotEquals(authorizationTransport.getContextId(), notificationTransport.getContextId());
        // Pre-position Authorization-T + Notification-T to both SPN agents
        for (String agent : List.of("SPN Domain Agent City1", "SPN Domain Agent City2")) {
            authorizationSender
                    .sendExtensionMessageFromData(
                            agent,
                            "下发授权放行策略",
                            SpnCasePrompts.addAuthorizationData(),
                            SpnCasePrompts.authorizationSchema(),
                            net.openan.a2at.sdk.core.model.StandardTemplates
                                    .AUTHORIZATION_POLICY_MANAGEMENT,
                            dev.openan.workflow.engine.client.A2ATExtension.AUTHORIZATION_T)
                    .join();
        }

        CountDownLatch recoveryPlanNotification = new CountDownLatch(1);
        CountDownLatch recoveryResultNotification = new CountDownLatch(1);
        AtomicReference<String> recoveryPlanNotificationText = new AtomicReference<>();
        AtomicReference<String> recoveryNotificationText = new AtomicReference<>();
        java.util.function.Consumer<Map<String, Object>> notificationCallback =
                event -> {
                    Object metadataValue = event.get("metadata");
                    String protocolContent = "";
                    if (metadataValue instanceof Map<?, ?> metadata) {
                        Object content = metadata.get(
                                dev.openan.workflow.engine.client.A2ATExtension.NOTIFICATION_T.uri());
                        if (content != null) protocolContent = String.valueOf(content);
                    }
                    if ("recovery-plan".equals(event.get("artifact_name"))) {
                        recoveryPlanNotificationText.set(protocolContent);
                        recoveryPlanNotification.countDown();
                    }
                    if ("recovery-result".equals(event.get("artifact_name"))) {
                        recoveryNotificationText.set(protocolContent);
                        recoveryResultNotification.countDown();
                    }
                };
        for (String agent : List.of("SPN Domain Agent City1", "SPN Domain Agent City2")) {
            NotificationSubscription subscription =
                    notificationSender.openNotificationFromData(
                                    agent,
                                    "订阅业务抢通结果通知",
                                    SpnCasePrompts.subscribeServiceRecoveryData(),
                                    SpnCasePrompts.serviceRecoverySchema(),
                                    net.openan.a2at.sdk.core.model.StandardTemplates.SERVICE_RECOVERY,
                                    notificationCallback)
                            .join();
            subscription.acknowledgement().join();
            subscriptions.add(subscription);
        }

        Map<String, Object> allOutputs = new ConcurrentHashMap<>();
        AtomicBoolean workflowTaskContainedRecovery = new AtomicBoolean(false);
        AtomicBoolean sawSelfLoop = new AtomicBoolean(false);
        AtomicBoolean sawNegotiation = new AtomicBoolean(false);
        EventCallback cb =
                new EventCallback() {
                    @Override
                    public void onEvent(String type, Map<String, Object> data) {
                        if (EventType.AGENT_ARTIFACT_UPDATE.equals(type)) {
                            String text =
                                    data.get("text") != null
                                            ? String.valueOf(data.get("text"))
                                            : "";
                            if (text.contains("业务抢通方案执行状态")
                                    || text.contains("Notification-T")) {
                                workflowTaskContainedRecovery.set(true);
                            }
                        }
                        if (EventType.TASK_RESPONSE.equals(type)
                                && "Workbench".equals(data.get("agent"))) {
                            sawSelfLoop.set(true);
                        }
                        if (EventType.NEGOTIATION_REQUEST.equals(type)) {
                            sawNegotiation.set(true);
                        }
                        allOutputs.put(type + ":" + data.get("agent"), data);
                    }
                };

        WorkbenchControlPoint cp = new WorkbenchControlPoint(sdkEnvPath);
        ExecutionResult result =
                ExecutePsop.builder()
                        .psop(crossCityWorkflow())
                        .controlPoint(cp)
                        .engineClient(client)
                        .runtimeIntent("SPN跨城专线故障诊断与抢通：客户A上海-广州间SPN专线中断")
                        .lang("zh")
                        .sslVerify(false)
                        .eventCallback(cb)
                        .execute()
                        .join();

        assertTrue(result.isSuccess(), "Workflow must succeed: " + result.getError());
        assertFalse(result.getHistory().isEmpty());
        assertEquals(3, result.getHistory().size(), "two diagnoses plus one merge must execute");
        long mergeExecutions =
                result.getHistory().stream()
                        .filter(entry -> "merge_analysis".equals(entry.get("step")))
                        .count();
        assertEquals(1, mergeExecutions, "cross-city diagnoses must be merged exactly once");
        assertFalse(
                sawNegotiation.get(),
                "The complete-input diagnosis workflow must not trigger Negotiation-T");
        // Self-loop merge step executed locally (no A2A-T to self)
        assertTrue(sawSelfLoop.get(), "Self-loop merge must run via onSelfTask");
        // Recovery is outside the workflow task channel: first the plan, then the result.
        assertFalse(
                workflowTaskContainedRecovery.get(),
                "Task-T diagnosis artifacts must not contain Notification-T recovery output");
        assertTrue(
                await(recoveryPlanNotification, 5),
                "Recovery plan must arrive first on the isolated Notification-T stream");
        assertTrue(
                recoveryPlanNotificationText.get() != null
                        && recoveryPlanNotificationText.get().contains("业务抢通方案执行状态：未启动")
                        && recoveryPlanNotificationText.get().contains("是否已授权OMC自动抢通：是"),
                "Recovery plan must contain the required protocol fields: "
                        + recoveryPlanNotificationText.get());
        assertTrue(
                await(recoveryResultNotification, 5),
                "Recovery result must arrive on the isolated Notification-T stream");
        assertTrue(
                recoveryNotificationText.get() != null
                        && recoveryNotificationText.get().contains("业务抢通方案执行结果：成功"),
                "Whitelist authorization must produce a successful recovery notification: "
                        + recoveryNotificationText.get());
        subscriptions.forEach(NotificationSubscription::close);
        assertTrue(
                subscriptions.stream().noneMatch(NotificationSubscription::isActive),
                "Notification-T streams must close after the recovery result is received");
        // Merge output contains fault localization
        Map<String, Object> mergeOut = result.getStepOutputs().get("merge_analysis");
        assertNotNull(mergeOut, "merge_analysis output must exist");
        String mergeText = String.valueOf(mergeOut.values().iterator().next());
        assertTrue(mergeText.contains("城市1"), "Merge must locate fault in City1: " + mergeText);
    }

    private static boolean await(CountDownLatch latch, long seconds) {
        try {
            return latch.await(seconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

}
