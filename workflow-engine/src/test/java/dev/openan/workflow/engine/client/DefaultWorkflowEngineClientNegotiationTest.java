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
import net.openan.a2at.sdk.negotiation.content.TargetEndingContent;

import org.junit.jupiter.api.Test;

import java.util.Map;

class DefaultWorkflowEngineClientNegotiationTest {

    @Test
    void currentFromDataAcceptRequiresAtLeastOneConcreteStringItem() throws Exception {
        var items = DefaultWorkflowEngineClient.parseNegotiationItems(
                "{\"任务对象\":\"P781-17\"}");

        assertEquals(1, items.size());
        assertEquals("任务对象", items.get(0).name());
        assertEquals("P781-17", items.get(0).value());
        assertThrows(
                IllegalArgumentException.class,
                () -> DefaultWorkflowEngineClient.parseNegotiationItems("{}"));
        assertThrows(
                IllegalArgumentException.class,
                () -> DefaultWorkflowEngineClient.parseNegotiationItems(
                        "{\"任务对象\":null}"));
        assertThrows(
                IllegalArgumentException.class,
                () -> DefaultWorkflowEngineClient.parseNegotiationItems(
                        "{\"任务对象\":\"null\"}"));
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
    void targetAndFeasibilityFromDataAcceptUseTheirTypedContent() throws Exception {
        TargetEndingContent target =
                assertInstanceOf(
                        TargetEndingContent.class,
                        DefaultWorkflowEngineClient.buildAcceptEndingContent(
                                StandardTemplates.TARGET_NEGOTIATION_ACCEPT_REJECT,
                                "{\"confirmedIntent\":\"诊断指定专线\"}"));
        FeasibilityEndingContent feasibility =
                assertInstanceOf(
                        FeasibilityEndingContent.class,
                        DefaultWorkflowEngineClient.buildAcceptEndingContent(
                                StandardTemplates.FEASIBILITY_NEGOTIATION_ACCEPT_REJECT,
                                "{\"feasibilitySummary\":\"资源与权限满足\"}"));

        assertEquals("诊断指定专线", target.confirmedIntent());
        assertEquals("资源与权限满足", feasibility.feasibilitySummary());
        assertThrows(
                IllegalArgumentException.class,
                () -> DefaultWorkflowEngineClient.buildAcceptEndingContent(
                        StandardTemplates.TARGET_NEGOTIATION_ACCEPT_REJECT,
                        "{\"任务对象\":\"P781\"}"));
    }
}
