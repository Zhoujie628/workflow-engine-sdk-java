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

import dev.openan.workflow.engine.client.RemoteProblemException;
import dev.openan.workflow.engine.model.BusinessFailure;
import dev.openan.workflow.engine.model.TaskResult;
import java.util.*;

/** Maps generic failures, remote problem facts, or explicitly safe host-selected business facts. */
final class FailureMapping {
  static TaskResult from(Throwable error) {
    Throwable root = error;
    Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    while (root.getCause() != null
        && seen.add(root)
        && !(root instanceof BusinessFailure)
        && !(root instanceof RemoteProblemException)) {
      root = root.getCause();
    }
    if (root instanceof BusinessFailure business) {
      return TaskResult.builder()
          .success(false)
          .errorCode(business.code())
          .error(business.getMessage())
          .errorDetails(business.details())
          .build();
    }
    if (root instanceof RemoteProblemException problem) {
      return TaskResult.builder()
          .success(false)
          .errorCode("remote.problem." + problem.getStatus())
          .error(problem.getMessage())
          .errorDetails(
              Map.of(
                  "status", problem.getStatus(),
                  "title", problem.getTitle(),
                  "detail", problem.getDetail(),
                  "type", problem.getType(),
                  "timestamp", problem.getTimestamp()))
          .build();
    }
    String code =
        root instanceof java.util.concurrent.TimeoutException
            ? "workflow.timeout"
            : root instanceof java.util.concurrent.CancellationException
                ? "workflow.cancelled"
                : "workflow.execution_failed";
    // Raw SDK/provider exceptions may contain credentials; the host chooses safe business facts.
    return TaskResult.failure(code, root.getClass().getSimpleName());
  }
}
