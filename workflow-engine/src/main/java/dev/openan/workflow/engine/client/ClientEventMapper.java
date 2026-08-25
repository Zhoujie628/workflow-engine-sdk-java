/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.client;

import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.client.MessageEvent;
import org.a2aproject.sdk.client.TaskEvent;
import org.a2aproject.sdk.client.TaskUpdateEvent;
import org.a2aproject.sdk.spec.TaskArtifactUpdateEvent;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;

import java.util.HashMap;
import java.util.Map;

/** Converts SDK events into the stable map exposed to integration callbacks. */
public final class ClientEventMapper {
    private ClientEventMapper() {}

    public static Map<String, Object> toMap(ClientEvent event, String agentName) {
        Map<String, Object> data = new HashMap<>();
        data.put("agent", agentName);
        if (event instanceof TaskEvent te) {
            data.put("event_kind", "task");
            data.put("task_id", te.getTask().id());
            data.put("state", te.getTask().status().state().name());
            data.put("is_final", te.getTask().status().state().isFinal());
            StringBuilder text = new StringBuilder();
            A2ATransport.extractTextFromTask(te.getTask(), text);
            if (!text.isEmpty()) data.put("text", text.toString());
            Map<String, Object> metadata = new HashMap<>();
            A2ATransport.mergeTaskMetadata(te.getTask(), metadata);
            if (!metadata.isEmpty()) data.put("metadata", metadata);
        } else if (event instanceof TaskUpdateEvent tue) {
            data.put("task_id", tue.getTask().id());
            if (tue.getUpdateEvent() instanceof TaskStatusUpdateEvent sue) {
                data.put("event_kind", "status");
                data.put("state", sue.status().state().name());
                data.put("is_final", sue.isFinal());
                StringBuilder text = new StringBuilder();
                A2ATransport.extractTextFromMessage(sue.status().message(), text);
                if (!text.isEmpty()) data.put("text", text.toString());
                if (sue.metadata() != null && !sue.metadata().isEmpty()) {
                    data.put("metadata", sue.metadata());
                }
            } else if (tue.getUpdateEvent() instanceof TaskArtifactUpdateEvent ae) {
                data.put("event_kind", "artifact");
                data.put("artifact_id", ae.artifact().artifactId());
                data.put("artifact_name", ae.artifact().name());
                data.put("append", ae.append());
                data.put("last_chunk", ae.lastChunk());
                StringBuilder text = new StringBuilder();
                A2ATransport.extractTextFromArtifact(ae.artifact(), text);
                if (!text.isEmpty()) data.put("text", text.toString());
                Map<String, Object> metadata = ae.artifact().metadata();
                if (metadata != null && !metadata.isEmpty()) {
                    data.put("metadata", metadata);
                } else if (ae.metadata() != null && !ae.metadata().isEmpty()) {
                    data.put("metadata", ae.metadata());
                }
            }
        } else if (event instanceof MessageEvent me) {
            data.put("event_kind", "message");
            data.put("role", me.getMessage().role().name());
            StringBuilder text = new StringBuilder();
            A2ATransport.extractTextFromMessage(me.getMessage(), text);
            if (!text.isEmpty()) data.put("text", text.toString());
            if (me.getMessage().metadata() != null && !me.getMessage().metadata().isEmpty()) {
                data.put("metadata", me.getMessage().metadata());
            }
        }
        return data;
    }
}
