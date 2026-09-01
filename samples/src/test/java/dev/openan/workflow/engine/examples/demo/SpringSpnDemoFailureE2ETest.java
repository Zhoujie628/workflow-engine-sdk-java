/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.examples.demo;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.openan.workflow.engine.client.A2ATExtension;
import dev.openan.workflow.engine.examples.testsupport.CapturedLogs;
import dev.openan.workflow.engine.examples.testsupport.OfflineA2ATLlmClient;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Actual northbound Spring request through the engine to two HTTP OMC fixtures. No live LLM or OMC. */
class SpringSpnDemoFailureE2ETest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @TempDir Path temp;

  protected String[] arguments(String city1, String city2) throws Exception {
    return new String[] {
        "--a2a.transport-mode=direct",
        "--a2a.embedded-omc-enabled=false",
        "--a2a.orch-url=http://127.0.0.1:1",
        "--a2a.a2at-env-path=" + OfflineA2ATLlmClient.envPath()
    };
  }

  @ParameterizedTest
  @ValueSource(ints = {400, 429})
  @org.junit.jupiter.api.Timeout(60)
  void remoteProblemReachesNorthboundFailureWithoutMergeOrResubmission(int status) throws Exception {
    var firstCalls = new AtomicInteger();
    var secondCalls = new AtomicInteger();
    HttpServer city1 = omc(status, firstCalls);
    HttpServer city2 = omc(0, secondCalls);
    String first = "http://127.0.0.1:" + city1.getAddress().getPort();
    String second = "http://127.0.0.1:" + city2.getAddress().getPort();
    Map<String, String> previous = new java.util.HashMap<>();
    try (var logs = new CapturedLogs()) {
      set(previous, "A2A_AGENT_CARD_LOCATIONS",
          writeCard("city1", first).toUri() + "," + writeCard("city2", second).toUri());
      set(previous, "a2at.env.path", OfflineA2ATLlmClient.envPath());
      set(previous, "a2at.samples.negotiation", "false");
      OfflineA2ATLlmClient.install();
      var error = assertThrows(IllegalStateException.class,
          () -> new SpringSpnDemo().run(arguments(first, second)));
      assertTrue(error.getMessage().contains("TASK_STATE_FAILED"), error.getMessage());
      assertTrue(error.getMessage().contains("remote.problem." + status), error.getMessage());
      assertTrue(error.getMessage().contains(reason(status)), error.getMessage());
      assertTrue(error.getMessage().contains("diagnosis_city2"));
      assertEquals(1, firstCalls.get());
      assertEquals(1, secondCalls.get());
      String evidence = logs.since(0);
      assertTrue(evidence.contains("OPERATION_FAILED extension=Authorization-T"));
      assertTrue(evidence.contains("OPERATION_FAILED extension=Notification-T"));
      assertTrue(evidence.contains("[Executor] TASK_FAILED executionId="));
      assertTrue(evidence.contains("errorCode=remote.problem." + status));
      assertTrue(evidence.contains("skippedSteps=[merge_analysis]"));
      assertTrue(evidence.contains("[Orchestrator] WORKFLOW_FINISH success=false, history=2"));
      assertTrue(evidence.contains("=== SSE data (JSON display; not wire text) ==="));
      assertTrue(evidence.contains("\"status\": " + status));
      assertTrue(evidence.contains("[SpringWorkbench] TASK_FAILED"));
      assertTrue(evidence.contains("[Demo] SHUTDOWN_DONE success=false"));
      assertFalse(evidence.contains("[ERROR] null"));
      assertFalse(evidence.contains("executionId=null"));
      assertFalse(evidence.contains("Dispatching task: step=merge_analysis"));
      assertFalse(evidence.contains("\"name\": \"cross-city-diagnosis-summary\""));
      assertFalse(evidence.contains("NEGOTIATION_APPLIED"));
      var failures = java.util.regex.Pattern.compile("\\[DIRECT_HTTP\\] FAILURE requestId=([a-f0-9-]+)")
          .matcher(evidence).results().map(match -> match.group(1)).toList();
      assertEquals(failures.size(), new java.util.HashSet<>(failures).size(),
          "body and future errors must not duplicate one HTTP failure log");
    } finally {
      previous.forEach((key, value) -> {
        if (value == null) System.clearProperty(key);
        else System.setProperty(key, value);
      });
      city1.stop(0);
      city2.stop(0);
    }
  }

  private static void set(Map<String, String> previous, String key, String value) {
    previous.put(key, System.getProperty(key));
    System.setProperty(key, value);
  }

  private Path writeCard(String city, String target) throws Exception {
    try (var input = getClass().getResourceAsStream("/agentcard/spn_domain_agent_" + city + ".json")) {
      var card = (com.fasterxml.jackson.databind.node.ObjectNode) JSON.readTree(input);
      ((com.fasterxml.jackson.databind.node.ObjectNode) card.path("supportedInterfaces").get(0))
          .put("url", target + "/a2a/json");
      // Auth is covered by dedicated direct/Order tests; these fixtures exercise task failure propagation.
      card.remove(List.of("securitySchemes", "securityRequirements"));
      Path file = temp.resolve(city + ".json");
      JSON.writeValue(file.toFile(), card);
      return file;
    }
  }

  private static String reason(int status) {
    return status == 400 ? "该端口下未发现业务信息，请校验业务的接入网元、端口信息是否正确"
        : "Current active tasks have reached the maximum limit 10. Please try again later.";
  }

  private static HttpServer omc(int status, AtomicInteger calls) throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", exchange -> {
      if ("GET".equals(exchange.getRequestMethod())
          && exchange.getRequestURI().getPath().endsWith("/tasks")) {
        byte[] bytes =
            JSON.writeValueAsBytes(
                Map.of("tasks", List.of(), "totalSize", 0, "pageSize", 0));
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (var output = exchange.getResponseBody()) {
          output.write(bytes);
        } finally {
          exchange.close();
        }
        return;
      }
      var request = JSON.readTree(exchange.getRequestBody());
      boolean task = request.path("message").path("metadata").has(A2ATExtension.TASK_T.uri());
      String response;
      if (!task) {
        // Deliberate pre-position failures must not prevent the subsequent workflow tasks.
        response = JSON.writeValueAsString(Map.of("status", 503, "detail", "Independent operation rejected"));
      } else if (status != 0) {
        calls.incrementAndGet();
        response = JSON.writeValueAsString(Map.of(
            "status", status, "detail", reason(status), "type", "", "timestamp", "2026-08-31T09:07:35Z"));
      } else {
        calls.incrementAndGet();
        response = JSON.writeValueAsString(Map.of("task", Map.of(
            "id", "city2-diagnosis", "contextId", request.path("message").path("contextId").asText(),
            "status", Map.of("state", "TASK_STATE_COMPLETED"),
            "artifacts", List.of(Map.of("artifactId", "diagnosis", "parts", List.of(Map.of("text", "City2 diagnosis")))))));
      }
      byte[] bytes = ("data: " + response + "\n\n").getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "text/event-stream;charset=UTF-8");
      exchange.sendResponseHeaders(200, 0);
      try (var output = exchange.getResponseBody()) { output.write(bytes); }
      finally { exchange.close(); }
    });
    server.start();
    return server;
  }
}
