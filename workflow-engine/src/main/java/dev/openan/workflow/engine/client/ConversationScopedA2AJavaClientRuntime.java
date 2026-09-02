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

import org.a2aproject.sdk.spec.AgentCard;

/**
 * Optional capability for runtimes whose transport session spans multiple A2A requests.
 *
 * <p>An A2A negotiation uses a second HTTP/SSE request, but remains part of the same logical
 * conversation. Implementations can use this lifecycle callback to keep an authenticated gateway
 * session alive across all negotiation rounds and release it only after the complete conversation.
 */
public interface ConversationScopedA2AJavaClientRuntime {

  /** Release resources retained for the completed logical conversation. */
  void closeConversation(AgentCard agentCard, String contextId);
}
