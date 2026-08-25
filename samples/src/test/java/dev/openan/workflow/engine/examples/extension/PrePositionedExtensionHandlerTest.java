/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.examples.extension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.openan.workflow.engine.examples.demo.SpnCasePrompts;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import net.openan.a2at.sdk.core.model.StandardTemplates;
import net.openan.a2at.sdk.core.resources.ClasspathResourceStreams;

class PrePositionedExtensionHandlerTest {

    @Test
    void sampleOverridesTheSdkValidatorPromptToMatchItsRenderedMessageContract()
            throws IOException {
        try (InputStream stream = ClasspathResourceStreams.open(
                "prompt_resources/prompts/content_validation/zh-CN/system.md")) {
            assertNotNull(stream);
            String prompt = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(prompt.contains("渲染器会有意删除"));
            assertTrue(prompt.contains("无明确反证则通过"));
        }
    }

    @Test
    void validationUsesTheCompleteSlotSchemaBundledByTheActiveSdk() {
        Map<String, Object> schema = SdkSlotSchemaLoader.load(
                StandardTemplates.AUTHORIZATION_POLICY_MANAGEMENT, "zh-CN");
        assertEquals("https://json-schema.org/draft/2020-12/schema", schema.get("$schema"));
        assertEquals(Boolean.FALSE, schema.get("additionalProperties"));
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> operation =
                (Map<String, Object>) properties.get(AuthorizationPolicy.OPERATION_TYPE_FIELD);
        assertFalse(String.valueOf(operation.get("description")).isBlank());
        assertNotNull(operation.get("x-a2at-value-constraint"));

        Map<String, Object> notificationSchema =
                SdkSlotSchemaLoader.load(StandardTemplates.SERVICE_RECOVERY, "zh-CN");
        assertEquals(
                List.of(NotificationPolicy.REPORT_FORMAT_FIELD),
                notificationSchema.get("required"));
    }

    @Test
    void sdkExtractionMustExactlyMatchValuesInRenderedSections() {
        String prompt =
                "## 授权策略的操作类型\n"
                        + "新增授权策略\n\n"
                        + "## 动网操作的授权策略列表\n"
                        + "业务投诉诊断/业务抢通/隧道调优/2026-06-01~2030-06-18\n";
        Map<String, Object> exact = SpnCasePrompts.addAuthorizationData();

        PrePositionedExtensionHandler.requireExtractedSectionsMatch(
                prompt,
                exact,
                AuthorizationPolicy.OPERATION_TYPE_FIELD,
                AuthorizationPolicy.POLICY_LIST_FIELD);

        Map<String, Object> wrongOperation = new java.util.LinkedHashMap<>(exact);
        wrongOperation.put(
                AuthorizationPolicy.OPERATION_TYPE_FIELD, AuthorizationPolicy.QUERY);
        assertThrows(
                IllegalArgumentException.class,
                () -> PrePositionedExtensionHandler.requireExtractedSectionsMatch(
                        prompt,
                        wrongOperation,
                        AuthorizationPolicy.OPERATION_TYPE_FIELD,
                        AuthorizationPolicy.POLICY_LIST_FIELD));
    }

    @Test
    void sdkExtractionCannotHallucinateAnEmptyNotificationCondition() {
        String prompt =
                "## 订阅条件\n\n## 上报通知数据格式\n业务抢通事件数据包含执行状态\n";
        assertThrows(
                IllegalArgumentException.class,
                () -> PrePositionedExtensionHandler.requireExtractedSectionsMatch(
                        prompt,
                        Map.of(
                                NotificationPolicy.CONDITION_FIELD,
                                "xx子网",
                                NotificationPolicy.REPORT_FORMAT_FIELD,
                                "业务抢通事件数据包含执行状态"),
                        NotificationPolicy.CONDITION_FIELD,
                        NotificationPolicy.REPORT_FORMAT_FIELD));
    }

    @Test
    void queryIsSideEffectFreeAndDeleteClearsTheDemoWhitelist() {
        PrePositionedExtensionHandler handler = new PrePositionedExtensionHandler();
        handler.applyAuthorization(SpnCasePrompts.addAuthorizationData(), "template", "test");
        AuthorizationPolicy added = handler.getAuthorizationPolicy();
        assertNotNull(added);

        handler.applyAuthorization(
                Map.of(AuthorizationPolicy.OPERATION_TYPE_FIELD, AuthorizationPolicy.QUERY),
                "template",
                "test");
        assertSame(added, handler.getAuthorizationPolicy());

        handler.applyAuthorization(
                Map.of(
                        AuthorizationPolicy.OPERATION_TYPE_FIELD,
                        AuthorizationPolicy.DELETE,
                        AuthorizationPolicy.POLICY_LIST_FIELD,
                        "policy-id"),
                "template",
                "test");
        assertNull(handler.getAuthorizationPolicy());
    }

    @Test
    void deleteWithoutASelectorFailsClosedAndKeepsTheExistingWhitelist() {
        PrePositionedExtensionHandler handler = new PrePositionedExtensionHandler();
        handler.applyAuthorization(SpnCasePrompts.addAuthorizationData(), "template", "test");
        AuthorizationPolicy existing = handler.getAuthorizationPolicy();

        assertThrows(
                IllegalArgumentException.class,
                () -> handler.applyAuthorization(
                        Map.of(
                                AuthorizationPolicy.OPERATION_TYPE_FIELD,
                                AuthorizationPolicy.DELETE),
                        "template",
                        "test"));
        assertSame(existing, handler.getAuthorizationPolicy());
    }
}
