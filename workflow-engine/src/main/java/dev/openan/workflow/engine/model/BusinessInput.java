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

import java.util.Map;

/** Business content, not a protocol message. Exactly one of text/data is present. */
public record BusinessInput(String text, Object data) {
    public BusinessInput {
        if ((text == null) == (data == null))
            throw new IllegalArgumentException("Exactly one of text or data is required");
        if (text != null && text.isBlank())
            throw new IllegalArgumentException("Text must not be blank");
        data = data == null ? null : BusinessValues.snapshot(data);
    }

    /** Natural-language input. */
    public static BusinessInput text(String text) {
        return new BusinessInput(text, null);
    }

    /** Arbitrary JSON input; any schema and interpretation belong to the host. */
    public static BusinessInput data(Object data) {
        return new BusinessInput(null, data);
    }
}
