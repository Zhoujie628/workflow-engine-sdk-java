/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 *    Licensed under the Apache License, Version 2.0 (the License); you may
 *    not use this file except in compliance with the License. You may obtain
 *    a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an AS IS BASIS, WITHOUT
 *    WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *    License for the specific language governing permissions and limitations
 *    under the License.
 */

package dev.openan.workflow.engine.control;

import dev.openan.workflow.engine.model.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/** Business-only callbacks. Implementations must support concurrent calls from different tasks. */
public interface ControlPoint {
  /** Registers only the capabilities the host needs. */
  static Builder builder() {
    return new Builder();
  }

  /** Prepares content; the engine sends after completion. No transport is exposed. */
  default CompletableFuture<MessageContent> onTask(TaskRequest request) {
    return CompletableFuture.failedFuture(
        new IllegalStateException("onTask handler is required for " + request.getStepName()));
  }

  /** Runs a local task. Missing implementation is an error, never an echo-success. */
  default CompletableFuture<TaskResult> onSelfTask(TaskRequest request) {
    return CompletableFuture.failedFuture(
        new IllegalStateException("onSelfTask handler is required for " + request.getStepName()));
  }

  /** Selects a permitted branch. Unconditional edges bypass this callback. */
  default CompletableFuture<RouteDecision> onRoute(RouteRequest request) {
    return CompletableFuture.failedFuture(
        new IllegalStateException("onRoute handler is required for " + request.stepName()));
  }

  /** Answers the proposal. Missing handlers never implicitly consent. */
  default CompletableFuture<NegotiationReply> onNegotiation(NegotiationRequest request) {
    return CompletableFuture.failedFuture(
        new IllegalStateException("onNegotiation handler is required"));
  }

  /** Independent registration; omitted callbacks retain the documented defaults. */
  final class Builder {
    private Function<TaskRequest, CompletableFuture<MessageContent>> task;
    private Function<TaskRequest, CompletableFuture<TaskResult>> self;
    private Function<RouteRequest, CompletableFuture<RouteDecision>> route;
    private Function<NegotiationRequest, CompletableFuture<NegotiationReply>> negotiation;

    public Builder onTask(Function<TaskRequest, CompletableFuture<MessageContent>> handler) {
      task = handler;
      return this;
    }

    public Builder onSelfTask(Function<TaskRequest, CompletableFuture<TaskResult>> handler) {
      self = handler;
      return this;
    }

    public Builder onRoute(Function<RouteRequest, CompletableFuture<RouteDecision>> handler) {
      route = handler;
      return this;
    }

    public Builder onNegotiation(
        Function<NegotiationRequest, CompletableFuture<NegotiationReply>> handler) {
      negotiation = handler;
      return this;
    }

    public ControlPoint build() {
      var t = task;
      var s = self;
      var r = route;
      var n = negotiation;
      return new ControlPoint() {
        public CompletableFuture<MessageContent> onTask(TaskRequest q) {
          return t == null ? ControlPoint.super.onTask(q) : t.apply(q);
        }

        public CompletableFuture<TaskResult> onSelfTask(TaskRequest q) {
          return s == null ? ControlPoint.super.onSelfTask(q) : s.apply(q);
        }

        public CompletableFuture<RouteDecision> onRoute(RouteRequest q) {
          return r == null ? ControlPoint.super.onRoute(q) : r.apply(q);
        }

        public CompletableFuture<NegotiationReply> onNegotiation(NegotiationRequest q) {
          return n == null ? ControlPoint.super.onNegotiation(q) : n.apply(q);
        }
      };
    }
  }
}
