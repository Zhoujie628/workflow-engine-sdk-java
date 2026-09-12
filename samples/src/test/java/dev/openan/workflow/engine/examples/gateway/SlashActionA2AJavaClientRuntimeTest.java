/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 *    Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package dev.openan.workflow.engine.examples.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SlashActionA2AJavaClientRuntimeTest {

  @ParameterizedTest
  @CsvSource({
    "https://msb.example/a2a/json/message:send, https://msb.example/a2a/json/message/send",
    "https://msb.example/a2a/json/message:stream, https://msb.example/a2a/json/message/stream",
    "https://msb.example/a2a/json/tasks/t-1:cancel, https://msb.example/a2a/json/tasks/t-1/cancel",
    "https://msb.example/a2a/json/tasks/t-1:subscribe, https://msb.example/a2a/json/tasks/t-1/subscribe",
    "https://msb.example/a2a/json/tasks/t-1, https://msb.example/a2a/json/tasks/t-1",
    "https://msb.example/a2a/json/not-a-task:cancel, https://msb.example/a2a/json/not-a-task:cancel",
    "https://msb.example/a2a/json/message:stream?tenant=x#part, https://msb.example/a2a/json/message/stream?tenant=x#part"
  })
  void rewritesOnlyA2aActionSeparators(String input, String expected) {
    assertEquals(expected, SlashActionA2AJavaClientRuntime.rewriteActionUrl(input));
  }
}
