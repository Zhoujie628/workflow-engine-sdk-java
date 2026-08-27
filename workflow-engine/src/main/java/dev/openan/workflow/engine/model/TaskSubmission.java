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

package dev.openan.workflow.engine.model;

import net.openan.a2at.sdk.core.model.StandardTemplates;
import net.openan.a2at.sdk.core.model.TemplateUri;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable business-level description of a remote workflow task dispatch.
 *
 * <p>The submission explicitly distinguishes natural-language input from structured input. The
 * workflow engine maps the selected form to the matching current A2A-T SDK generation API; host
 * applications never need to put engine-private markers into metadata.
 *
 * @param agentName target agent name
 * @param instruction A2A message text and task instruction
 * @param input protocol-content input selected by the business callback
 * @param contextId optional A2A conversation context ID
 * @param metadata optional non-protocol application metadata
 */
public record TaskSubmission(
        String agentName,
        String instruction,
        Input input,
        String contextId,
        Map<String, Object> metadata) {

    /** Creates a natural-language Task-T submission. */
    public static TaskSubmission fromText(String agentName, String text) {
        return new TaskSubmission(agentName, text, new NaturalLanguage(), null, Map.of());
    }

    /** Creates a structured Task-T submission rendered with a caller-selected Task-T template. */
    public static TaskSubmission fromData(
            String agentName,
            String instruction,
            Map<String, Object> data,
            Map<String, Object> schema,
            TemplateUri templateUri) {
        return new TaskSubmission(
                agentName,
                instruction,
                new StructuredData(data, schema, templateUri),
                null,
                Map.of());
    }

    /** Validates and defensively copies the submission. */
    public TaskSubmission {
        if (agentName == null || agentName.isBlank()) {
            throw new IllegalArgumentException("Task target agentName must not be blank");
        }
        if (instruction == null || instruction.isBlank()) {
            throw new IllegalArgumentException("Task instruction must not be blank");
        }
        input = Objects.requireNonNull(input, "Task input is required");
        metadata =
                metadata == null
                        ? Map.of()
                        : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }

    /** Supported business input forms for Task-T generation. */
    public sealed interface Input permits NaturalLanguage, StructuredData {}

    /** Natural-language input: the instruction itself is passed to SDK scenario recognition. */
    public record NaturalLanguage() implements Input {}

    /** Structured input: data and schema are rendered by the SDK's schema-aware fromData API. */
    public record StructuredData(
            Map<String, Object> data, Map<String, Object> schema, TemplateUri templateUri)
            implements Input {

        /** Validates and defensively copies structured input. */
        public StructuredData {
            Objects.requireNonNull(data, "Task structured data is required");
            Objects.requireNonNull(schema, "Task data schema is required");
            Objects.requireNonNull(templateUri, "Task template URI is required");
            if (schema.isEmpty()) {
                throw new IllegalArgumentException("Task data schema must not be empty");
            }
            if (!StandardTemplates.TASK_EXTENSION_NAME.equals(templateUri.extensionName())) {
                throw new IllegalArgumentException(
                        "Structured task template is not Task-T: " + templateUri.uri());
            }
            data = Collections.unmodifiableMap(new LinkedHashMap<>(data));
            schema = Collections.unmodifiableMap(new LinkedHashMap<>(schema));
        }
    }
}
