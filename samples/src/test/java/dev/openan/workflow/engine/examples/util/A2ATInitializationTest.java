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

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class A2ATInitializationTest {
  @Test
  void restoresContextClassLoaderAfterSuccessAndFailure() {
    var original = Thread.currentThread().getContextClassLoader();
    assertEquals(
        "created",
        A2ATInitialization.create(
            () -> {
              assertNotSame(original, Thread.currentThread().getContextClassLoader());
              return "created";
            }));
    assertSame(original, Thread.currentThread().getContextClassLoader());
    assertThrows(
        IllegalStateException.class,
        () ->
            A2ATInitialization.create(
                () -> {
                  throw new IllegalStateException("failure");
                }));
    assertSame(original, Thread.currentThread().getContextClassLoader());
  }
}
