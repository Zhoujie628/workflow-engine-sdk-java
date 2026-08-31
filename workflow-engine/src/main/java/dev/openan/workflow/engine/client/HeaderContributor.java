/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.openan.workflow.engine.client;

import java.util.Map;
import org.a2aproject.sdk.spec.AgentCard;

/**
 * Supplies one isolated group of outbound A2A headers. Contributors return their own map so the
 * factory can reject conflicting values instead of relying on mutation order.
 */
interface HeaderContributor {

  Map<String, String> contribute(
      AgentCard agentCard,
      String agentName,
      Map<String, Object> messageMetadata,
      Map<String, String> currentHeaders);
}
