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

import org.a2aproject.sdk.spec.Artifact;
import org.a2aproject.sdk.spec.DataPart;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.TextPart;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Response snapshot preserving message, task and artifact metadata at their original levels. */
public record ReceivedMessage(
        MessageContent message, Map<String, Object> taskMetadata, List<Artifact> artifacts) {

    public ReceivedMessage {
        taskMetadata = taskMetadata == null ? Map.of() : BusinessValues.map(taskMetadata);
        artifacts = artifacts == null ? List.of() : artifacts.stream()
                .map(a -> new Artifact(a.artifactId(), a.name(), a.description(),
                        MessageContent.snapshotParts(a.parts()),
                        MessageContent.snapshotMetadata(a.metadata()),
                        a.extensions() == null ? List.of() : List.copyOf(a.extensions())))
                .toList();
    }

    /** Projects deliverables, or the message when there are no artifacts; never parses text. */
    public List<Object> outputs() { return outputs(true); }

    /** Failed/non-final task status messages remain evidence, never business outputs. */
    public List<Object> outputs(boolean includeMessage) {
        List<Object> values = new ArrayList<>();
        // A task's status message is still available in the complete view, but must not
        // become an extra deliverable alongside its artifacts (e.g. "Completed").
        if (includeMessage && message != null && artifacts.isEmpty()) {
            project(message.parts(), values);
        }
        for (Artifact artifact : artifacts) {
            project(artifact.parts(), values);
        }
        return BusinessValues.list(values);
    }

    private static void project(List<Part<?>> parts, List<Object> values) {
        for (Part<?> part : parts) {
            if (part instanceof TextPart text) {
                values.add(text.text());
            } else if (part instanceof DataPart data) {
                values.add(data.data());
            }
        }
    }
}
