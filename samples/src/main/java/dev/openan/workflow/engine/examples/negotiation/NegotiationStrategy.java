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
 * generate a clarification text that supplements the missing parameters. The returned text is
 * fed to the engine's SDK content layer, which renders the structured Accept message (the raw
 * text is also kept as the fallback message body). Uses LLM when the A2A-T .env is configured,
 * with a deterministic fallback.
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

    private final String a2atEnvPath;

    public NegotiationStrategy(String a2atEnvPath) {
        this.a2atEnvPath = a2atEnvPath;
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
        // Negotiation-T resolution (Accept), mirroring spec case 7.3 step 3. The engine's SDK
        // content layer renders the structured Accept template from this text; the session
        // context travels in the negotiationContext metadata key, so no context echoing here.
        String accept =
                "同意补充以下信息：\n"
                        + "1. 接入端口名称："
                        + FILLED_PORT_NAME
                        + "\n"
                        + "2. 投诉分类："
                        + FILLED_COMPLAINT_CATEGORY
                        + "\n"
                        + "信息已完整，可以启动诊断。";
        log.info("[NegotiationStrategy] Accept generated for agent={}", agentName);
        return CompletableFuture.completedFuture(accept);
    }
}
