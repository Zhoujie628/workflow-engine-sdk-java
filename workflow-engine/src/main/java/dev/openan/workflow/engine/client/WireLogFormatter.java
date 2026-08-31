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

import com.fasterxml.jackson.core.JsonFactory;

/** Display only: transport bytes and observer entries are never reformatted. */
final class WireLogFormatter {
    private static final JsonFactory JSON = new JsonFactory();

    private WireLogFormatter() {}

    static String format(WireLog.Entry entry, boolean pretty) {
        StringBuilder out = new StringBuilder()
                .append("[").append(entry.boundary()).append("] ").append(entry.direction())
                .append(" requestId=").append(entry.requestId())
                .append("\nTarget: ").append(entry.method()).append(" ").append(entry.target())
                .append("\nStatus: ").append(entry.status() == null ? "(not observed)" : entry.status())
                .append("\nRepresentation: ").append(entry.representation())
                .append("\nVisibility: ").append(entry.visibility())
                .append("\nDisplay: ").append(pretty ? "pretty (not wire bytes)" : "raw observed text")
                .append("\n=== Correlation ===\n");
        entry.correlation().forEach((key, value) -> out.append(key).append(": ").append(value).append('\n'));
        out.append("=== Headers ===\n");
        if (entry.headers().isEmpty()) out.append("(none observed)\n");
        entry.headers().forEach((key, values) ->
                values.forEach(value -> out.append(key).append(": ").append(value).append('\n')));
        return out.append("=== Body ===\n")
                .append(pretty ? prettyBody(entry.body(), entry.representation()) : entry.body()).toString();
    }

    static String prettyBody(String body, String representation) {
        if (body == null || body.isBlank()) return body == null ? "" : body;
        String result = representation.toLowerCase(java.util.Locale.ROOT).contains("sse")
                ? prettySse(body) : prettyJson(body);
        return result.length() <= WireLog.limit() ? result
                : body + "\n(pretty skipped: display capacity exceeded)";
    }

    private static String prettySse(String body) {
        String[] lines = body.split("\\r\\n|\\r|\\n", -1);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (!lines[i].startsWith("data:")) {
                out.append(lines[i]);
            } else {
                int first = i;
                StringBuilder data = new StringBuilder();
                do {
                    String value = lines[i].substring(5);
                    if (value.startsWith(" ")) value = value.substring(1);
                    if (i > first) data.append('\n');
                    data.append(value);
                    i++;
                } while (i < lines.length && lines[i].startsWith("data:"));
                String formatted = prettyJson(data.toString());
                if (!isJsonContainer(data.toString().strip())) {
                    for (int j = first; j < i; j++) {
                        if (j > first) out.append('\n');
                        out.append(lines[j]);
                    }
                } else {
                    out.append("=== SSE data (JSON display; not wire text) ===\n")
                            .append(formatted).append("\n=== End SSE data ===");
                }
                i--;
            }
            if (i < lines.length - 1) out.append('\n');
            if (out.length() > WireLog.limit()) return body + "\n(pretty skipped: display capacity exceeded)";
        }
        return out.toString();
    }

    private static boolean isJsonContainer(String text) {
        if (!text.startsWith("{") && !text.startsWith("[")) return false;
        try (var parser = JSON.createParser(text)) {
            parser.nextToken();
            parser.skipChildren();
            return parser.nextToken() == null;
        } catch (java.io.IOException | RuntimeException invalid) {
            return false;
        }
    }

    /** Insert whitespace only; preserve duplicate keys, numbers and escaped string tokens exactly. */
    private static String prettyJson(String text) {
        String trimmed = text.strip();
        if (!isJsonContainer(trimmed)) return text;
        StringBuilder out = new StringBuilder();
        boolean quoted = false;
        boolean escaped = false;
        int depth = 0;
        char previous = 0;
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (quoted) {
                out.append(c);
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') quoted = false;
            } else if (c == '"') {
                quoted = true;
                out.append(c);
            } else if (!Character.isWhitespace(c)) {
                switch (c) {
                    case '{', '[' -> {
                        out.append(c);
                        if (++depth > 64) return text;
                        int next = i + 1;
                        while (next < trimmed.length() && Character.isWhitespace(trimmed.charAt(next))) next++;
                        if (next < trimmed.length() && trimmed.charAt(next) != '}' && trimmed.charAt(next) != ']')
                            newline(out, depth);
                    }
                    case '}', ']' -> {
                        depth--;
                        if (previous != '{' && previous != '[') newline(out, depth);
                        out.append(c);
                    }
                    case ',' -> { out.append(c); newline(out, depth); }
                    case ':' -> out.append(": ");
                    default -> out.append(c);
                }
            }
            if (!Character.isWhitespace(c) || quoted) previous = c;
            if (out.length() > WireLog.limit()) return text + "\n(pretty skipped: display capacity exceeded)";
        }
        return out.toString();
    }

    private static void newline(StringBuilder out, int depth) {
        out.append('\n').append("  ".repeat(depth));
    }
}
