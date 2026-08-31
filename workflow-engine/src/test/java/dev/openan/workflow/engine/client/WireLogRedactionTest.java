/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.client;

import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WireLogRedactionTest {
    @Test void largeEscapedContentDoesNotUseRecursiveRegexBacktracking() {
        String payload = "{\"result\":\"" + "escaped \\\"text\\\" ".repeat(8000)
                + "\",\"token\":\"hidden\"}";
        assertEquals(payload.replace("hidden", "***"), WireLog.redact(payload));
    }

    @Test void redactsNestedValuesEscapedKeysAndIncompleteSecretsWithoutReserializingOtherFields() {
        String original = "{ \"password\": {\"nested\":[\"secret\",{\"value\":\"hidden\"}]}, "
                + "\"to\\u006ben\":\"hidden\", \"ok\" : [1, 2] }";
        assertEquals("{ \"password\": \"***\", \"to\\u006ben\":\"***\", \"ok\" : [1, 2] }", WireLog.redact(original));
        assertEquals("data: {\"accessSession\":\"***\"", WireLog.redact("data: {\"accessSession\":\"partial"));
        assertEquals("password=***&ok=1", WireLog.redact("password=hidden&ok=1"));
    }

    @Test void splitSdkTextChunksDoNotExposeFragmentsOfCredentials() {
        List<String> observed = new ArrayList<>();
        WireLog.Body body = new WireLog.Body(true, true, frame -> observed.add(WireLog.redact(frame[0])));
        for (String chunk : List.of("id: 1\ndata: {\"to", "ken\":\"sensitive", "-tail\",\"ok\":true}\n", "\n")) {
            body.accept(ByteBuffer.wrap(chunk.getBytes(StandardCharsets.UTF_8)));
        }
        assertEquals(List.of("id: 1\ndata: {\"token\":\"***\",\"ok\":true}\n\n"), observed);
        body.end(false);
        assertEquals(1, observed.size());
    }
}
