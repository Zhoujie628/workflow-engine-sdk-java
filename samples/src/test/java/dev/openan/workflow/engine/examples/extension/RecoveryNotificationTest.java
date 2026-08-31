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
import org.a2aproject.sdk.spec.Artifact;
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class RecoveryNotificationTest {
    private static final String RESULT = "### 业务抢通事件\n"
            + "1. 业务抢通方案执行状态：已结束\n2. 投诉诊断任务流水号：task-42\n"
            + "3. OSS侧事件流水号：event-42\n4. 接入端口名称：port-42\n"
            + "5. 是否已授权OMC自动抢通：是\n6. 业务抢通方案名称：隧道调优\n"
            + "7. 业务抢通方案详情：执行指定调优策略\n8. 业务抢通方案执行结束时间：2026-08-31T00:00:00Z\n"
            + "9. 业务抢通方案执行结果：成功";

    private ReceivedMessage received(String name, String formal, String summary) {
        return new ReceivedMessage(null, Map.of(), List.of(Artifact.builder().artifactId("event").name(name)
                .parts(List.of(new TextPart(summary))).metadata(formal == null ? Map.of()
                        : Map.of(A2ATExtension.NOTIFICATION_T.uri(), formal)).build()));
    }

    @Test void completedSuccessAndFailureAreBothTerminalRecoveryResults() {
        assertTrue(RecoveryNotification.hasCompletedResult(received("recovery-result", RESULT, "summary")));
        assertTrue(RecoveryNotification.hasCompletedResult(received("recovery-result", RESULT.replace("结果：成功", "结果：失败"), "summary")));
    }

    @Test void artifactNameOrPartsAloneNeverCompletesSubscription() {
        assertFalse(RecoveryNotification.hasCompletedResult(received("recovery-result", null, RESULT)));
        assertFalse(RecoveryNotification.hasCompletedResult(received("recovery-result", "订阅成功", RESULT)));
        assertFalse(RecoveryNotification.hasCompletedResult(received("recovery-plan", RESULT, "summary")));
    }

    @Test void incompleteOrAmbiguousResultKeepsSubscriptionOpen() {
        assertFalse(RecoveryNotification.hasCompletedResult(received("recovery-result", RESULT.replace("已结束", "未启动"), "summary")));
        assertFalse(RecoveryNotification.hasCompletedResult(received("recovery-result", RESULT.replace("OSS侧事件流水号：event-42", "OSS侧事件流水号："), "summary")));
        assertFalse(RecoveryNotification.hasCompletedResult(received("recovery-result", RESULT + "\n10. 接入端口名称：other", "summary")));
    }
}
