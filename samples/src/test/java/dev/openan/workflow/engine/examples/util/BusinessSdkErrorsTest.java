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


package dev.openan.workflow.engine.examples.util;

import dev.openan.workflow.engine.model.BusinessFailure;
import net.openan.a2at.sdk.core.exception.A2ATError;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class BusinessSdkErrorsTest {
    @Test void exposesSdkCodeAndOnlyHostSelectedDetails() {
        BusinessFailure failure = assertThrows(BusinessFailure.class, () -> BusinessSdkErrors.call(
                "task-generation", () -> { throw new A2ATError("slot.validation_error",
                        "password=must-not-leak accessSession=secret"); }));
        assertEquals("slot.validation_error", failure.code());
        assertEquals(Map.of("operation", "task-generation"), failure.details());
        assertNull(failure.getCause());
        assertFalse(failure.toString().contains("must-not-leak"));
    }
}
