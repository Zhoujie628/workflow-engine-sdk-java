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

package dev.openan.workflow.engine.examples.negotiation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import dev.openan.workflow.engine.model.NegotiationDecision;
import dev.openan.workflow.engine.model.NegotiationRequest;

/**
 * Negotiation decision strategy for the SPN cross-city workbench.
 *
 * <p>Single responsibility: when a downstream agent returns INPUT_REQUIRED (Negotiation-T),
 * generate typed decision data that supplements the missing parameters. The returned
 * structured Accept decision selects the latest SDK's deterministic typed renderer; it is never
 * treated as a hand-written protocol prompt.
 *
 * <p>The Accept payload follows the SDK scenario data for the private-line complaint case:
 * the two parameters the agent negotiated for (接入端口名称, 投诉分类), filled with the values
 * from the task context.
 */
public class NegotiationStrategy implements dev.openan.workflow.engine.control.NegotiationStrategy {

    private static final Logger log = LoggerFactory.getLogger(NegotiationStrategy.class);

    /** Filled values for the two negotiated parameters (from the task context, spec case 7.3). */
    private static final String FILLED_PORT_NAME = "P533-珠江旧城-PTN3900-23-TPA1EG24-1";

    private static final String FILLED_COMPLAINT_CATEGORY = "专线质差";

    public NegotiationStrategy(String a2atEnvPath) {
        // Kept in the constructor contract so applications can use one strategy factory for
        // environment-aware implementations. This deterministic strategy needs no LLM itself.
    }

    /**
     * Make an Accept decision for the given negotiation request.
     *
     * @param request typed negotiation request, including concern and session information
     * @return typed business decision to send back to the agent
     */
    public CompletableFuture<NegotiationDecision> resolve(NegotiationRequest request) {
        log.info(
                "[NegotiationStrategy] agent={}, round={}: {}",
                request.agentName(),
                request.round(),
                request.concern());
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("接入端口名称", FILLED_PORT_NAME);
        values.put("投诉分类", FILLED_COMPLAINT_CATEGORY);
        NegotiationDecision accept = NegotiationDecision.acceptData(values);
        log.info("[NegotiationStrategy] Accept generated for agent={}", request.agentName());
        return CompletableFuture.completedFuture(accept);
    }
}
