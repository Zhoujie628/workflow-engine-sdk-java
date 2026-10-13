/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */
package dev.openan.workflow.engine.examples.config;

import static org.junit.jupiter.api.Assertions.*;

import dev.openan.workflow.engine.registry.OrchestrationAccountAuth;
import dev.openan.workflow.engine.registry.TlsPolicy;
import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

class OrchestrationAccessTest {
  @Test
  void bindsLoginAccountIndependentlyOfAgentCredentialsAndBuildsLazyProvider() {
    var environment = new StandardEnvironment();
    environment.getPropertySources().addFirst(new MapPropertySource("accounts", Map.of(
        "a2a.orch-username", "operator", "a2a.orch-password", "synthetic-password",
        "a2a.orch-url", "https://127.0.0.1:1", "a2a.orch-verify-server", "false",
        "a2a.ssl-verify", "true")));
    var properties = Binder.get(environment).bind("a2a", Bindable.of(WorkbenchClientProperties.class))
        .orElseThrow(AssertionError::new);
    assertEquals("operator", properties.getOrchUsername());
    assertEquals("synthetic-password", properties.getOrchPassword());
    assertTrue(properties.isSslVerify());
    assertFalse(properties.isOrchVerifyServer());
    assertInstanceOf(OrchestrationAccountAuth.class,
        OrchestrationAccess.authentication(properties, TlsPolicy.insecure()));
  }

  @Test
  void ambiguousOrIncompleteConfigurationFailsInsteadOfUsingAnotherIdentity() {
    var properties = new WorkbenchClientProperties();
    properties.setOrchUsername("operator");
    assertThrows(IllegalArgumentException.class,
        () -> OrchestrationAccess.authentication(properties, TlsPolicy.strict()));
    properties.setOrchPassword("synthetic-password");
    properties.setOrchApiToken("external-token");
    assertThrows(IllegalArgumentException.class,
        () -> OrchestrationAccess.authentication(properties, TlsPolicy.strict()));
  }

  @Test
  void externalTokenIsExplicitAndMissingCredentialsNeverBecomeAnonymous() {
    var properties = new WorkbenchClientProperties();
    var auth = OrchestrationAccess.authentication(properties, TlsPolicy.strict());
    URI uri = URI.create(properties.getOrchUrl() + "/api/v1/orchestrate/search");
    assertThrows(IllegalArgumentException.class, () -> auth.headers(uri));
    properties.setOrchApiToken("external-token");
    assertEquals(Map.of("Authorization", "Bearer external-token"), auth.headers(uri));
    assertFalse(auth.onUnauthorized(uri, auth.headers(uri)));
  }
}
