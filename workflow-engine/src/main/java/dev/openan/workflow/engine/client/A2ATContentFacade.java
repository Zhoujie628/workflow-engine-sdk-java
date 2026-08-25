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

package dev.openan.workflow.engine.client;

import net.openan.a2at.sdk.client.A2ATClient;
import net.openan.a2at.sdk.core.model.FilledParamData;
import net.openan.a2at.sdk.core.model.MetadataContent;
import net.openan.a2at.sdk.core.model.PromptTemplate;
import net.openan.a2at.sdk.core.model.StandardTemplates;
import net.openan.a2at.sdk.core.model.TemplateUri;
import net.openan.a2at.sdk.negotiation.content.NegotiationAbortData;
import net.openan.a2at.sdk.core.model.NegotiationContext;
import net.openan.a2at.sdk.negotiation.content.NegotiationEndingData;
import net.openan.a2at.sdk.negotiation.content.NegotiationProposeData;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Engine-facing facade over the A2A-T SDK's negotiation content layer.
 *
 * <p>Bridges the engine to the SDK's full content-generation and validation surface: propose /
 * accept / reject / abort message rendering from typed data or free text, the validate-and-fill
 * pipeline, and the template queries. Callers get compile-time {@link TemplateUri} constants from
 * {@link StandardTemplates} instead of hand-written URI strings.
 *
 * <p>All methods throw {@link net.openan.a2at.sdk.core.exception.A2ATError} subtypes on failure;
 * callers are expected to catch and degrade (see {@code buildFallbackMeta} in {@code
 * DefaultWorkflowEngineClient}).
 *
 * <p>Instances are cheap views over the shared {@link A2ATClient}; create one per transport or
 * reuse freely.
 */
public final class A2ATContentFacade {

    private final A2ATClient client;

