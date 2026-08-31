/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.examples.demo;

import static org.junit.jupiter.api.Assertions.*;
import dev.openan.workflow.engine.client.WorkflowEngineClient;
import dev.openan.workflow.engine.model.*;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class HostQuickStartTest {
  @Test
  void publishedHostExampleReachesLocalAggregation() throws Exception {
    var sent = new java.util.concurrent.atomic.AtomicInteger();
    var client = (WorkflowEngineClient) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
        new Class<?>[] {WorkflowEngineClient.class}, (proxy, method, args) -> {
          if (method.getName().equals("callbackTimeoutSeconds")) return 10L;
          if (method.getName().equals("dispatch")) {
            sent.incrementAndGet();
            return CompletableFuture.completedFuture(SendMessageResult.builder().taskState("TASK_STATE_COMPLETED")
                .receivedMessages(List.of(new ReceivedMessage(MessageContent.text("diagnosis"), Map.of(), List.of())))
                .build());
          }
          return null;
        });
    var result = HostQuickStart.execute(HostQuickStart.workflow("test-agent"), List.of(), client);
    assertTrue(result.isSuccess(), result.getError());
    assertEquals(1, sent.get());
    assertTrue(result.getStepOutputs().get("aggregate").toString().contains("diagnosis"));
  }
}
