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

package dev.openan.workflow.engine.model;

/**
 * Business decision for one conditional outgoing edge. A denied edge is skipped; an allowed edge is
 * activated together with every unconditional edge and every other allowed conditional edge.
 */
public record RouteDecision(boolean allowed, String reason) {
  /**
   * Creates a decision and normalizes a null reason to an empty string.
   */
  public RouteDecision {
    reason = reason == null ? "" : reason;
  }

  /**
   * Allows the edge without an explanatory reason.
   */
  public static RouteDecision allow() {
    return new RouteDecision(true, "");
  }

  /**
   * Allows the edge and records the business reason for observability.
   */
  public static RouteDecision allow(String reason) {
    return new RouteDecision(true, reason);
  }

  /**
   * Denies the edge without an explanatory reason.
   */
  public static RouteDecision deny() {
    return new RouteDecision(false, "");
  }

  /**
   * Denies the edge and records the business reason for observability.
   */
  public static RouteDecision deny(String reason) {
    return new RouteDecision(false, reason);
  }
}
