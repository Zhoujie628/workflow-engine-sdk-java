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
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import net.openan.a2at.sdk.core.model.StandardTemplates;
import net.openan.a2at.sdk.core.resources.ClasspathResourceStreams;

class PrePositionedExtensionHandlerTest {

    @Test
    void validationUsesTheCurrentSdkPromptWithoutSampleResourceShadowing()
            throws IOException {
        String resourcePath = "prompt_resources/prompts/content_validation/zh-CN/system.md";
        URL resource = ClasspathResourceStreams.class.getClassLoader().getResource(resourcePath);
        assertNotNull(resource);
        assertTrue(resource.toExternalForm().contains("a2a-t-resources"));
        try (InputStream stream = ClasspathResourceStreams.open(
                resourcePath)) {
            assertNotNull(stream);
            String prompt = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(prompt.contains("输入内容的结构"));
            assertTrue(prompt.contains("条目级检查"));
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
        assertFalse(((List<?>) operation.get("examples")).isEmpty());
        assertTrue(
                String.valueOf(operation.get("x-a2at-value-constraint"))
                        .contains("导出"));
        assertEquals(schema, SpnCasePrompts.authorizationSchema());

        Map<String, Object> notificationSchema =
                SdkSlotSchemaLoader.load(StandardTemplates.SERVICE_RECOVERY, "zh-CN");
        assertEquals(
                List.of(NotificationPolicy.REPORT_FORMAT_FIELD),
                notificationSchema.get("required"));
        assertEquals(notificationSchema, SpnCasePrompts.serviceRecoverySchema());
        assertEquals(
                SdkSlotSchemaLoader.load(StandardTemplates.PRIVATE_LINE_COMPLAINT, "zh-CN"),
                SpnCasePrompts.privateLineComplaintSchema());
    }

    @Test
    void sdkExtractionMustExactlyMatchValuesInRenderedSections() {
        String prompt =
                "## 授权策略的操作类型\n"
                        + "新增授权策略\n\n"
                        + "## 动网操作的授权策略列表\n"
                        + "1. 业务场景是业务投诉诊断，处置类型是业务抢通，操作名称是隧道调优，有效期是2026-06-01~2030-06-18\n";
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
        PrePositionedExtensionHandler.AuthorizationOperationResult addResult =
                handler.applyAuthorization(
                        SpnCasePrompts.addAuthorizationData(), "template", "test");
        AuthorizationPolicy added = handler.getAuthorizationPolicy();
        assertNotNull(added);
        assertTrue(addResult.metadataText().contains("授权操作执行结果：成功"));
        assertTrue(
                addResult.metadataText()
                        .contains("7d8c7b00-3c8c-4f8e-9b1e-9b17b6a3e5c3"));

        PrePositionedExtensionHandler.AuthorizationOperationResult queryResult =
                handler.applyAuthorization(
                        Map.of(AuthorizationPolicy.OPERATION_TYPE_FIELD, AuthorizationPolicy.QUERY),
                        "template",
                        "test");
        assertSame(added, handler.getAuthorizationPolicy());
        assertTrue(queryResult.metadataText().contains("动网操作的授权策略列表"));

        handler.applyAuthorization(
                Map.of(
                        AuthorizationPolicy.OPERATION_TYPE_FIELD,
                        AuthorizationPolicy.DELETE,
                        AuthorizationPolicy.POLICY_LIST_FIELD,
                        "1. 策略标识是7d8c7b00-3c8c-4f8e-9b1e-9b17b6a3e5c3"),
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

    @Test
    void deleteMustTargetTheExactActivePolicyIdentifier() {
        PrePositionedExtensionHandler handler = new PrePositionedExtensionHandler();
        handler.applyAuthorization(SpnCasePrompts.addAuthorizationData(), "template", "test");
        AuthorizationPolicy existing = handler.getAuthorizationPolicy();

        assertThrows(
                IllegalArgumentException.class,
                () -> handler.applyAuthorization(
                        Map.of(
                                AuthorizationPolicy.OPERATION_TYPE_FIELD,
                                AuthorizationPolicy.DELETE,
                                AuthorizationPolicy.POLICY_LIST_FIELD,
                                "1. 策略标识是7d8c7b00-3c8c-4f8e-9b1e-9b17b6a3e5c4"),
                        "template",
                        "test"));
        assertSame(existing, handler.getAuthorizationPolicy());
    }

    @Test
    void aMalformedSecondRuleCannotPartiallyReplaceTheExistingWhitelist() {
        PrePositionedExtensionHandler handler = new PrePositionedExtensionHandler();
        handler.applyAuthorization(SpnCasePrompts.addAuthorizationData(), "template", "test");
        AuthorizationPolicy existing = handler.getAuthorizationPolicy();
        String rules = SpnCasePrompts.addAuthorizationData().get(AuthorizationPolicy.POLICY_LIST_FIELD)
                + "\n2. 业务场景是其他，处置类型是业务抢通，操作名称是任意操作，有效期是2026-02-30~2030-06-18";
        assertThrows(IllegalArgumentException.class, () -> handler.applyAuthorization(
                Map.of(AuthorizationPolicy.OPERATION_TYPE_FIELD, AuthorizationPolicy.ADD,
                        AuthorizationPolicy.POLICY_LIST_FIELD, rules), "template", "test"));
        assertSame(existing, handler.getAuthorizationPolicy());
    }

    @Test
    void unsupportedQueryConditionsAreNotSilentlyIgnored() {
        PrePositionedExtensionHandler handler = new PrePositionedExtensionHandler();
        handler.applyAuthorization(SpnCasePrompts.addAuthorizationData(), "template", "test");
        AuthorizationPolicy existing = handler.getAuthorizationPolicy();
        assertThrows(UnsupportedOperationException.class, () -> handler.applyAuthorization(
                Map.of(AuthorizationPolicy.OPERATION_TYPE_FIELD, AuthorizationPolicy.QUERY,
                        AuthorizationPolicy.POLICY_LIST_FIELD, "1. 业务场景是其他"),
                "template", "test"));
        assertSame(existing, handler.getAuthorizationPolicy());
    }

    @Test
    void deleteCannotTreatBareIdsOrMultipleSelectorsAsTheActiveIdentifier() {
        PrePositionedExtensionHandler handler = new PrePositionedExtensionHandler();
        handler.applyAuthorization(SpnCasePrompts.addAuthorizationData(), "template", "test");
        AuthorizationPolicy existing = handler.getAuthorizationPolicy();
        String id = "7d8c7b00-3c8c-4f8e-9b1e-9b17b6a3e5c3";
        for (String selector : List.of(id, "1. 业务场景是业务投诉诊断",
                "1. 策略标识是" + id + "\n2. 策略标识是" + id)) {
            assertThrows(IllegalArgumentException.class, () -> handler.applyAuthorization(
                    Map.of(AuthorizationPolicy.OPERATION_TYPE_FIELD, AuthorizationPolicy.DELETE,
                            AuthorizationPolicy.POLICY_LIST_FIELD, selector), "template", "test"));
        }
        assertSame(existing, handler.getAuthorizationPolicy());
    }

    @Test
    void modifyRequiresAnIntegratingPolicyStoreAndKeepsTheExistingWhitelist() {
        PrePositionedExtensionHandler handler = new PrePositionedExtensionHandler();
        handler.applyAuthorization(SpnCasePrompts.addAuthorizationData(), "template", "test");
        AuthorizationPolicy existing = handler.getAuthorizationPolicy();

        assertThrows(
                UnsupportedOperationException.class,
                () -> handler.applyAuthorization(
                        Map.of(
                                AuthorizationPolicy.OPERATION_TYPE_FIELD,
                                AuthorizationPolicy.MODIFY,
                                AuthorizationPolicy.POLICY_LIST_FIELD,
                                "1. 策略标识是7d8c7b00-3c8c-4f8e-9b1e-9b17b6a3e5c3，有效期是永久生效"),
                        "template",
                        "test"));
        assertSame(existing, handler.getAuthorizationPolicy());
    }
}
