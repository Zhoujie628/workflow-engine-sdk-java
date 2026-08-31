/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.openan.workflow.engine.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.client.transport.spi.interceptors.ClientCallContext;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.MessageSendParams;
import org.junit.jupiter.api.Test;

class A2ATransportHeaderTest {

  private static final String TASK_T_URI =
      "https://projects.tmforum.org/a2aproject/telecommunication/extensions/Task-T/v1";

  private static dev.openan.workflow.engine.model.MessageContent content(
      String text, Map<String, Object> metadata) {
    return new dev.openan.workflow.engine.model.MessageContent(
        List.of(new org.a2aproject.sdk.spec.TextPart(text)),
        metadata,
        metadata == null ? java.util.Set.of() : metadata.keySet());
  }

  private static AgentCard agentCard() throws Exception {
    String json =
        """
                {
                  "name": "Test Agent",
                  "description": "test",
                  "version": "1.0",
                  "capabilities": {
                    "streaming": false,
                    "extensions": [
                      {"uri": "%s", "required": false}
                    ]
                  },
                  "defaultInputModes": ["text/plain"],
                  "defaultOutputModes": ["text/plain"],
                  "skills": [],
                  "supportedInterfaces": [
                    {
                      "protocolBinding": "HTTP+JSON",
                      "protocolVersion": "1.0",
                      "url": "https://agent.example.test/a2a/json",
                      "tenant": ""
                    }
                  ]
                }
                """
            .formatted(TASK_T_URI);
    return new ObjectMapper()
        .registerModule(new AgentCardJacksonModule())
        .readValue(json, AgentCard.class);
  }

  private static AgentCard securedAgentCard() throws Exception {
    String json =
        """
                {
                  "name": "Test Agent",
                  "description": "test",
                  "version": "1.0",
                  "capabilities": {"streaming": false},
                  "defaultInputModes": ["text/plain"],
                  "defaultOutputModes": ["text/plain"],
                  "skills": [],
                  "securitySchemes": {
                    "bearerAuth": {
                      "httpAuthSecurityScheme": {"scheme": "Bearer"}
                    }
                  },
                  "securityRequirements": [
                    {"schemes": {"bearerAuth": {}}}
                  ],
                  "supportedInterfaces": [
                    {
                      "protocolBinding": "HTTP+JSON",
                      "protocolVersion": "1.0",
                      "url": "https://agent.example.test/a2a/json",
                      "tenant": ""
                    }
                  ]
                }
                """;
    return new ObjectMapper()
        .registerModule(new AgentCardJacksonModule())
        .readValue(json, AgentCard.class);
  }

  @Test
  void forwardsAuthorizationAndActiveExtensionHeadersToRuntime() throws Exception {
    AtomicReference<Map<String, String>> capturedHeaders = new AtomicReference<>();
    A2AJavaClientRuntime runtime = new CapturingRuntime(capturedHeaders);
    AgentCard card = agentCard();
    WorkflowEngineClientConfig config =
        WorkflowEngineClientConfig.builder()
            .authProvider(
                (agentName, ignored, headers) -> headers.put("Authorization", "Bearer test-token"))
            .build();

    try (A2ATransport transport = new A2ATransport(List.of(card), runtime, config)) {
      transport
          .send(
              card,
              card.name(),
              content("diagnose", Map.of(TASK_T_URI, "structured task")),
              "context-1",
              null,
              null)
          .join();
    }

    assertEquals("Bearer test-token", capturedHeaders.get().get("Authorization"));
    assertEquals(TASK_T_URI, capturedHeaders.get().get("A2A-Extensions"));
  }