    /** @param client the shared SDK client; must not be null */
    public A2ATContentFacade(A2ATClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    /** The underlying SDK client. */
    public A2ATClient client() {
        return client;
    }

    // ------------------------------------------------------------------
    // Task/Authorization/Notification generation from schema-aware data (may use LLM)
    // ------------------------------------------------------------------

    /**
     * Renders a Task-T prompt from structured data and a schema. Scenario recognition is bypassed;
     * the SDK's schema-aware slot extractor may still invoke the configured LLM.
     *
     * @param data structured task input (string-to-object map)
     * @param schema schema describing the meaning of each data field; must not be empty
     * @param templateUri task template, e.g. {@link StandardTemplates#PRIVATE_LINE_COMPLAINT}
     * @return rendered prompt with template URI and Task-T extension URI
     */
    public MetadataContent generateTaskFromData(
            Map<String, Object> data, Map<String, Object> schema, TemplateUri templateUri) {
        return client.generateTaskPromptFromDataWithSchema(data, schema, templateUri);
    }

    /**
     * Renders an Authorization-T prompt from structured data and a schema. The SDK's schema-aware
     * slot extractor may invoke the configured LLM.
     *
     * @param data structured authorization input
     * @param schema schema describing the data fields; must not be empty
     * @param templateUri authorization template, e.g. {@link
     *     StandardTemplates#AUTHORIZATION_POLICY_MANAGEMENT}
     * @return rendered prompt with template URI and Authorization-T extension URI
     */
    public MetadataContent generateAuthFromData(
            Map<String, Object> data, Map<String, Object> schema, TemplateUri templateUri) {
        return client.generateAuthPromptFromDataWithSchema(data, schema, templateUri);
    }

    /**
     * Renders a Notification-T prompt from structured data and a schema. The SDK's schema-aware
     * slot extractor may invoke the configured LLM.
     *
     * @param data structured notification input
     * @param schema schema describing the data fields; must not be empty
     * @param templateUri notification template, e.g. {@link
     *     StandardTemplates#SUBSCRIBE_INCIDENT}
     * @return rendered prompt with template URI and Notification-T extension URI
     */
    public MetadataContent generateNotificationFromData(
            Map<String, Object> data, Map<String, Object> schema, TemplateUri templateUri) {
        return client.generateNotificationPromptFromDataWithSchema(data, schema, templateUri);
    }

    /**
     * Renders a propose-phase negotiation message from typed data. Deterministic, no LLM call.
     *
     * @param data typed propose input (context + content matching the template's type)
     * @param templateUri propose template, e.g. {@link StandardTemplates#INFORMATION_NEGOTIATION_PROPOSE}
     * @return rendered message with template URI and negotiation extension URI
     */
    public MetadataContent generateProposeFromData(NegotiationProposeData data, TemplateUri templateUri) {
        return client.generateNegotiationProposePromptFromData(data, templateUri);
    }

    /**
     * Renders an accept-phase negotiation message from typed data. Deterministic, no LLM call.
     *
     * @param data typed ending input whose content conclusion must be {@code ACCEPT}
     * @param templateUri accept-reject template, e.g. {@link
     *     StandardTemplates#INFORMATION_NEGOTIATION_ACCEPT_REJECT}
     * @return rendered message with template URI and negotiation extension URI
     */
    public MetadataContent generateAcceptFromData(NegotiationEndingData data, TemplateUri templateUri) {
        return client.generateNegotiationAcceptPromptFromData(data, templateUri);
    }

    /**
     * Renders a reject-phase negotiation message from typed data. Deterministic, no LLM call.
     *
     * @param data typed ending input whose content conclusion must be {@code REJECT}
     * @param templateUri accept-reject template, e.g. {@link
     *     StandardTemplates#INFORMATION_NEGOTIATION_ACCEPT_REJECT}
     * @return rendered message with template URI and negotiation extension URI
     */
    public MetadataContent generateRejectFromData(NegotiationEndingData data, TemplateUri templateUri) {
        return client.generateNegotiationRejectPromptFromData(data, templateUri);
    }

    /**
     * Renders an abort negotiation message from typed data. Deterministic, no LLM call.
     *
     * @param data typed abort input carrying the context and termination reason
     * @return rendered abort message ({@link StandardTemplates#NEGOTIATION_ABORT} is implied)
     */
    public MetadataContent generateAbortFromData(NegotiationAbortData data) {
        return client.generateNegotiationAbortPromptFromData(data, StandardTemplates.NEGOTIATION_ABORT);
    }

    // ------------------------------------------------------------------
    // Message generation: from free text (one LLM extraction step)
    // ------------------------------------------------------------------

    /**
     * Renders a propose-phase negotiation message from free text. Runs one LLM content-extraction
     * step constrained by the template URI, then renders deterministically.
     *
     * @param text free-text description of the message content
     * @param context negotiation session context (id/round/maxRounds)
     * @param templateUri propose template
     * @return rendered message with template URI and negotiation extension URI
     */
    public MetadataContent generateProposeFromText(
            String text,
            NegotiationContext context,
            TemplateUri templateUri) {
        return client.generateNegotiationProposePromptFromText(text, context, templateUri);
    }

    /**
     * Renders an accept-phase negotiation message from free text; extracted conclusion must be
     * {@code Accept}.
     *
     * @param text free-text description of the accept content
     * @param context negotiation session context
     * @param templateUri accept-reject template
     * @return rendered message
     */
    public MetadataContent generateAcceptFromText(
            String text,
            NegotiationContext context,
            TemplateUri templateUri) {
        return client.generateNegotiationAcceptPromptFromText(text, context, templateUri);
    }

    /**
     * Renders a reject-phase negotiation message from free text; extracted conclusion must be
     * {@code Reject}.
     *
     * @param text free-text description of the reject reason
     * @param context negotiation session context
     * @param templateUri accept-reject template
     * @return rendered message
     */
    public MetadataContent generateRejectFromText(
            String text,
            NegotiationContext context,
            TemplateUri templateUri) {
        return client.generateNegotiationRejectPromptFromText(text, context, templateUri);
    }

    /**
     * Renders an abort negotiation message from free text stating the termination reason.
     *
     * @param text free-text termination reason
     * @param context negotiation session context
     * @return rendered abort message
     */
    public MetadataContent generateAbortFromText(
            String text, NegotiationContext context) {
        return client.generateNegotiationAbortPromptFromText(text, context, StandardTemplates.NEGOTIATION_ABORT);
    }

    // ------------------------------------------------------------------
    // Negotiation session context (engine-held, content-layer contract)
    // ------------------------------------------------------------------

    /**
     * Builds the transport serialization of a negotiation session context: the {@code
     * negotiationContext} metadata value carrying {@code id} / {@code round} / {@code
     * maxRounds}.
     *
     * <p>The content layer is stateless by design — session identity and round tracking stay
     * with the caller (see SDK developer guide §1.10). The engine owns the context: it
     * serializes it here for the wire and advances rounds itself via {@code
     * NegotiationContext.nextRound()}.
     *
     * @param context the engine-held session context
     * @return wire map for the canonical {@code negotiationContext} metadata key
     */
    public static Map<String, Object> contextPayload(NegotiationContext context) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", context.id());
        payload.put("round", context.round());
        payload.put("maxRounds", context.maxRounds());
        return payload;
    }

