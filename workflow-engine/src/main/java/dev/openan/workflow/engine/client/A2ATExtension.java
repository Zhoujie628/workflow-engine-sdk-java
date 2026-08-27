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
 * strings. Use these with {@link ExtensionSender#sendExtensionMessage}.
 *
 * <p>The engine handles these extensions automatically:
 *
 * <ul>
 *   <li>{@link #TASK_T} - structured task prompt generation (in-workflow)
 *   <li>{@link #NEGOTIATION_T} - negotiation auto-loop (in-workflow)
 *   <li>{@link #AUTHORIZATION_T} - independent whitelist authorization operation
 *   <li>{@link #NOTIFICATION_T} - independent result subscription
 * </ul>
 *
 */
public enum A2ATExtension {

    /** Structured task prompt. Handled automatically during workflow execution. */
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

    /**
     * Internal metadata key under which {@code NegotiationTHandler} stores the extracted
     * negotiation message text. Engine-internal, not an A2A-T protocol key.
     */
    public static final String NEGOTIATION_MESSAGE_META_KEY = "negotiation_message";

    /**
     * Internal metadata key under which {@code NegotiationTHandler} stores the parameters
     * extracted by the validate-and-fill pipeline. Engine-internal, not an A2A-T protocol key.
     */
    public static final String NEGOTIATION_PARAMS_META_KEY = "negotiation_params";

    /**
     * Engine-internal metadata key carrying structured task input (a string-to-object map) for
     * the SDK's {@code generateTaskPromptFromDataWithSchema} pipeline. When present in the
     * caller-supplied metadata, {@code TaskTHandler} renders the Task-T prompt from the data
     * through the schema-aware path instead of running scenario recognition on the message text. Consumed
     * (removed) before the A2A message goes on the wire; the rendered prompt travels under the
     * Task-T extension URI.
     */
    public static final String TASK_DATA_META_KEY = "a2at.taskData";

    /**
     * Engine-internal metadata key carrying the JSON schema describing {@link
     * #TASK_DATA_META_KEY} fields. Required when task data is present; the schema tells the
     * SDK renderer what each field means. Engine-internal, never sent on the wire.
     */
    public static final String TASK_SCHEMA_META_KEY = "a2at.taskSchema";

    /**
     * Engine-internal metadata key carrying the template URI string for the fromData rendering
     * track (e.g. {@code "Task-T/network-layer/private-line-complaint/v1"}). Required for
     * structured Task-T rendering. Engine-internal, never sent on the wire.
     */
    public static final String TASK_TEMPLATE_META_KEY = "a2at.taskTemplate";

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
