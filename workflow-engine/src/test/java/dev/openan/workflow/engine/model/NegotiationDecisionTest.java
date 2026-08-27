/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.util.Map;

class NegotiationDecisionTest {

    @Test
    void factoriesPreserveActionAndInputMode() {
        var accept = NegotiationDecision.acceptData(Map.of("port", "P1"));
        var reject = NegotiationDecision.rejectText("policy denied");
        var abort = NegotiationDecision.abortData("round limit");

        assertEquals(NegotiationDecision.Action.ACCEPT, accept.action());
        assertEquals(
                "P1",
                assertInstanceOf(NegotiationDecision.StructuredData.class, accept.input())
                        .values()
                        .get("port"));
        assertEquals(NegotiationDecision.Action.REJECT, reject.action());
        assertEquals(
                "policy denied",
                assertInstanceOf(NegotiationDecision.NaturalLanguage.class, reject.input()).text());
        assertEquals(NegotiationDecision.Action.ABORT, abort.action());
    }

    @Test
    void invalidBusinessValuesFailBeforeSdkInvocation() {
        assertThrows(IllegalArgumentException.class, () -> NegotiationDecision.acceptText(" "));
        assertThrows(IllegalArgumentException.class, () -> NegotiationDecision.acceptData(Map.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> NegotiationDecision.acceptData(java.util.Collections.singletonMap("port", null)));
        assertThrows(
                IllegalArgumentException.class,
                () -> NegotiationDecision.rejectData(Map.of("port", "null")));
        assertThrows(IllegalArgumentException.class, () -> NegotiationDecision.abortData(" "));
        assertThrows(IllegalArgumentException.class, () -> NegotiationDecision.abortData("null"));
    }
}
