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

package dev.openan.workflow.engine.examples.demo;

import dev.openan.workflow.engine.examples.testsupport.OfflineA2ATLlmClient;

/**
 * Same complete Spring demo through the real vendor SDK and local instruction-platform simulator.
 */
class SpringSpnDemoOrderE2ETest extends SpringSpnDemoE2ETest {
  @Override
  protected String negotiationWireBoundary() {
    return "ORDER_FORWARD_REQUEST";
  }

  @Override
  protected String[] arguments() {
    return new String[] {
      "--a2a.transport-mode=order",
      "--a2a.embedded-omc-enabled=true",
      "--a2a.orch-url=http://127.0.0.1:1",
      "--a2a.a2at-env-path=" + OfflineA2ATLlmClient.envPath(),
      "--a2a.order.simulator-enabled=true",
      "--a2a.order.host=127.0.0.1",
      "--a2a.order.port=26401",
      "--a2a.order.username=sim-user",
      "--a2a.order.password=sim-password",
      "--a2a.order.client-id=sim-client",
      "--a2a.order.client-secret=sim-secret",
      "--a2a.order.city1-ne=sim-city1",
      "--a2a.order.city2-ne=sim-city2",
      "--a2a.order.simulator-city1-target-url=https://127.0.0.1:26335",
      "--a2a.order.simulator-city2-target-url=https://127.0.0.1:26336",
      "--a2a.order.omc-auth-enabled=true",
      "--a2a.order.omc-credentials-path=classpath:spn_agent_credentials.json",
      "--a2a.order.timeout-seconds=30"
    };
  }
}
