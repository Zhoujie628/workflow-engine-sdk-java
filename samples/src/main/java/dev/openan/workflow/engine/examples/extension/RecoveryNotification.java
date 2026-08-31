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
package dev.openan.workflow.engine.examples.extension;

import dev.openan.workflow.engine.client.A2ATExtension;
import dev.openan.workflow.engine.model.ReceivedMessage;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.regex.Pattern;

/**
 * SPN business completion policy, not an engine or SDK-wide notification schema.
 * SDK notification generation/validation describes subscription requests, not recovery events.
 */
public final class RecoveryNotification {
    private RecoveryNotification() {}

    /** Close only for a complete recovery result in formal Notification-T artifact metadata. */
    public static boolean hasCompletedResult(ReceivedMessage received) {
        return received.artifacts().stream().filter(a -> "recovery-result".equals(a.name()))
                .anyMatch(a -> isCompletedResult(a.metadata()));
    }

    private static boolean isCompletedResult(Map<String, Object> metadata) {
        Object payload = metadata == null ? null : metadata.get(A2ATExtension.NOTIFICATION_T.uri());
        if (!(payload instanceof String text)) return false;
        Map<String, String> fields = new LinkedHashMap<>();
        var matcher = Pattern.compile("(?m)^\\s*\\d+[.、]\\s*([^：:\\r\\n]+)[：:]\\s*(.*)$").matcher(text);
        while (matcher.find()) {
            if (fields.putIfAbsent(matcher.group(1).strip(), matcher.group(2).strip()) != null) return false;
        }
        List<String> required = List.of("业务抢通方案执行状态", "投诉诊断任务流水号", "OSS侧事件流水号",
                "接入端口名称", "是否已授权OMC自动抢通", "业务抢通方案名称", "业务抢通方案详情",
                "业务抢通方案执行结束时间", "业务抢通方案执行结果");
        if (required.stream().anyMatch(key -> fields.getOrDefault(key, "").isBlank())) return false;
        return "已结束".equals(fields.get("业务抢通方案执行状态"))
                && List.of("成功", "失败").contains(fields.get("业务抢通方案执行结果"));
    }
}
