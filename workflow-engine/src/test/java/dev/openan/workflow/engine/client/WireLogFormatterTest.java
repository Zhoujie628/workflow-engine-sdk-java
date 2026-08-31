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
package dev.openan.workflow.engine.client;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WireLogFormatterTest {
    @Test void prettyJsonPreservesDuplicateKeysNumericAndEscapedStringTokens() {
        String raw = "{\"n\":1e+02,\"n\":-0.0,\"text\":\"x\\n\\\"中文\\\"\",\"a\":[{},1]}";
        String pretty = WireLogFormatter.prettyBody(raw, "serialized-utf8");
        assertTrue(pretty.contains("\n  \"n\": 1e+02,"));
        assertTrue(pretty.contains("\n  \"n\": -0.0,"));
        assertTrue(pretty.contains("\"x\\n\\\"中文\\\"\""));
        assertTrue(pretty.contains("\n    {},\n    1\n  ]"));
    }

    @Test void prettySseKeepsEventControlFieldsAndMakesDataReadable() {
        String raw = "id: 12\r\nevent: task\r\n: heartbeat\r\ndata: {\"metadata\":{\r\ndata: \"performative\":\"PROPOSE\"}}\r\n\r\n";
        String pretty = WireLogFormatter.prettyBody(raw, "raw-sse-frame");
        assertTrue(pretty.startsWith("id: 12\nevent: task\n: heartbeat\n=== SSE data (JSON display; not wire text) ===\n{\n"));
        assertTrue(pretty.contains("    \"performative\": \"PROPOSE\""));
        assertFalse(pretty.contains("\ndata: "));
        assertTrue(pretty.contains("=== End SSE data ==="));
        assertTrue(pretty.endsWith("\n\n"));
        assertEquals(pretty, WireLogFormatter.prettyBody(raw, "sdk-sse-text"));
    }


    @Test void sseDisplaySeparatesEventsAndKeepsRawObservationAvailable() {
        String raw = "id: 1\ndata: {\n" + "data:   \"n\": 1\n" + "data: }\n\n"
                + "id: 2\ndata: {}\n\n";
        List<WireLog.Entry> observed = new ArrayList<>();
        WireLog.emit(observed::add, "DIRECT_HTTP", "RESPONSE_BODY", "sse-1", "/", "POST", 200,
                Map.of(), "raw-sse-frame", raw, "observed", Map.of());
        assertEquals(raw, observed.get(0).body());
        assertTrue(WireLogFormatter.format(observed.get(0), false).endsWith(raw));
        String pretty = WireLogFormatter.format(observed.get(0), true);
        assertTrue(pretty.contains("id: 1\n=== SSE data (JSON display; not wire text) ===\n{\n  \"n\": 1\n}"));
        assertTrue(pretty.contains("id: 2\n=== SSE data (JSON display; not wire text) ===\n{}"));
        assertFalse(pretty.contains("\ndata:"));
        assertEquals("data: {\"unfinished\":\n\n",
                WireLogFormatter.prettyBody("data: {\"unfinished\":\n\n", "raw-sse-frame"));
    }

    @Test void invalidTruncatedAndNonJsonContentAreNotRepaired() {
        for (String raw : List.of("{\"x\":", "{\"x\":1} trailing", "[DONE]", "plain text"))
            assertEquals(raw, WireLogFormatter.prettyBody(raw, "serialized-utf8"));
        assertEquals("data: [DONE]\n\n", WireLogFormatter.prettyBody("data: [DONE]\n\n", "raw-sse-frame"));
    }

    @Test void logLayoutIsPrettyButObserversKeepExactRedactedWireText() {
        List<WireLog.Entry> entries = new ArrayList<>();
        String raw = "{\"password\":\"hidden\",\"negotiationContext\":{\"performative\":\"ACCEPT\"}}";
        WireLog.emit(entries::add, "DIRECT_HTTP", "REQUEST_BODY", "req-1", "/send", "POST", null,
                Map.of("Authorization", List.of("hidden"), "A2A-Extensions", List.of("Negotiation-T/v1"),
                        "X-Trace", List.of("one", "two")), "serialized-utf8", raw, "observed", Map.of());
        WireLog.Entry entry = entries.get(0);
        assertEquals(raw.replace("hidden", "***"), entry.body());
        String pretty = WireLogFormatter.format(entry, true);
        assertTrue(pretty.contains("=== Headers ===\n"));
        assertTrue(pretty.contains("A2A-Extensions: Negotiation-T/v1\n"));
        assertTrue(pretty.contains("X-Trace: one\nX-Trace: two\n"));
        assertTrue(pretty.contains("\n    \"performative\": \"ACCEPT\""));
        assertFalse(pretty.contains("hidden"));
        assertTrue(WireLogFormatter.format(entry, false).endsWith(entry.body()));
        assertEquals(raw.replace("hidden", "***"), entry.body());
    }

    @Test void displayExpansionIsBoundedAndDoesNotRecurseOnDeepJson() {
        String key = "WORKFLOW_ENGINE_PROTOCOL_MAX_BODY_CHARS";
        String previous = System.getProperty(key);
        System.setProperty(key, "256");
        try {
            String raw = "{\"a\":[" + "1,".repeat(80) + "2]}";
            String display = WireLogFormatter.prettyBody(raw, "serialized-utf8");
            assertTrue(display.startsWith(raw));
            assertTrue(display.contains("pretty skipped"));
            String deep = "[".repeat(70) + "0" + "]".repeat(70);
            assertEquals(deep, WireLogFormatter.prettyBody(deep, "serialized-utf8").split("\n\\(pretty")[0]);
        } finally {
            if (previous == null) System.clearProperty(key); else System.setProperty(key, previous);
        }
    }

    @Test void disabledBodyIsNotReconstructedByPrettyDisplay() {
        String key = "WORKFLOW_ENGINE_PROTOCOL_INCLUDE_BODY";
        String previous = System.getProperty(key);
        System.setProperty(key, "false");
        try {
            List<WireLog.Entry> entries = new ArrayList<>();
            WireLog.emit(entries::add, "DIRECT_HTTP", "REQUEST_BODY", "req", "/", "POST", null,
                    Map.of(), "serialized-utf8", "{\"privateBusiness\":42}", "observed", Map.of());
            String display = WireLogFormatter.format(entries.get(0), true);
            assertTrue(display.contains("(body logging disabled)"));
            assertFalse(display.contains("privateBusiness"));
        } finally {
            if (previous == null) System.clearProperty(key); else System.setProperty(key, previous);
        }
    }
}
