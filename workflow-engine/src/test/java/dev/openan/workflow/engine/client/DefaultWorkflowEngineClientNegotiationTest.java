/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import net.openan.a2at.sdk.core.model.MetadataContent;
import net.openan.a2at.sdk.core.model.StandardTemplates;
import net.openan.a2at.sdk.negotiation.content.FeasibilityEndingContent;
import net.openan.a2at.sdk.negotiation.content.InformationEndingContent;
import net.openan.a2at.sdk.negotiation.content.NegotiationConclusion;
import net.openan.a2at.sdk.negotiation.content.TargetEndingContent;

import org.junit.jupiter.api.Test;

import java.util.Map;

class DefaultWorkflowEngineClientNegotiationTest {

    @Test
    void currentFromDataEndingUsesConcreteTypedItems() {
        InformationEndingContent content =
                assertInstanceOf(
                        InformationEndingContent.class,
                        DefaultWorkflowEngineClient.buildEndingContent(
                                StandardTemplates.INFORMATION_NEGOTIATION_ACCEPT_REJECT,
                                Map.of("任务对象", "P781-17"),
                                NegotiationConclusion.ACCEPT));

        assertEquals(NegotiationConclusion.ACCEPT, content.conclusion());
        assertEquals(1, content.items().size());
        assertEquals("任务对象", content.items().get(0).name());
        assertEquals("P781-17", content.items().get(0).value());
    }

    @Test
    void endingTemplateMatchesTheReceivedCurrentProposeType() {
        assertEquals(
                StandardTemplates.INFORMATION_NEGOTIATION_ACCEPT_REJECT,
                DefaultWorkflowEngineClient.endingTemplateFor(Map.of(
                        MetadataContent.TEMPLATE_URI_METADATA_KEY,
                        StandardTemplates.INFORMATION_NEGOTIATION_PROPOSE.uri())));
        assertEquals(
                StandardTemplates.TARGET_NEGOTIATION_ACCEPT_REJECT,
                DefaultWorkflowEngineClient.endingTemplateFor(Map.of(
                        MetadataContent.TEMPLATE_URI_METADATA_KEY,
                        StandardTemplates.TARGET_NEGOTIATION_PROPOSE.uri())));
        assertEquals(
                StandardTemplates.FEASIBILITY_NEGOTIATION_ACCEPT_REJECT,
                DefaultWorkflowEngineClient.endingTemplateFor(Map.of(
                        MetadataContent.TEMPLATE_URI_METADATA_KEY,
                        StandardTemplates.FEASIBILITY_NEGOTIATION_PROPOSE.uri())));
        assertThrows(
                IllegalArgumentException.class,
                () -> DefaultWorkflowEngineClient.endingTemplateFor(Map.of()));
    }

    @Test
    void targetAndFeasibilityFromDataUseTheirTypedContent() {
        TargetEndingContent target =
                assertInstanceOf(
                        TargetEndingContent.class,
                        DefaultWorkflowEngineClient.buildEndingContent(
                                StandardTemplates.TARGET_NEGOTIATION_ACCEPT_REJECT,
                                Map.of("confirmedIntent", "诊断指定专线"),
                                NegotiationConclusion.REJECT));
        FeasibilityEndingContent feasibility =
                assertInstanceOf(
                        FeasibilityEndingContent.class,
                        DefaultWorkflowEngineClient.buildEndingContent(
                                StandardTemplates.FEASIBILITY_NEGOTIATION_ACCEPT_REJECT,
                                Map.of("feasibilitySummary", "资源与权限满足"),
                                NegotiationConclusion.ACCEPT));

        assertEquals(NegotiationConclusion.REJECT, target.conclusion());
        assertEquals("诊断指定专线", target.confirmedIntent());
        assertEquals("资源与权限满足", feasibility.feasibilitySummary());
        assertThrows(
                IllegalArgumentException.class,
                () -> DefaultWorkflowEngineClient.buildEndingContent(
                        StandardTemplates.TARGET_NEGOTIATION_ACCEPT_REJECT,
                        Map.of("任务对象", "P781"),
                        NegotiationConclusion.ACCEPT));
    }
}
