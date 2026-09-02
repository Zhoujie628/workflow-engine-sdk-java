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
package dev.openan.workflow.engine.client;

import java.util.Map;
import org.a2aproject.sdk.spec.AgentCard;

/**
 * Supplies one isolated group of outbound A2A headers. Contributors return their own map so the
 * factory can reject conflicting values instead of relying on mutation order.
 */
interface HeaderContributor {

  Map<String, String> contribute(
      AgentCard agentCard,
      String agentName,
      Map<String, Object> messageMetadata,
      Map<String, String> currentHeaders);
}
