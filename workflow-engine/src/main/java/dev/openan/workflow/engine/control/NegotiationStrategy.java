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

package dev.openan.workflow.engine.control;

import dev.openan.workflow.engine.model.NegotiationReply;
import dev.openan.workflow.engine.model.NegotiationRequest;
import java.util.concurrent.CompletableFuture;

/**
 * Strategy for making typed negotiation decisions.
 *
 * <p>Single responsibility: when an agent returns INPUT_REQUIRED (Negotiation-T), produce the typed
 * decision to send back. This is a <b>separate concern</b> from workflow orchestration (task
 * dispatch, routing). Users who need custom negotiation logic (LLM-based decisions, DAG-predecessor
 * forwarding, etc.) implement this interface and inject it into {@link DefaultControlPoint} rather
 * than mixing negotiation policy into their ControlPoint class.
 *
 * <p>The SDK's {@link ControlPoint#onNegotiation} remains the entry point for the auto-negotiation
 * loop; {@link DefaultControlPoint} delegates to an injected strategy by default. Users may still
 * override {@code onNegotiation} directly when a full strategy object is overkill.
 */
public interface NegotiationStrategy {

  /**
   * Make a business decision for the given negotiation request.
   *
   * @param request typed business view of the received negotiation request
   * @return future completing with final content to send, or a local stop
   */
  CompletableFuture<NegotiationReply> resolve(NegotiationRequest request);
}