  @Test
  void notificationTransportFailureIsNotReportedAsSubscriptionSuccess() throws Exception {
    AgentCard card = agentCard();
    A2AJavaClientRuntime runtime =
        new A2AJavaClientRuntime() {
          @Override
          public Iterable<ClientEvent> sendMessage(
              AgentCard agentCard,
              MessageSendParams params,
              ClientCallContext callContext,
              Consumer<ClientEvent> eventSink,
              Consumer<String> logSink) {
            throw new IllegalStateException("gateway unavailable");
          }

          @Override
          public void close() {}
        };
    WorkflowEngineClientConfig config = WorkflowEngineClientConfig.builder().build();

    try (A2ATransport transport = new A2ATransport(List.of(card), runtime, config)) {
      CompletionException error =
          assertThrows(
              CompletionException.class,
              () ->
                  transport
                      .openNotificationStream(
                          card,
                          card.name(),
                          content("subscribe", Map.of(TASK_T_URI, "notification")),
                          "context-1",
                          null)
                      .acknowledgement()
                      .join());
      assertEquals("gateway unavailable", error.getCause().getMessage());
    }
  }

  @Test
  void agentCardSecurityRequirementWithoutCredentialsFailsClosed() throws Exception {
    AgentCard card = securedAgentCard();
    A2AJavaClientRuntime runtime =
        new A2AJavaClientRuntime() {
          @Override
          public Iterable<ClientEvent> sendMessage(
              AgentCard agentCard,
              MessageSendParams params,
              ClientCallContext callContext,
              Consumer<ClientEvent> eventSink,
              Consumer<String> logSink) {
            throw new AssertionError("unauthenticated request must not reach runtime");
          }

          @Override
          public void close() {}
        };

    try (A2ATransport transport =
        new A2ATransport(List.of(card), runtime, WorkflowEngineClientConfig.builder().build())) {
      CompletionException error =
          assertThrows(
              CompletionException.class,
              () ->
                  transport
                      .send(card, card.name(), content("diagnose", null), "context-1", null, null)
                      .join());
      assertTrue(error.getCause() instanceof SecurityException);
      assertTrue(error.getCause().getMessage().contains("none are configured"));
    }
  }

  @Test
  void authProviderCanSatisfySecurityRequirementWithoutCredentials() throws Exception {
    AtomicReference<Map<String, String>> capturedHeaders = new AtomicReference<>();
    A2AJavaClientRuntime runtime = new CapturingRuntime(capturedHeaders);
    AgentCard card = securedAgentCard();
    WorkflowEngineClientConfig config =
        WorkflowEngineClientConfig.builder()
            .authProvider(
                (agentName, ignored, headers) ->
                    headers.put("Authorization", "Bearer gateway-token"))
            .build();

    try (A2ATransport transport = new A2ATransport(List.of(card), runtime, config)) {
      transport.send(card, card.name(), content("diagnose", null), "context-1", null, null).join();
    }

    assertEquals("Bearer gateway-token", capturedHeaders.get().get("Authorization"));
  }

  @Test
  void authenticationHeaderConflictsAreCaseInsensitive() {
    Map<String, String> headers = new HashMap<>();
    headers.put("authorization", "Bearer provider-token");

    SecurityException error =
        assertThrows(
            SecurityException.class,
            () ->
                ClientCallContextFactory.mergeHeaders(
                    "Test Agent", headers, Map.of("Authorization", "Bearer credential-token")));

    assertEquals(
        "Authentication header conflict for agent Test Agent: Authorization", error.getMessage());
    assertEquals(1, headers.size());
  }

  @Test
  void closeWaitsUntilNotificationTransportCallHasExited() throws Exception {
    AgentCard card = agentCard();
    CountDownLatch entered = new CountDownLatch(1);
    AtomicBoolean exited = new AtomicBoolean();
    A2AJavaClientRuntime runtime =
        new A2AJavaClientRuntime() {
          @Override
          public Iterable<ClientEvent> sendMessage(
              AgentCard agentCard,
              MessageSendParams params,
              ClientCallContext callContext,
              Consumer<ClientEvent> eventSink,
              Consumer<String> logSink) {
            entered.countDown();
            try {
              new CountDownLatch(1).await();
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            } finally {
              exited.set(true);
            }
            return List.of();
          }

          @Override
          public void close() {}
        };
    A2ATransport transport =
        new A2ATransport(List.of(card), runtime, WorkflowEngineClientConfig.builder().build());

    transport.openNotificationStream(
        card, card.name(), content("subscribe", Map.of()), "context-1", null);
    assertTrue(entered.await(1, TimeUnit.SECONDS));

    transport.close();

    assertTrue(exited.get(), "close must wait for the notification call to terminate");
  }

