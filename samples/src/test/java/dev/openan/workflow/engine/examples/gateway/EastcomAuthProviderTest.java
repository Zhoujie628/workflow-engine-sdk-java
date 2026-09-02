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
package dev.openan.workflow.engine.examples.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

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
