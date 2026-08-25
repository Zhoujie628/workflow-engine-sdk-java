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

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Negotiation clarification strategy for the SPN cross-city workbench.
 *
 * <p>Single responsibility: when a downstream agent returns INPUT_REQUIRED (Negotiation-T),
 * generate typed clarification data that supplements the missing parameters. The returned
 * {@code data:{...}} value selects the latest SDK's deterministic typed Accept renderer; it is
 * never treated as a hand-written protocol prompt.
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
     * Generate a clarification for the given negotiation request.
     *
     * @param agentName the agent requesting negotiation
     * @param negotiationText the concern/question raised by the agent
     * @param receiveResult the response metadata, carrying the SDK negotiation context under the
     *     {@code negotiationContext} key (id/round/maxRounds) when present
     * @return clarification text to send back to the agent
     */
    public CompletableFuture<String> resolve(
            String agentName, String negotiationText, Map<String, Object> receiveResult) {
        log.info("[NegotiationStrategy] agent={}: {}", agentName, negotiationText);
        // The data: contract is consumed by DefaultWorkflowEngineClient and rendered with
        // generateNegotiationAcceptPromptFromData. The negotiationContext remains metadata.
        String accept =
                "data:{\"接入端口名称\":\""
                        + FILLED_PORT_NAME
                        + "\",\"投诉分类\":\""
                        + FILLED_COMPLAINT_CATEGORY
                        + "\"}";
        log.info("[NegotiationStrategy] Accept generated for agent={}", agentName);
        return CompletableFuture.completedFuture(accept);
    }
}
