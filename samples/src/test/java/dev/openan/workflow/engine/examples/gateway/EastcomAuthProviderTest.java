/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.examples.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

class EastcomAuthProviderTest {

    @Test
    void injectsBearerTokenReturnedByTheEastcomTokenService() {
        EastcomAuthProvider provider =
                new EastcomAuthProvider(agent -> "token-for-" + agent, "Authorization", "Bearer");
        Map<String, String> headers = new LinkedHashMap<>();

        provider.applyAuth("city1", null, headers);

        assertEquals("Bearer token-for-city1", headers.get("Authorization"));
    }

    @Test
    void doesNotDuplicateSchemeAlreadyPresentInTheReturnedHeader() {
        EastcomAuthProvider provider =
                new EastcomAuthProvider(agent -> "Bearer abc", "Authorization", "Bearer");
        Map<String, String> headers = new LinkedHashMap<>();

        provider.applyAuth("city1", null, headers);

        assertEquals("Bearer abc", headers.get("Authorization"));
    }

    @Test
    void rejectsAConflictingAuthenticationHeader() {
        EastcomAuthProvider provider =
                new EastcomAuthProvider(agent -> "new-token", "Authorization", "Bearer");
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("authorization", "Bearer old-token");

        assertThrows(SecurityException.class, () -> provider.applyAuth("city1", null, headers));
    }

    @Test
    void reusesTheExistingHeaderCasingWhenTheValueMatches() {
        EastcomAuthProvider provider =
                new EastcomAuthProvider(agent -> "abc", "Authorization", "Bearer");
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("authorization", "Bearer abc");

        provider.applyAuth("city1", null, headers);

        assertEquals(Map.of("authorization", "Bearer abc"), headers);
    }
}
