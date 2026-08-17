/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.examples.gateway;

import org.a2aproject.sdk.spec.AgentCard;

/** Resolves an A2A agent to the NE and URI understood by the Eastcom instruction platform. */
@FunctionalInterface
public interface AgentGatewayRouteResolver {
    AgentGatewayRoute resolve(AgentCard agentCard);
}
