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

import dev.openan.workflow.engine.client.RemoteA2AErrorException;
import dev.openan.workflow.engine.model.BusinessFailure;
import dev.openan.workflow.engine.model.TaskResult;
import java.util.*;

/** Maps generic failures, standard A2A errors, or explicitly safe host-selected business facts. */
final class FailureMapping {
  static TaskResult from(Throwable error) {
    Throwable root = error;
    Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    while (root.getCause() != null
        && seen.add(root)
        && !(root instanceof BusinessFailure)
        && !(root instanceof RemoteA2AErrorException)) {
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
    RemoteA2AErrorException remoteError = RemoteA2AErrorException.findIn(root);
    if (remoteError != null) {
      Map<String, Object> details = new LinkedHashMap<>();
      details.put("httpStatus", remoteError.getHttpStatus());
      details.put("code", remoteError.getCode());
      if (!remoteError.getStatus().isBlank()) details.put("status", remoteError.getStatus());
      if (!remoteError.getReason().isBlank()) details.put("reason", remoteError.getReason());
      if (!remoteError.getDomain().isBlank()) details.put("domain", remoteError.getDomain());
      if (!remoteError.getDetails().isEmpty()) details.put("details", remoteError.getDetails());
      if (!remoteError.getRetryAfter().isBlank()) {
        details.put("retryAfter", remoteError.getRetryAfter());
      }
      return TaskResult.builder()
          .success(false)
          .errorCode(remoteError.workflowErrorCode())
          .error(remoteError.getMessage())
          .errorDetails(details)
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
