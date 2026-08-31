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

package dev.openan.workflow.engine.examples.negotiation;

import dev.openan.workflow.engine.examples.demo.SpnCasePrompts;
import dev.openan.workflow.engine.examples.testsupport.OfflineA2ATLlmClient;
import dev.openan.workflow.engine.examples.util.A2ATInitialization;
import dev.openan.workflow.engine.client.*;
import dev.openan.workflow.engine.model.*;
import net.openan.a2at.sdk.client.A2ATClient;
import net.openan.a2at.sdk.core.model.*;
import net.openan.a2at.sdk.negotiation.content.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class NegotiationStrategyTest {
    private String env;
    @BeforeEach void configure() throws Exception {
        OfflineA2ATLlmClient.install();
        env = java.nio.file.Path.of(getClass().getResource("/a2at-e2e.env").toURI()).toString();
    }

    @Test void twoCitiesUseTheirOwnInputs() {
        String first = answer(SpnCasePrompts.privateLineComplaintData());
        String second = answer(SpnCasePrompts.privateLineComplaintDataCity2());
        assertTrue(first.contains("P781-"));
        assertTrue(second.contains("P882-"));
        assertNotEquals(first, second);
    }

    @Test void missingInformationSendsAbortWithoutInventingPort() {
        var reply = assertInstanceOf(NegotiationReply.Send.class,
                new NegotiationStrategy(env).resolve(request(SpnCasePrompts.privateLineComplaintDataBlankObject())).join());
        var received = new ReceivedMessage(reply.content(), Map.of(), List.of());
        assertEquals(NegotiationPerformative.ABORT, A2atMessages.contextOf(received).performative());
    }

    @Test void hostDisclosurePolicyUsesSdkReject() {
        var strategy = new NegotiationStrategy(env) {
            @Override protected boolean mayProvideField(TaskRequest task, String fieldName) { return false; }
        };
        var reply = assertInstanceOf(NegotiationReply.Send.class,
                strategy.resolve(request(SpnCasePrompts.privateLineComplaintData())).join());
        assertEquals(NegotiationPerformative.REJECT,
                A2atMessages.contextOf(new ReceivedMessage(reply.content(), Map.of(), List.of())).performative());
        assertTrue(reply.content().metadata().get(A2ATExtension.NEGOTIATION_T.uri()).toString().contains("业务策略不允许提供"));
    }

    @Test void llmDescriptionParaphraseCannotRenameTheRequestedField() {
        var reply = assertInstanceOf(NegotiationReply.Send.class,
                new NegotiationStrategy(env).resolve(request(SpnCasePrompts.privateLineComplaintData(),
                        List.of(new NegotiationItem("任务对象", "请提供本地市实际接入端口名称")))).join());
        assertEquals(NegotiationPerformative.ACCEPT,
                A2atMessages.contextOf(new ReceivedMessage(reply.content(), Map.of(), List.of())).performative());
        String prompt = reply.content().metadata().get(A2ATExtension.NEGOTIATION_T.uri()).toString();
        assertTrue(prompt.contains("任务对象："));
        assertTrue(prompt.contains("P781-"));
        assertFalse(prompt.contains("任务上下文："));
    }

    @Test void unknownLiteralItemCannotBeReplacedByARecognizableDescription() {
        var reply = assertInstanceOf(NegotiationReply.Send.class,
                new NegotiationStrategy(env).resolve(request(SpnCasePrompts.privateLineComplaintData(),
                        List.of(new NegotiationItem("其他用户的信息", "本地市实际接入端口名称")))).join());
        assertEquals(NegotiationPerformative.ABORT,
                A2atMessages.contextOf(new ReceivedMessage(reply.content(), Map.of(), List.of())).performative());
    }

    private String answer(Map<String, Object> input) {
        var reply = assertInstanceOf(NegotiationReply.Send.class,
                new NegotiationStrategy(env).resolve(request(input)).join());
        return (String) reply.content().metadata().get(A2ATExtension.NEGOTIATION_T.uri());
    }

    private NegotiationRequest request(Map<String, Object> input) {
        return request(input, List.of(new NegotiationItem("接入端口名称", "补充实际端口"),
                new NegotiationItem("投诉分类", "补充实际分类")));
    }

    private NegotiationRequest request(Map<String, Object> input, List<NegotiationItem> items) {
        var sdk = A2ATInitialization.create(() -> new A2ATClient(java.nio.file.Path.of(env)));
        var generated = sdk.generateNegotiationProposePromptFromData(new NegotiationProposeData(
                new NegotiationContext(UUID.randomUUID().toString(), 1, 3, NegotiationPerformative.PROPOSE),
                new InformationProposeContent(items, "AND")),
                StandardTemplates.INFORMATION_NEGOTIATION_PROPOSE.uri());
        var received = new ReceivedMessage(A2atMessages.from(generated, List.of()), Map.of(), List.of());
        return new NegotiationRequest(TaskRequest.builder().taskId(UUID.randomUUID().toString()).agentName("city")
                .input(BusinessInput.data(input)).build(), MessageContent.text("diagnose"), received,
                List.of(), java.time.Duration.ofSeconds(10));
    }

    @Test void hostCanChooseNaturalLanguageOrStructuredTaskGeneration() {
        var sdk = A2ATInitialization.create(() -> new A2ATClient(java.nio.file.Path.of(env)));
        var data = SpnCasePrompts.privateLineComplaintData();
        String text = "请进行投诉诊断。任务对象：" + data.get("任务对象")
                + "；任务上下文：" + data.get("任务上下文");
        for (var generated : List.of(
                sdk.generateTaskPromptFromText(text, StandardTemplates.PRIVATE_LINE_COMPLAINT.uri()),
                sdk.generateTaskPromptFromDataWithSchema(data, SpnCasePrompts.privateLineComplaintSchema(),
                        StandardTemplates.PRIVATE_LINE_COMPLAINT.uri()))) {
            MessageContent content = A2atMessages.from(generated,
                    List.of(new org.a2aproject.sdk.spec.TextPart("business-owned accompanying text")));
            assertEquals(generated.buildMetadataContent(), content.metadata());
            assertEquals(Set.of(A2ATExtension.TASK_T.uri()), content.extensions());
            assertTrue(generated.promptText().contains("P781-"));
        }
    }
}
