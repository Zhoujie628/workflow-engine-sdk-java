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

import net.openan.a2at.sdk.core.model.ExtensionUriConstants;

/**
 * A2A-T extension types supported by the workflow execution engine.
 *
 * <p>Each enum constant encapsulates the full extension URI so callers never need to hardcode URI
 * strings. Use these when explicitly activating final message content.
 *
 * <p>The engine handles these extensions automatically:
 *
 * <ul>
 *   <li>{@link #TASK_T} - host-generated task content (in-workflow)
 *   <li>{@link #NEGOTIATION_T} - negotiation auto-loop (in-workflow)
 *   <li>{@link #AUTHORIZATION_T} - independent whitelist authorization operation
 *   <li>{@link #NOTIFICATION_T} - independent result subscription
 * </ul>
 */
public enum A2ATExtension {

  /** Host-generated task content. The engine does not generate it. */
  TASK_T(ExtensionUriConstants.TASK_T_EXTENSION_URI),

  /** Negotiation text exchange. Handled automatically via auto-loop. */
  NEGOTIATION_T(ExtensionUriConstants.NEGOTIATION_T_EXTENSION_URI),

  /** Authorization whitelist. Pre-positioned before workflow starts. */
  AUTHORIZATION_T(ExtensionUriConstants.AUTHORIZATION_T_EXTENSION_URI),

  /** Result notification subscription. Pre-positioned before workflow starts. */
  NOTIFICATION_T(ExtensionUriConstants.NOTIFICATION_T_EXTENSION_URI);

  /** Canonical SDK metadata key carrying id/round/maxRounds/performative. */
  public static final String NEGOTIATION_CONTEXT_META_KEY =
      net.openan.a2at.sdk.core.model.MetadataContent.NEGOTIATION_CONTEXT_METADATA_KEY;

  private final String uri;

  A2ATExtension(String uri) {
    this.uri = uri;
  }

  /**
   * @return the full extension URI used as metadata key and A2A-Extensions header value
   */
  public String uri() {
    return uri;
  }

  /**
   * @return short display name (e.g. {@code "Authorization-T"})
   */
  public String displayName() {
    return name().replace('_', '-');
  }
}
