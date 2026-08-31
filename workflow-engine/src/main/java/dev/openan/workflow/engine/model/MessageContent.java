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

import org.a2aproject.sdk.spec.DataPart;
import org.a2aproject.sdk.spec.FilePart;
import org.a2aproject.sdk.spec.FileWithBytes;
import org.a2aproject.sdk.spec.FileWithUri;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.TextPart;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Final business content. The engine owns the A2A envelope and transport headers. */
public record MessageContent(
        List<Part<?>> parts, Map<String, Object> metadata, Set<String> extensions) {

    public MessageContent {
        parts = snapshotParts(Objects.requireNonNull(parts, "parts"));
        metadata = metadata == null ? Map.of() : BusinessValues.map(metadata);
        LinkedHashSet<String> active = new LinkedHashSet<>();
        if (extensions != null) {
            for (String uri : extensions) {
                if (uri == null || uri.isBlank()) {
                    throw new IllegalArgumentException("Extension URI must not be blank");
                }
                active.add(uri);
            }
        }
        extensions = Collections.unmodifiableSet(active);
    }

    /** Plain A2A text, without implicit extension activation or generation. */
    public static MessageContent text(String text) {
        return parts(List.of(new TextPart(Objects.requireNonNull(text, "text"))));
    }

    /** Ordered standard A2A parts without message-level metadata or extensions. */
    public static MessageContent parts(List<Part<?>> parts) {
        return new MessageContent(parts, Map.of(), Set.of());
    }

    static List<Part<?>> snapshotParts(List<Part<?>> parts) {
        return parts.stream().map(MessageContent::snapshotPart).toList();
    }

    private static Part<?> snapshotPart(Part<?> part) {
        Objects.requireNonNull(part, "part");
        if (part instanceof TextPart text) {
            return new TextPart(text.text(), snapshotMetadata(text.metadata()));
        }
        if (part instanceof DataPart data) {
            return new DataPart(BusinessValues.snapshot(data.data()), snapshotMetadata(data.metadata()));
        }
        if (part instanceof FilePart file
                && (file.file() instanceof FileWithUri || file.file() instanceof FileWithBytes)) {
            // File references are never dereferenced by snapshotting. Byte sources are SDK-owned.
            return new FilePart(file.file(), snapshotMetadata(file.metadata()));
        }
        throw new IllegalArgumentException("Unsupported A2A part: " + part.getClass().getName());
    }

    static Map<String, Object> snapshotMetadata(Map<String, Object> metadata) {
        return metadata == null ? null : BusinessValues.map(metadata);
    }
}
