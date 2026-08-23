/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 *    Licensed under the Apache License, Version 2.0 (the License); you may
 *    not use this file except in compliance with the License. You may obtain
 *    a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an AS IS BASIS, WITHOUT
 *    WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *    License for the specific language governing permissions and limitations
 *    under the License.
 */

package dev.openan.workflow.engine.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import net.openan.a2at.sdk.core.model.StandardTemplates;
import net.openan.a2at.sdk.negotiation.content.NegotiationAbortData;
import net.openan.a2at.sdk.negotiation.content.NegotiationAbortContent;
import net.openan.a2at.sdk.core.model.NegotiationContext;
import net.openan.a2at.sdk.negotiation.content.NegotiationEndingData;
import net.openan.a2at.sdk.negotiation.content.InformationEndingContent;
import net.openan.a2at.sdk.negotiation.content.InformationProposeContent;
import net.openan.a2at.sdk.negotiation.content.NegotiationConclusion;
import net.openan.a2at.sdk.negotiation.content.NegotiationItem;
import net.openan.a2at.sdk.negotiation.content.NegotiationProposeData;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/**
 * Tests for {@link A2ATContentFacade} against the real A2A-T SDK: deterministic from-data
 * rendering (no LLM) for propose, accept, reject and abort, plus the metadata shape the engine
 * attaches to A2A messages.
 *
 * <p>These tests exercise the bundled classpath templates of a2a-t-resources; the .env-driven
 * LLM paths (from-text generation, validate-and-fill) are covered by the SDK's own suite and the
 * engine's E2E sample test.
 */
class A2ATContentFacadeTest {

    private static final String ENV_PATH = resolveSampleEnv();

    private static String resolveSampleEnv() {
        // The facade needs a real A2ATClient; use the engine repo's .env when present so the
        // template language resolves to zh-CN. Walk up from the module dir to the repo root.
        java.nio.file.Path dir = java.nio.file.Path.of("").toAbsolutePath();
        while (dir != null) {
            java.nio.file.Path candidate = dir.resolve(".env");
            if (java.nio.file.Files.exists(candidate)) {
                return candidate.toString();
            }
            dir = dir.getParent();
        }
        return null;
    }

    private A2ATContentFacade facade() {
        if (ENV_PATH == null) {
            return null;
        }
        return new A2ATContentFacade(new net.openan.a2at.sdk.client.A2ATClient(java.nio.file.Path.of(ENV_PATH)));
    }

    private static NegotiationContext context() {
        return new NegotiationContext("3dbc13b5-bd57-4c2b-b503-24e381b6c8d3", 1, 5);
    }

    @Test
    void rendersInformationProposeFromData() {
        A2ATContentFacade facade = facade();
        assumeTrue(facade != null, ".env not found; SDK-dependent assertions skipped");
        net.openan.a2at.sdk.core.model.MetadataContent mc =
                facade.generateProposeFromData(
                        new NegotiationProposeData(
                                context(),
                                new InformationProposeContent(
                                        List.of(
                                                new NegotiationItem("接入端口名称", "举例：P533-珠江旧城-PTN3900-23-TPA1EG24-1"),
                                                new NegotiationItem("投诉分类", "举例：专线质差")),
                                        null)),
                        StandardTemplates.INFORMATION_NEGOTIATION_PROPOSE);
        assertNotNull(mc.promptText());
        // Since the SDK moved the context out of templates, the propose text carries only the
        // business sections; the context travels in the negotiationContext metadata key.
        assertTrue(mc.promptText().contains("信息协商"), "propose must render the info section");
        assertTrue(mc.promptText().contains("接入端口名称"));
        assertFalse(mc.promptText().contains("协商上下文"), "context section must not render");
        assertEquals(
                "Negotiation-T/information-negotiation/propose/v1",
                mc.templateUri());
        Map<String, Object> meta = A2ATContentFacade.toMetadata(mc);
        assertEquals(mc.promptText(), meta.get(net.openan.a2at.sdk.core.model.ExtensionUriConstants.NEGOTIATION_T_EXTENSION_URI));
        assertEquals(
                "Negotiation-T/information-negotiation/propose/v1",
                meta.get(net.openan.a2at.sdk.core.model.MetadataContent.TEMPLATE_URI_METADATA_KEY));
        Object ctx = meta.get(net.openan.a2at.sdk.core.model.MetadataContent.NEGOTIATION_CONTEXT_METADATA_KEY);
        assertTrue(ctx instanceof Map, "context must travel in metadata");
        assertEquals("3dbc13b5-bd57-4c2b-b503-24e381b6c8d3", ((Map<?, ?>) ctx).get("id"));
        assertEquals(1, ((Map<?, ?>) ctx).get("round"));
    }

    @Test
    void rendersAcceptFromData() {
        A2ATContentFacade facade = facade();
        assumeTrue(facade != null, ".env not found; SDK-dependent assertions skipped");
        net.openan.a2at.sdk.core.model.MetadataContent mc =
                facade.generateAcceptFromData(
                        new NegotiationEndingData(
                                context(),
                                new InformationEndingContent(
                                        NegotiationConclusion.ACCEPT,
                                        List.of(new NegotiationItem("接入端口名称", "P533-珠江旧城-PTN3900-23-TPA1EG24-1")))),
                        StandardTemplates.INFORMATION_NEGOTIATION_ACCEPT_REJECT);
        assertTrue(mc.promptText().contains("Accept"), "accept must carry the Accept conclusion");
        // The round travels in metadata, not in the rendered text.
        assertFalse(mc.promptText().contains("round: 1"));
    }

    @Test
    void rendersRejectFromData() {
        A2ATContentFacade facade = facade();
        assumeTrue(facade != null, ".env not found; SDK-dependent assertions skipped");
        net.openan.a2at.sdk.core.model.MetadataContent mc =
                facade.generateRejectFromData(
                        new NegotiationEndingData(
                                context(),
                                new InformationEndingContent(
                                        NegotiationConclusion.REJECT,
                                        List.of(new NegotiationItem("投诉分类", "无法提供，原因：数据源不可用")))),
                        StandardTemplates.INFORMATION_NEGOTIATION_ACCEPT_REJECT);
        assertTrue(mc.promptText().contains("Reject"), "reject must carry the Reject conclusion");
    }

    @Test
    void rendersAbortFromData() {
        A2ATContentFacade facade = facade();
        assumeTrue(facade != null, ".env not found; SDK-dependent assertions skipped");
        net.openan.a2at.sdk.core.model.MetadataContent mc =
                facade.generateAbortFromData(
                        new NegotiationAbortData(
                                context(), new NegotiationAbortContent("轮次预算耗尽，协商终止")));
        assertEquals("Negotiation-T/common/abort/v1", mc.templateUri());
        assertTrue(mc.promptText().contains("轮次预算耗尽"));
    }

    @Test
    void negotiationTemplateQueriesResolve() {
        A2ATContentFacade facade = facade();
        assumeTrue(facade != null, ".env not found; SDK-dependent assertions skipped");
        var prompts = facade.getNegotiationPrompts();
        // 3 types x 2 phases + common abort
        assertEquals(7, prompts.size(), "bundled negotiation templates must be enumerable");
        assertTrue(
                facade.getNegotiationPrompt(StandardTemplates.INFORMATION_NEGOTIATION_PROPOSE).isPresent());
        assertFalse(
                facade.getPrompt(
                                net.openan.a2at.sdk.core.model.TemplateUri.of("Task-T", "nonexistent-scenario"))
                        .isPresent(),
                "unknown scenario must yield empty");
    }
}
