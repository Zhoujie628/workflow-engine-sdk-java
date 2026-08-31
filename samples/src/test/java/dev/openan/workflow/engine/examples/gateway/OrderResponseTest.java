/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.examples.gateway;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class OrderResponseTest {
    @Test void preservesStatusBodyAndRepeatedHeadersWithoutSharingMutableContainers() {
        List<String> values = new ArrayList<>(List.of("one", "two"));
        Map<String, List<String>> headers = new LinkedHashMap<>();
        headers.put("X-Multi", values);
        OrderResponse response = new OrderResponse(207, "unchanged body", headers, "sdk-body");
        values.clear();
        headers.clear();
        assertEquals(207, response.status());
        assertEquals("unchanged body", response.body());
        assertEquals(List.of("one", "two"), response.headers().get("X-Multi"));
        assertThrows(UnsupportedOperationException.class, () -> response.headers().put("Other", List.of()));
        assertThrows(UnsupportedOperationException.class, () -> response.headers().get("X-Multi").add("three"));
    }
}
