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

import java.util.List;
import java.util.Map;
import lombok.Builder;
import lombok.Value;

/**
 * Result of one dispatched task as seen by the workflow layer: success flag, ordered business
 * outputs, and failure diagnostics. Remote evidence is retained losslessly in {@code
 * receivedMessages}; when it is present, the convenience {@code outputs} are derived from it.
 */
@Value
@Builder
public class TaskResult {
  boolean success;

  /** Ordered business outputs; values may be text or JSON-serializable structured data. */
  @Builder.Default List<Object> outputs = List.of();

  /**
   * Remote evidence; local onSelfTask outputs leave this empty. When present, {@code outputs} are
   * derived from these messages so conflicting builder-supplied output values are not retained.
   */
  @Builder.Default List<ReceivedMessage> receivedMessages = List.of();

  String error;
  String errorCode;
  @Builder.Default Map<String, Object> errorDetails = java.util.Map.of();

  public TaskResult(
      boolean success,
      List<Object> outputs,
      List<ReceivedMessage> receivedMessages,
      String error,
      String errorCode,
      java.util.Map<String, Object> errorDetails) {
    this.success = success;
    this.receivedMessages = receivedMessages == null ? List.of() : List.copyOf(receivedMessages);
    this.outputs =
        this.receivedMessages.isEmpty()
            ? BusinessValues.list(outputs)
            : this.receivedMessages.stream()
                .flatMap(message -> message.outputs(success).stream())
                .toList();
    this.error = error;
    this.errorCode = errorCode;
    this.errorDetails =
        BusinessValues.map(errorDetails == null ? java.util.Map.of() : errorDetails);
  }

  /** Successful local business output, including an intentionally empty output list. */
  public static TaskResult success(List<Object> outputs) {
    return builder().success(true).outputs(outputs).build();
  }

  /** Business failure; never mixes the error message into the output list. */
  public static TaskResult failure(String code, String message) {
    return builder().success(false).errorCode(code).error(message).build();
  }
}
