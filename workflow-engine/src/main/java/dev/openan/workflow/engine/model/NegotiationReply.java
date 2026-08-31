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

package dev.openan.workflow.engine.model;

import java.util.Objects;

/** Business chooses a final protocol reply or a local stop; the engine never generates an Abort. */
public sealed interface NegotiationReply {
    /** Final content to send on the original remote task and context. */
    record Send(MessageContent content) implements NegotiationReply {
        public Send {
            Objects.requireNonNull(content, "content");
        }
    }

    /** Ends local execution only. Does not imply a protocol Abort was sent or acknowledged. */
    record Stop(String code, String reason) implements NegotiationReply {
        public Stop {
            if (code == null || code.isBlank() || reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("Stop code and reason are required");
            }
        }
    }
}
