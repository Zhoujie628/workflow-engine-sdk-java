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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import net.openan.a2at.sdk.core.model.NegotiationPerformative;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

class NegotiationRequestTest {

    @Test
    void retainsTypedPerformativeAndDefensivelyCopiesBusinessData() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("trace", "one");
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("接入端口名称", "举例：P533");
        var request =
                new NegotiationRequest(
                        "SPN",
                        "confirm port",
                        "session-1",
                        1,
                        3,
                        NegotiationPerformative.PROPOSE,
                        NegotiationRequest.Kind.INFORMATION,
                        "urn:template",
                        parameters,
                        metadata);

        metadata.put("trace", "two");
        parameters.put("接入端口名称", "changed");
        assertEquals(NegotiationPerformative.PROPOSE, request.performative());
        assertEquals("举例：P533", request.parameters().get("接入端口名称"));
        assertThrows(
                UnsupportedOperationException.class,
                () -> request.parameters().put("x", "y"));
        assertEquals("one", request.metadata().get("trace"));
        assertThrows(UnsupportedOperationException.class, () -> request.metadata().put("x", "y"));
    }

    @Test
    void rejectsInvalidSessionAndRounds() {
        assertThrows(
                IllegalArgumentException.class,
                () -> request("", 1, 3));
        assertThrows(
                IllegalArgumentException.class,
                () -> request("session-1", 0, 3));
        assertThrows(
                IllegalArgumentException.class,
                () -> request("session-1", 4, 3));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new NegotiationRequest(
                                "SPN",
                                "confirm port",
                                "session-1",
                                1,
                                3,
                                NegotiationPerformative.ACCEPT,
                                NegotiationRequest.Kind.INFORMATION,
                                "urn:template",
                                Map.of(),
                                Map.of()));
    }

    private static NegotiationRequest request(String sessionId, int round, int maxRounds) {
        return new NegotiationRequest(
                "SPN",
                "confirm port",
                sessionId,
                round,
                maxRounds,
                NegotiationPerformative.PROPOSE,
                NegotiationRequest.Kind.INFORMATION,
                "urn:template",
                Map.of(),
                Map.of());
    }
}
