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

import dev.openan.workflow.engine.registry.HttpAuthProvider;
import dev.openan.workflow.engine.registry.OrchestrationAccountAuth;
import dev.openan.workflow.engine.registry.TlsPolicy;

/** Sample composition only: center account authentication is independent of the agent transport. */
public final class OrchestrationAccess {
  private OrchestrationAccess() {}

  /** Builds one shared provider; never silently chooses between two configured identities. */
  public static HttpAuthProvider authentication(WorkbenchClientProperties properties, TlsPolicy tls) {
    boolean username = present(properties.getOrchUsername());
    boolean password = present(properties.getOrchPassword());
    if (username || password) {
      if (!username || !password) {
        throw new IllegalArgumentException("Configure both ORCH_USERNAME and ORCH_PASSWORD");
      }
      if (present(properties.getOrchApiToken())) {
        throw new IllegalArgumentException("Choose account login or ORCH_API_TOKEN, not both");
      }
      return new OrchestrationAccountAuth(properties.getOrchUrl(), properties.getOrchUsername(),
          properties.getOrchPassword(), tls);
    }
    // Externally supplied Bearer remains explicit. Missing credentials fail at request time;
    // constructing an offline sample neither contacts the center nor acquires a token.
    return HttpAuthProvider.bearer(properties::getOrchApiToken);
  }

  private static boolean present(String value) {
    return value != null && !value.isBlank();
  }
}
