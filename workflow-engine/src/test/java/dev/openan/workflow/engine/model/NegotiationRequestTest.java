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
import org.junit.jupiter.api.Test;
import java.util.*;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class NegotiationRequestTest {
    @Test void preservesTaskContentAndHistorySnapshot() {
        var task = TaskRequest.builder().agentName("omc").taskId("local-task").build();
        var content = MessageContent.text("opaque");
        var received = new ReceivedMessage(content, Map.of("task-only", true), List.of());
        List<NegotiationRequest.Exchange> history = new ArrayList<>(List.of(
                new NegotiationRequest.Exchange(received, new NegotiationReply.Stop("code", "reason"))));
        var request = new NegotiationRequest(task, content, received, history, Duration.ofSeconds(3));
        history.clear();
        assertEquals("omc", request.agentName());
        assertEquals("local-task", request.task().getTaskId());
        assertEquals(1, request.previousExchanges().size());
        assertSame(received, request.received());
        assertThrows(IllegalArgumentException.class, () ->
                new NegotiationRequest(task, content, received, List.of(), Duration.ofSeconds(-1)));
    }
}