    /**
     * Parses a negotiation session context from its canonical {@code negotiationContext} wire
     * serialization ({@code id}/{@code round}/{@code maxRounds}).
     *
     * @param map the metadata value under the {@code negotiationContext} key
     * @return the session context, or null when malformed
     */
    public static NegotiationContext contextFromMap(Map<?, ?> map) {
        if (map == null) {
            return null;
        }
        Object id = map.get("id");
        Object round = map.get("round");
        if (id instanceof String s && round instanceof Number r && r.intValue() > 0) {
            Object maxRounds = map.get("maxRounds");
            if (!(maxRounds instanceof Number m) || m.intValue() <= 0) {
                return null;
            }
            try {
                return new NegotiationContext(s, r.intValue(), m.intValue());
            } catch (IllegalArgumentException ignored) {
                // fall through
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Validate and fill (rule gate + LLM semantic validation + param merge)
    // ------------------------------------------------------------------

    /**
     * Validates a propose-phase negotiation message and extracts parameters.
     *
     * @param prompt rendered negotiation message text
     * @param context negotiation context carried alongside the message; null is reported as
     *     not-a-negotiation-message
     * @param schema caller-provided parameter JSON schema
     * @param templateUri propose template declaring the expected type and phase
     * @return filled parameter data (context params + extracted params)
     */
    public FilledParamData validatePropose(
            String prompt, NegotiationContext context, Map<String, Object> schema, TemplateUri templateUri) {
        return client.validateProposePromptAndDataFilling(prompt, context, schema, templateUri);
    }

    /**
     * Validates an accept-phase negotiation message and extracts parameters.
     *
     * @param prompt rendered negotiation message text
     * @param context negotiation context carried alongside the message; null is reported as
     *     not-a-negotiation-message
     * @param schema caller-provided parameter JSON schema
     * @param templateUri accept-reject template
     * @return filled parameter data
     */
    public FilledParamData validateAccept(
            String prompt, NegotiationContext context, Map<String, Object> schema, TemplateUri templateUri) {
        return client.validateAcceptPromptAndDataFilling(prompt, context, schema, templateUri);
    }

    /**
     * Validates a reject-phase negotiation message and extracts parameters.
     *
     * @param prompt rendered negotiation message text
     * @param context negotiation context carried alongside the message; null is reported as
     *     not-a-negotiation-message
     * @param schema caller-provided parameter JSON schema
     * @param templateUri accept-reject template
     * @return filled parameter data
     */
    public FilledParamData validateReject(
            String prompt, NegotiationContext context, Map<String, Object> schema, TemplateUri templateUri) {
        return client.validateRejectPromptAndDataFilling(prompt, context, schema, templateUri);
    }

    /**
     * Validates an abort negotiation message and extracts parameters.
     *
     * @param prompt rendered abort message text
     * @param context negotiation context carried alongside the message
     * @param schema caller-provided parameter JSON schema
     * @return filled parameter data
     */
    public FilledParamData validateAbort(
            String prompt, NegotiationContext context, Map<String, Object> schema) {
        return client.validateAbortPromptAndDataFilling(
                prompt, context, schema, StandardTemplates.NEGOTIATION_ABORT);
    }

    // ------------------------------------------------------------------
    // Template queries
    // ------------------------------------------------------------------

    /**
     * Lists every template of the configured language across all A2A-T extensions.
     *
     * @return loadable templates sorted by template URI; empty when none can be loaded
     */
    public List<PromptTemplate> getPrompts() {
        return client.getPrompts();
    }

    /**
     * Loads one template by URI regardless of extension.
     *
     * @param templateUri template URI such as {@link StandardTemplates#PRIVATE_LINE_COMPLAINT}
     * @return the addressed template, or empty when it does not exist for the language
     */
    public Optional<PromptTemplate> getPrompt(TemplateUri templateUri) {
        return client.getPrompt(templateUri);
    }

    /**
     * Lists every negotiation template of the configured language.
     *
     * @return loadable negotiation templates in fixed type and phase order
     */
    public List<PromptTemplate> getNegotiationPrompts() {
        return client.getNegotiationPrompts();
    }

    /**
     * Loads one negotiation template by URI.
     *
     * @param templateUri template URI such as {@link
     *     StandardTemplates#INFORMATION_NEGOTIATION_PROPOSE}
     * @return the addressed template, or empty when it does not exist
     */
    public Optional<PromptTemplate> getNegotiationPrompt(TemplateUri templateUri) {
        return client.getNegotiationPrompt(templateUri);
    }

    /**
     * Builds the metadata map for a generated message: the extension URI mapping to the rendered
     * text plus the {@code templateUri} marker key.
     *
     * @param content generated message content
     * @return metadata map ready to attach to an A2A message
     */
    public static Map<String, Object> toMetadata(MetadataContent content) {
        return new LinkedHashMap<>(content.buildMetadataContent());
    }
}
