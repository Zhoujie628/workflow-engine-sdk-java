/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.examples.workbench;

import dev.openan.workflow.engine.client.A2ATExtension;
import dev.openan.workflow.engine.examples.demo.SpnCasePrompts;

import net.openan.a2at.sdk.core.model.FilledParamData;
import net.openan.a2at.sdk.core.model.MetadataContent;
import net.openan.a2at.sdk.core.model.StandardTemplates;
import net.openan.a2at.sdk.core.model.TemplateUri;
import net.openan.a2at.sdk.server.A2ATServer;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Validates an inbound Workbench Task-T message and exposes its structured business input. */
final class WorkbenchTaskInputParser {

    @FunctionalInterface
    interface TaskPromptValidator {
        FilledParamData validate(
                String prompt, Map<String, Object> schema, TemplateUri templateUri);
    }

    record ParsedTask(
            String userText,
            String prompt,
            TemplateUri templateUri,
            Map<String, Object> parameters) {

        String runtimeIntent() {
            StringBuilder intent = new StringBuilder();
            if (userText != null && !userText.isBlank()) {
                intent.append(userText.strip()).append('\n');
            }
            intent.append("Task-T template: ").append(templateUri.uri()).append('\n');
            intent.append("Validated complaint parameters:");
            parameters.forEach(
                    (key, value) ->
                            intent.append("\n- ")
                                    .append(key)
                                    .append(": ")
                                    .append(value != null ? value : ""));
            return intent.toString();
        }
    }

    private final TaskPromptValidator validator;

    WorkbenchTaskInputParser(TaskPromptValidator validator) {
        this.validator = validator;
    }

    static WorkbenchTaskInputParser fromEnv(String envPath) {
        if (envPath == null || envPath.isBlank()) {
            throw new IllegalStateException(
                    "A2A-T SDK env path is required to validate inbound Workbench Task-T messages");
        }
        A2ATServer server = new A2ATServer(Path.of(envPath));
        return new WorkbenchTaskInputParser(server::validateTaskPromptAndDataFilling);
    }

    ParsedTask parse(String userText, Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            throw new IllegalArgumentException("Inbound Workbench request is missing Task-T metadata");
        }
        String prompt = extractTaskPrompt(metadata);
        TemplateUri templateUri = extractTemplateUri(metadata);
        FilledParamData filled =
                validator.validate(prompt, SpnCasePrompts.privateLineComplaintSchema(), templateUri);
        if (filled == null || filled.data() == null || filled.data().isEmpty()) {
            throw new IllegalArgumentException(
                    "Inbound Workbench Task-T did not contain validated complaint parameters");
        }
        Map<String, Object> parameters = new LinkedHashMap<>(filled.data());
        for (String required : SpnCasePrompts.privateLineComplaintSchemaProperties()) {
            Object value = parameters.get(required);
            if (value == null || String.valueOf(value).isBlank()) {
                throw new IllegalArgumentException(
                        "Inbound Workbench Task-T is missing required parameter: " + required);
            }
        }
        return new ParsedTask(userText, prompt, templateUri, Map.copyOf(parameters));
    }

    private static String extractTaskPrompt(Map<String, Object> metadata) {
        Object exact = metadata.get(A2ATExtension.TASK_T.uri());
        if (exact instanceof String prompt && !prompt.isBlank()) {
            return prompt;
        }
        throw new IllegalArgumentException(
                "Inbound Workbench request has no canonical Task-T prompt payload");
    }

    private static TemplateUri extractTemplateUri(Map<String, Object> metadata) {
        Object raw = metadata.get(MetadataContent.TEMPLATE_URI_METADATA_KEY);
        if (raw == null) {
            throw new IllegalArgumentException(
                    "Inbound Workbench Task-T is missing templateUri metadata");
        }
        if (!(raw instanceof String candidate) || candidate.isBlank()) {
            throw new IllegalArgumentException(
                    "Inbound Workbench Task-T has malformed templateUri metadata");
        }
        TemplateUri parsed =
                TemplateUri.parse(candidate)
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "Inbound Workbench Task-T has invalid templateUri: "
                                                        + candidate));
        return requireTaskTemplate(parsed);
    }

    private static TemplateUri requireTaskTemplate(TemplateUri templateUri) {
        if (!StandardTemplates.PRIVATE_LINE_COMPLAINT.equals(templateUri)) {
            throw new IllegalArgumentException(
                    "Inbound Workbench template is not the SPN private-line complaint template: "
                            + templateUri.uri());
        }
        return templateUri;
    }
}
