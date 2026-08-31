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

package dev.openan.workflow.engine.examples.negotiation;

import dev.openan.workflow.engine.client.A2ATExtension;
import dev.openan.workflow.engine.client.A2atMessages;
import dev.openan.workflow.engine.examples.util.A2ATInitialization;
import dev.openan.workflow.engine.model.*;
import net.openan.a2at.sdk.client.A2ATClient;
import net.openan.a2at.sdk.core.model.*;
import net.openan.a2at.sdk.negotiation.content.*;
import org.a2aproject.sdk.spec.TextPart;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/** Host business policy: validate the proposal, then answer only from this city's authoritative input. */
public class NegotiationStrategy implements dev.openan.workflow.engine.control.NegotiationStrategy {
    private final A2ATClient client;

    /** The host owns SDK initialization and its provider configuration. */
    public NegotiationStrategy(String a2atEnvPath) {
        client = A2ATInitialization.create(() -> new A2ATClient(java.nio.file.Path.of(a2atEnvPath)));
    }

    @Override
    public CompletableFuture<NegotiationReply> resolve(NegotiationRequest request) {
        return CompletableFuture.supplyAsync(() -> dev.openan.workflow.engine.examples.util.BusinessSdkErrors.call(
                "negotiation-reply", () -> answer(request)));
    }

    private NegotiationReply answer(NegotiationRequest request) {
        NegotiationContext context = A2atMessages.contextOf(request.received());
        Map<String, Object> metadata = negotiationMetadata(request.received());
        String template = Objects.toString(metadata.get(MetadataContent.TEMPLATE_URI_METADATA_KEY), "");
        if (!StandardTemplates.INFORMATION_NEGOTIATION_PROPOSE.uri().equals(template)) {
            return abort(context, "当前示例策略需要人工确认目标或可行性");
        }
        String prompt = Objects.toString(metadata.get(A2ATExtension.NEGOTIATION_T.uri()), "");
        Map<String, Object> schema = Map.of("type", "object", "properties", Map.of(
                "items", Map.of("type", "array", "items", Map.of("type", "string"), "minItems", 1,
                        "description", "OMC要求补充或修正的投诉诊断字段名称；仅提取实际请求的字段，不臆造字段"),
                "relationship", Map.of("type", "string", "nullable", true,
                        "description", "请求字段之间的逻辑关系；必须一起提供时为AND，明确为替代项时为OR，未说明可为空")));
        FilledParamData validated = client.validateProposePromptAndDataFilling(prompt, context, schema, template);
        Object raw = validated.data().get("items");
        if (!(raw instanceof List<?> requested) || requested.isEmpty()) {
            return abort(context, "协商未包含可识别的请求字段");
        }
        Map<String, Object> data;
        try { data = dev.openan.workflow.engine.examples.demo.SpnCasePrompts.complaintData(request.task()); }
        catch (IllegalArgumentException error) { return abort(context, "当前任务没有可核实的结构化补充信息"); }
        List<NegotiationItem> answers = new ArrayList<>();
        for (Object item : requested) {
            if (!(item instanceof String name) || name.isBlank()) return abort(context, "协商字段名称无效");
            if (!mayProvideField(request.task(), name)) return reject(context, "业务策略不允许提供：" + name);
            String value = switch (name) {
                case "接入端口名称" -> field(data.get("任务对象"), "接入端口名称");
                case "投诉分类" -> field(data.get("任务上下文"), "投诉分类");
                default -> data.get(name) instanceof String text ? text : null;
            };
            if (value == null || value.isBlank()) return abort(context, "无法从当前任务核实：" + name);
            answers.add(new NegotiationItem(name, value));
        }
        MetadataContent reply = client.generateNegotiationAcceptPromptFromData(
                new NegotiationEndingData(context, new InformationEndingContent(NegotiationConclusion.ACCEPT, answers)),
                StandardTemplates.INFORMATION_NEGOTIATION_ACCEPT_REJECT.uri());
        return new NegotiationReply.Send(A2atMessages.from(reply, List.of(new TextPart("补充诊断信息"))));
    }

    private NegotiationReply abort(NegotiationContext context, String reason) {
        MetadataContent reply = client.generateNegotiationAbortPromptFromData(
                new NegotiationAbortData(context, new NegotiationAbortContent(reason)),
                StandardTemplates.NEGOTIATION_ABORT.uri());
        return new NegotiationReply.Send(A2atMessages.from(reply, List.of(new TextPart("终止本次协商"))));
    }

    /**
     * Host-owned disclosure policy. Override to refuse a requested field with a protocol Reject.
     * This is separate from the OMC Authorization-T whitelist for automatic recovery operations.
     */
    protected boolean mayProvideField(TaskRequest task, String fieldName) {
        return true;
    }

    private NegotiationReply reject(NegotiationContext context, String reason) {
        MetadataContent reply = client.generateNegotiationRejectPromptFromData(
                new NegotiationEndingData(context, new InformationEndingContent(NegotiationConclusion.REJECT,
                        List.of(new NegotiationItem("拒绝原因", reason)))),
                StandardTemplates.INFORMATION_NEGOTIATION_ACCEPT_REJECT.uri());
        return new NegotiationReply.Send(A2atMessages.from(reply, List.of(new TextPart("拒绝本次信息请求"))));
    }

    private static Map<String, Object> negotiationMetadata(ReceivedMessage received) {
        if (received.message() != null && received.message().metadata().containsKey(A2ATExtension.NEGOTIATION_T.uri()))
            return received.message().metadata();
        if (received.taskMetadata().containsKey(A2ATExtension.NEGOTIATION_T.uri())) return received.taskMetadata();
        return received.artifacts().stream().map(org.a2aproject.sdk.spec.Artifact::metadata)
                .filter(Objects::nonNull).filter(m -> m.containsKey(A2ATExtension.NEGOTIATION_T.uri()))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Missing negotiation content"));
    }

    private static String field(Object raw, String label) {
        if (!(raw instanceof String text)) return null;
        var matcher =
                Pattern.compile(Pattern.quote(label) + "[：:]\\s*[\\\"“]?([^；;\\\"”\\r\\n]+)")
                        .matcher(text);
        return matcher.find() ? matcher.group(1).strip() : null;
    }

}
