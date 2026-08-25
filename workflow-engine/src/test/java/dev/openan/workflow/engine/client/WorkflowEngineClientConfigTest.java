/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class WorkflowEngineClientConfigTest {

    @Test
    void rejectsInvalidNegotiationRoundLimit() {
        assertThrows(
                IllegalArgumentException.class,
                () -> WorkflowEngineClientConfig.builder().maxNegotiationRounds(0).build());
    }

    @Test
    @SuppressWarnings("unchecked")
    void deeplySnapshotsNegotiationSchema() {
        List<Object> required = new ArrayList<>(List.of("city"));
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("city", Map.of("type", "string"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("required", required);
        schema.put("properties", properties);

        WorkflowEngineClientConfig config =
                WorkflowEngineClientConfig.builder().negotiationParamSchema(schema).build();
        required.add("port");
        properties.put("port", Map.of("type", "string"));

        Map<String, Object> snapshot = config.getNegotiationParamSchema();
        assertEquals(List.of("city"), snapshot.get("required"));
        assertEquals(1, ((Map<String, Object>) snapshot.get("properties")).size());
        assertThrows(
                UnsupportedOperationException.class,
                () -> ((List<Object>) snapshot.get("required")).add("port"));
    }
}
