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

package dev.openan.workflow.engine.examples.demo;

import dev.openan.workflow.engine.examples.testsupport.OfflineA2ATLlmClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Runs the actual northbound Spring demo with local OMCs and the offline SDK provider. */
class SpringSpnDemoE2ETest {
  private dev.openan.workflow.engine.examples.testsupport.CapturedLogs captured;

  @org.junit.jupiter.api.BeforeEach
  void captureLogs() {
    captured = new dev.openan.workflow.engine.examples.testsupport.CapturedLogs();
  }

  @org.junit.jupiter.api.AfterEach
  void closeCapture() {
    captured.close();
  }

  @Test
  @Timeout(120)
  void defaultLocalDemoNegotiatesOnlyCity1WithoutChangingGlobalSwitch() throws Exception {
    String enabled = System.getProperty("a2at.samples.negotiation");
    String city = System.getProperty("a2at.samples.negotiation.city");
    int offset = captured.length();
    System.clearProperty("a2at.samples.negotiation");
    System.clearProperty("a2at.samples.negotiation.city");
    try {
      runDemo();
      {
        String evidence = captured.since(offset);
        org.junit.jupiter.api.Assertions.assertTrue(
            evidence.contains("NEGOTIATION_DEMO enabled=true, city=city1"));
        org.junit.jupiter.api.Assertions.assertTrue(
            evidence.contains("[SpnDomainAgentCity1Executor] NEGOTIATION_APPLIED"));
        org.junit.jupiter.api.Assertions.assertFalse(
            evidence.contains("[SpnDomainAgentCity2Executor] NEGOTIATION_APPLIED"));
        org.junit.jupiter.api.Assertions.assertTrue(
            evidence.contains(
                "[SpnDomainAgentCity2Executor] Parameters sufficient, skipping negotiation"));
        assertNegotiationWireEvidence(evidence);
        for (String cityName : java.util.List.of("City1", "City2")) {
          org.junit.jupiter.api.Assertions.assertTrue(
              evidence.contains(
                  "[SpnDomainAgent" + cityName + "Executor] Authorization-T accepted"));
          org.junit.jupiter.api.Assertions.assertTrue(
              evidence.contains(
                  "[SpnDomainAgent" + cityName + "Executor] NOTIFICATION_SUBSCRIBED"));
        }
        org.junit.jupiter.api.Assertions.assertFalse(
            evidence.contains("OPERATION_FAILED extension="));
        org.junit.jupiter.api.Assertions.assertFalse(
            evidence.matches("(?s).*\\ndata: +\\\"performative\\\".*"));
      }
      org.junit.jupiter.api.Assertions.assertNull(System.getProperty("a2at.samples.negotiation"));
    } finally {
      if (enabled == null) System.clearProperty("a2at.samples.negotiation");
      else System.setProperty("a2at.samples.negotiation", enabled);
      if (city == null) System.clearProperty("a2at.samples.negotiation.city");
      else System.setProperty("a2at.samples.negotiation.city", city);
    }
  }

  @Test
  @Timeout(120)
  void bothCitiesNegotiateIndependentlyAndComplete() throws Exception {
    String previous = System.getProperty("a2at.samples.negotiation.city");
    int offset = captured.length();
    System.setProperty("a2at.samples.negotiation.city", "both");
    try {
      missingPortNegotiatesAndCompletesThroughTheSameSpringWorkflow();
      {
        String evidence = captured.since(offset);
        for (String city : java.util.List.of("City1", "City2")) {
          org.junit.jupiter.api.Assertions.assertTrue(
              evidence.contains("[SpnDomainAgent" + city + "Executor] NEGOTIATION_APPLIED"), city);
        }
        org.junit.jupiter.api.Assertions.assertTrue(
            evidence.contains("port=P781-珠江新城-PTN7900-23-TPA1EG24-17"));
        org.junit.jupiter.api.Assertions.assertTrue(
            evidence.contains("port=P882-珠江新城-PTN7900-23-TPA1EG24-11"));
      }
    } finally {
      if (previous == null) System.clearProperty("a2at.samples.negotiation.city");
      else System.setProperty("a2at.samples.negotiation.city", previous);
    }
  }

