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

/** Host-supplied safe business failure. Do not include credentials or raw provider errors. */
public final class BusinessFailure extends RuntimeException {
    private final String code;
    private final Map<String, Object> details;

    /** Constructs a failure with an application code and explicitly safe diagnostic fields. */
    public BusinessFailure(String code, String message, Map<String, Object> details) {
        super(message);
        if (code == null || code.isBlank()) throw new IllegalArgumentException("Failure code required");
        this.code = code;
        this.details = details == null ? Map.of() : BusinessValues.map(details);
    }

    /** Machine-readable business failure code. */
    public String code() { return code; }

    /** Safe business facts selected by the host. */
    public Map<String, Object> details() { return details; }
}
