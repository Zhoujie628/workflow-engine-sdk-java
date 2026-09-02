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

package dev.openan.workflow.engine.model;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Uninterpreted received content and history for one task's current negotiation session.
 *
 * @throws NullPointerException if any component except {@code previousExchanges} is null
 * @throws IllegalArgumentException if {@code remainingWait} is negative
 */
public record NegotiationRequest(
    TaskRequest task,
    MessageContent originalSubmission,
    ReceivedMessage received,
    List<Exchange> previousExchanges,
    Duration remainingWait) {

  public NegotiationRequest {
    Objects.requireNonNull(task, "task");
    Objects.requireNonNull(originalSubmission, "originalSubmission");
    Objects.requireNonNull(received, "received");
    previousExchanges = List.copyOf(previousExchanges);
    Objects.requireNonNull(remainingWait, "remainingWait");
    if (remainingWait.isNegative()) throw new IllegalArgumentException("Negative remaining wait");
  }

  /** Counterpart identity, not a remote protocol task identifier. */
  public String agentName() {
    return task.getAgentName();
  }

  /** One completed local interaction, without an engine-defined business schema. */
  public record Exchange(ReceivedMessage received, NegotiationReply reply) {
    public Exchange {
      Objects.requireNonNull(received, "received");
      Objects.requireNonNull(reply, "reply");
    }
  }
}