  @Test
  void borrowedClientDoesNotCloseCallerOwnedTransport() throws Exception {
    AtomicInteger closeCalls = new AtomicInteger();
    A2ATransport transport =
        new A2ATransport(
            List.of(agentCard()),
            new CountingRuntime(closeCalls),
            WorkflowEngineClientConfig.builder().build());

    new DefaultWorkflowEngineClient(transport).close();
    assertEquals(0, closeCalls.get());

    transport.close();
    assertEquals(1, closeCalls.get());
  }

  @Test
  void owningClientClosesTransportExactlyOnce() throws Exception {
    AtomicInteger closeCalls = new AtomicInteger();
    A2ATransport transport =
        new A2ATransport(
            List.of(agentCard()),
            new CountingRuntime(closeCalls),
            WorkflowEngineClientConfig.builder().build());
    DefaultWorkflowEngineClient client = DefaultWorkflowEngineClient.owning(transport);

    client.close();
    client.close();

    assertEquals(1, closeCalls.get());
  }

  @Test
  void finalStructuredContentNeedsNoContentSdk() throws Exception {
    AtomicReference<MessageSendParams> captured = new AtomicReference<>();
    try (var transport =
        new A2ATransport(
            List.of(agentCard()),
            DefaultWorkflowEngineClientNegotiationTest.runtime(
                params -> {
                  captured.set(params);
                  return List.of();
                }),
            WorkflowEngineClientConfig.builder().build())) {
      var content =
          new dev.openan.workflow.engine.model.MessageContent(
              List.of(new org.a2aproject.sdk.spec.DataPart(Map.of("any", List.of(1, 2)))),
              Map.of("contextId", "business-only"),
              java.util.Set.of("urn:optional:custom"));
      new DefaultWorkflowEngineClient(transport).sendMessage("Test Agent", content).join();
      assertEquals(content.parts(), captured.get().message().parts());
      assertEquals(content.metadata(), captured.get().message().metadata());
      assertTrue(!"business-only".equals(captured.get().message().contextId()));
    }
  }

  private record CapturingRuntime(AtomicReference<Map<String, String>> capturedHeaders)
      implements A2AJavaClientRuntime {

    @Override
    public Iterable<ClientEvent> sendMessage(
        AgentCard agentCard,
        MessageSendParams params,
        ClientCallContext callContext,
        Consumer<ClientEvent> eventSink,
        Consumer<String> logSink) {
      capturedHeaders.set(Map.copyOf(callContext.getHeaders()));
      return List.of();
    }

    @Override
    public void close() {}
  }

  private record CountingRuntime(AtomicInteger closeCalls) implements A2AJavaClientRuntime {
    @Override
    public Iterable<ClientEvent> sendMessage(
        AgentCard agentCard,
        MessageSendParams params,
        ClientCallContext callContext,
        Consumer<ClientEvent> eventSink,
        Consumer<String> logSink) {
      return List.of();
    }

    @Override
    public void close() {
      closeCalls.incrementAndGet();
    }
  }

  private record SendCountingRuntime(AtomicInteger sendCalls) implements A2AJavaClientRuntime {
    @Override
    public Iterable<ClientEvent> sendMessage(
        AgentCard agentCard,
        MessageSendParams params,
        ClientCallContext callContext,
        Consumer<ClientEvent> eventSink,
        Consumer<String> logSink) {
      sendCalls.incrementAndGet();
      return List.of();
    }

    @Override
    public void close() {}
  }
}