  @Test
  @Timeout(120)
  void missingPortNegotiatesAndCompletesThroughTheSameSpringWorkflow() throws Exception {
    int offset = captured.length();
    String previous = System.getProperty("a2at.samples.negotiation");
    System.setProperty("a2at.samples.negotiation", "true");
    try {
      runDemo();
      {
        String evidence = captured.since(offset);
        org.junit.jupiter.api.Assertions.assertTrue(evidence.contains("DEMO_NEGOTIATION"));
        org.junit.jupiter.api.Assertions.assertTrue(evidence.contains("TASK_STATE_INPUT_REQUIRED"));
        org.junit.jupiter.api.Assertions.assertTrue(
            evidence.matches("(?s).*\\\"performative\\\"\\s*:\\s*\\\"PROPOSE\\\".*"));
        org.junit.jupiter.api.Assertions.assertTrue(
            evidence.matches("(?s).*\\\"performative\\\"\\s*:\\s*\\\"ACCEPT\\\".*"));
        org.junit.jupiter.api.Assertions.assertTrue(evidence.contains("NORTHBOUND_DONE"));
        assertNegotiationWireEvidence(evidence);
      }
    } finally {
      if (previous == null) System.clearProperty("a2at.samples.negotiation");
      else System.setProperty("a2at.samples.negotiation", previous);
    }
  }

  protected String negotiationWireBoundary() {
    return "DIRECT_HTTP";
  }

  private void assertNegotiationWireEvidence(String evidence) {
    String uri = dev.openan.workflow.engine.client.A2ATExtension.NEGOTIATION_T.uri();
    var records = java.util.Arrays.asList(evidence.split("(?m)^.*? PROTOCOL - "));
    String request =
        records.stream()
            .filter(record -> record.startsWith("[" + negotiationWireBoundary() + "] REQUEST"))
            .filter(record -> record.contains("\"performative\": \"ACCEPT\""))
            .findFirst()
            .orElseThrow(() -> new AssertionError("No actual ACCEPT request log"));
    var id = java.util.regex.Pattern.compile("requestId=([^\\r\\n]+)").matcher(request);
    org.junit.jupiter.api.Assertions.assertTrue(id.find());
    String requestId = id.group(1);
    org.junit.jupiter.api.Assertions.assertTrue(
        records.stream()
            .anyMatch(
                record ->
                    record.startsWith("[" + negotiationWireBoundary() + "] REQUEST")
                        && record.contains("requestId=" + requestId)
                        && record.contains("A2A-Extensions: " + uri)),
        "Missing actual negotiation activation header");
    org.junit.jupiter.api.Assertions.assertTrue(request.contains("\"" + uri + "\":"));
    org.junit.jupiter.api.Assertions.assertTrue(request.contains("\"negotiationContext\": {"));
    org.junit.jupiter.api.Assertions.assertTrue(request.contains("=== Body ===\n{\n"));
    org.junit.jupiter.api.Assertions.assertTrue(
        records.stream()
            .anyMatch(
                record ->
                    record.contains("RESPONSE_BODY")
                        && record.contains("=== SSE data (JSON display; not wire text) ===")
                        && record.contains("\"performative\": \"PROPOSE\"")),
        "Missing observed PROPOSE SSE data");
  }

  protected String[] arguments() {
    return new String[] {
      "--a2a.transport-mode=direct",
      "--a2a.orch-url=http://127.0.0.1:1",
      "--a2a.a2at-env-path=" + OfflineA2ATLlmClient.envPath()
    };
  }

  @Test
  @Timeout(120)
  void northboundComplaintCompletesAndResourcesClose() throws Exception {
    String previous = System.getProperty("a2at.samples.negotiation");
    int offset = captured.length();
    System.setProperty("a2at.samples.negotiation", "false");
    try {
      runDemo();
      {
        String evidence = captured.since(offset);
        org.junit.jupiter.api.Assertions.assertTrue(
            evidence.contains("NEGOTIATION_DEMO enabled=false"));
        org.junit.jupiter.api.Assertions.assertFalse(evidence.contains("DEMO_NEGOTIATION agent="));
        org.junit.jupiter.api.Assertions.assertFalse(evidence.contains("NEGOTIATION_APPLIED"));
      }
    } finally {
      if (previous == null) System.clearProperty("a2at.samples.negotiation");
      else System.setProperty("a2at.samples.negotiation", previous);
    }
  }

  private void runDemo() throws Exception {
    OfflineA2ATLlmClient.install();
    String previous = System.getProperty("a2at.env.path");
    System.setProperty("a2at.env.path", OfflineA2ATLlmClient.envPath());
    try {
      // run() propagates failure unless the northbound Task reaches COMPLETED.
      new SpringSpnDemo().run(arguments());
    } finally {
      if (previous == null) System.clearProperty("a2at.env.path");
      else System.setProperty("a2at.env.path", previous);
    }
  }
}
