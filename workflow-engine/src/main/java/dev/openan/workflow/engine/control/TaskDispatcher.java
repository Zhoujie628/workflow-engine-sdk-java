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

package dev.openan.workflow.engine.control;

import dev.openan.workflow.engine.model.SendMessageResult;
import dev.openan.workflow.engine.model.TaskSubmission;

import java.util.concurrent.CompletableFuture;

/** Narrow capability exposed to business task callbacks for dispatching one typed remote task. */
@FunctionalInterface
public interface TaskDispatcher {

    /** Dispatches a natural-language or structured Task-T submission. */
    CompletableFuture<SendMessageResult> dispatch(TaskSubmission submission);
}
