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

/** Explicit business callbacks, with no implicit local success, branch selection or consent. */
public class DefaultControlPoint implements ControlPoint {
    private final NegotiationStrategy strategy;

    public DefaultControlPoint() {
        this(null);
    }

    public DefaultControlPoint(NegotiationStrategy strategy) {
        this.strategy = strategy;
    }

    @Override
    public CompletableFuture<NegotiationReply> onNegotiation(NegotiationRequest request) {
        return strategy == null
                ? ControlPoint.super.onNegotiation(request)
                : strategy.resolve(request);
    }
}
