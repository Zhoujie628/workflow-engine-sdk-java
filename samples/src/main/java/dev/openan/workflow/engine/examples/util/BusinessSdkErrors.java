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

package dev.openan.workflow.engine.examples.util;

import dev.openan.workflow.engine.model.BusinessFailure;
import java.util.Map;
import java.util.function.Supplier;
import net.openan.a2at.sdk.core.exception.A2ATError;

/**
 * Host-owned SDK error translation. Raw provider errors/input/configuration never cross the
 * callback boundary.
 */
public final class BusinessSdkErrors {
  private BusinessSdkErrors() {}

  public static <T> T call(String operation, Supplier<T> action) {
    try {
      return action.get();
    } catch (A2ATError error) {
      throw new BusinessFailure(
          error.getCode(),
          "A2A-T content operation failed: " + operation,
          Map.of("operation", operation));
    }
  }
}
