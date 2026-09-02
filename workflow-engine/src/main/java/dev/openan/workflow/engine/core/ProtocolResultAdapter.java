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

package dev.openan.workflow.engine.core;

import dev.openan.workflow.engine.model.SendMessageResult;
import dev.openan.workflow.engine.model.TaskResult;
import org.a2aproject.sdk.spec.TaskState;

/** Maps transport state independently from the presence or absence of business content. */
final class ProtocolResultAdapter {
  private ProtocolResultAdapter() {}

  static TaskResult toTaskResult(SendMessageResult result) {
    if (result == null)
      return TaskResult.failure("remote.no_response", "Agent returned no response");
    String state = result.getTaskState();
    TaskState protocolState = null;
    if (state != null && !state.isBlank()) {
      try {
        protocolState = TaskState.valueOf(state);
      } catch (IllegalArgumentException ignored) {
        // Unknown transport state remains a failure and is preserved in the diagnostic below.
      }
    }
    boolean standaloneMessage = result.getTask() == null && !result.getReceivedMessages().isEmpty();
    boolean success =
        protocolState == TaskState.TASK_STATE_COMPLETED
            || (((state == null || state.isBlank())
                    || protocolState == TaskState.TASK_STATE_UNSPECIFIED)
                && standaloneMessage);
    if (result.getFailureCode() != null) success = false;
    return TaskResult.builder()
        .success(success)
        .receivedMessages(result.getReceivedMessages())
        .error(
            success
                ? null
                : result.getFailureCode() == null
                    ? "Agent returned state=" + state
                    : result.getFailureMessage())
        .errorCode(
            success
                ? null
                : result.getFailureCode() == null ? "remote.task_failed" : result.getFailureCode())
        .build();
  }
}
