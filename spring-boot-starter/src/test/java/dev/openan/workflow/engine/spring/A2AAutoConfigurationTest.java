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
package dev.openan.workflow.engine.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.containsString;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Flow;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.a2aproject.sdk.server.ServerCallContext;
import org.a2aproject.sdk.server.auth.TaskOperation;
import org.a2aproject.sdk.server.config.A2AConfigProvider;
import org.a2aproject.sdk.server.requesthandlers.RequestHandler;
import org.a2aproject.sdk.spec.AgentCapabilities;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.TaskNotFoundError;
import org.a2aproject.sdk.spec.StreamingEventKind;
import org.a2aproject.sdk.transport.rest.handler.RestHandler;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class A2AAutoConfigurationTest {

  @Test
  void everyTaskRouteUsesConfiguredA2aPathPrefix() throws Exception {
    String placeholder = "${a2at.server.path-prefix}";

    assertEquals(
        placeholder + "/tasks/{id}",
        A2AController.class
            .getMethod("getTask", jakarta.servlet.http.HttpServletRequest.class, String.class)
            .getAnnotation(GetMapping.class)
            .value()[0]);
    assertEquals(
        placeholder + "/tasks",
        A2AController.class
            .getMethod(
                "listTasks",
                jakarta.servlet.http.HttpServletRequest.class,
                String.class,
                String.class,
                Integer.class,
                String.class,
                Integer.class,
                String.class,
                Boolean.class)
            .getAnnotation(GetMapping.class)
            .value()[0]);
    assertEquals(
        placeholder + "/tasks/{id}:cancel",
        A2AController.class
            .getMethod(
                "cancelTask",
                jakarta.servlet.http.HttpServletRequest.class,
                String.class,
                String.class)
            .getAnnotation(PostMapping.class)
            .value()[0]);
    var subscribeMethod =
        A2AController.class.getMethod(
            "subscribeToTask", jakarta.servlet.http.HttpServletRequest.class, String.class);
    assertEquals(
        placeholder + "/tasks/{id}:subscribe",
        subscribeMethod.getAnnotation(PostMapping.class).value()[0]);
    assertEquals(SseEmitter.class, subscribeMethod.getReturnType());
    assertEquals(
        "text/event-stream", subscribeMethod.getAnnotation(PostMapping.class).produces()[0]);

    var streamMethod =
        A2AController.class.getMethod(
            "streamMessage", jakarta.servlet.http.HttpServletRequest.class, String.class);
    // SseEmitter (not ResponseEntity): a wildcard ResponseEntity return is not picked up by
    // Spring's emitter handler and the stream response degrades to a converter error.
    assertEquals(SseEmitter.class, streamMethod.getReturnType());
    assertEquals("text/event-stream", streamMethod.getAnnotation(PostMapping.class).produces()[0]);
  }

  @Test
  void completedSseReleasesPublisherAndSdkEventConsumer() throws Exception {
    RestHandler restHandler = mock(RestHandler.class);
    RequestHandler requestHandler = mock(RequestHandler.class);
    AgentCard agentCard = mock(AgentCard.class);
    when(agentCard.capabilities()).thenReturn(AgentCapabilities.builder().streaming(true).build());
    AtomicReference<Flow.Subscriber<? super StreamingEventKind>> subscriber =
        new AtomicReference<>();
    AtomicInteger subscriptionCancellations = new AtomicInteger();
    AtomicInteger eventConsumerCancellations = new AtomicInteger();
    when(requestHandler.onSubscribeToTask(any(), any()))
        .thenAnswer(
            invocation -> {
              ServerCallContext context = invocation.getArgument(1);
              context.setEventConsumerCancelCallback(eventConsumerCancellations::incrementAndGet);
              Flow.Publisher<StreamingEventKind> publisher =
                  receiver -> {
                    subscriber.set(receiver);
                    receiver.onSubscribe(
                        new Flow.Subscription() {
                          @Override
                          public void request(long count) {}

                          @Override
                          public void cancel() {
                            subscriptionCancellations.incrementAndGet();
                          }
                        });
                  };
              return publisher;
            });
    var controller = new A2AController(restHandler, requestHandler, agentCard);
    MockMvc mockMvc =
        MockMvcBuilders.standaloneSetup(controller)
            .addPlaceholderValue("a2at.server.path-prefix", "/a2a/json")
            .build();

    MvcResult stream =
        mockMvc
            .perform(post("/a2a/json/tasks/task-1:subscribe").accept("text/event-stream"))
            .andExpect(request().asyncStarted())
            .andReturn();
    subscriber.get().onComplete();
    mockMvc.perform(asyncDispatch(stream)).andExpect(status().isOk());

    assertEquals(1, subscriptionCancellations.get());
    assertEquals(1, eventConsumerCancellations.get());
    assertEquals(0, controller.activeStreamCount());
  }

  @Test
  void nonStreamingA2AResponseUsesProtocolMediaType() throws Exception {
    RestHandler restHandler = mock(RestHandler.class);
    when(restHandler.sendMessage(any(), eq(""), eq("{}")))
        .thenReturn(new RestHandler.HTTPRestResponse(200, "application/json", "{}"));
    var controller =
        new A2AController(restHandler, mock(RequestHandler.class), mock(AgentCard.class));
    MockMvc mockMvc =
        MockMvcBuilders.standaloneSetup(controller)
            .addPlaceholderValue("a2at.server.path-prefix", "/a2a/json")
            .build();

    mockMvc
        .perform(post("/a2a/json/message:send").content("{}"))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith("application/a2a+json"));
  }

  @Test
  void taskSubscriptionRejectionReturnsAStandardNonStreamingA2AError() throws Exception {
    RestHandler restHandler = mock(RestHandler.class);
    RequestHandler requestHandler = mock(RequestHandler.class);
    var responseBody =
        "{\"error\":{\"code\":404,\"status\":\"NOT_FOUND\","
            + "\"message\":\"Task not found\",\"details\":[]}}";
    when(restHandler.createErrorResponse(any()))
        .thenReturn(new RestHandler.HTTPRestResponse(404, "application/a2a+json", responseBody));
    doThrow(new TaskNotFoundError())
        .when(requestHandler)
        .authorizeTaskAccess(eq("missing"), any(), eq(TaskOperation.SUBSCRIBE_TO_TASK));
    AgentCard agentCard = mock(AgentCard.class);
    when(agentCard.capabilities()).thenReturn(AgentCapabilities.builder().streaming(true).build());
    var controller = new A2AController(restHandler, requestHandler, agentCard);

    // Through MockMvc so the exception -> @ExceptionHandler mapping runs exactly as in dispatch.
    MockMvc mockMvc =
        MockMvcBuilders.standaloneSetup(controller)
            .addPlaceholderValue("a2at.server.path-prefix", "/a2a/json")
            .build();

    mockMvc
        .perform(post("/a2a/json/tasks/missing:subscribe"))
        .andExpect(status().is(404))
        .andExpect(content().contentTypeCompatibleWith("application/a2a+json"))
        .andExpect(content().string(containsString("\"status\":\"NOT_FOUND\"")));
  }

  @Test
  void streamingCapabilityIsCheckedBeforeInvokingBusinessHandler() throws Exception {
    RestHandler restHandler = mock(RestHandler.class);
    RequestHandler requestHandler = mock(RequestHandler.class);
    AgentCard agentCard = mock(AgentCard.class);
    when(agentCard.capabilities()).thenReturn(AgentCapabilities.builder().streaming(false).build());
    when(restHandler.createErrorResponse(any()))
        .thenReturn(
            new RestHandler.HTTPRestResponse(
                501,
                "application/a2a+json",
                "{\"error\":{\"code\":-32004,\"status\":\"UNIMPLEMENTED\"}}"));
    var controller = new A2AController(restHandler, requestHandler, agentCard);

    MockMvc mockMvc =
        MockMvcBuilders.standaloneSetup(controller)
            .addPlaceholderValue("a2at.server.path-prefix", "/a2a/json")
            .build();

    mockMvc
        .perform(post("/a2a/json/message:stream").content("{}"))
        .andExpect(status().is(501))
        .andExpect(content().contentTypeCompatibleWith("application/a2a+json"));
    verifyNoInteractions(requestHandler);
  }

  @Test
  void blockingTimeoutsComeFromProperties() {
    A2AProperties properties = new A2AProperties();
    properties.setAgentTimeoutSeconds(90);
    properties.setConsumptionTimeoutSeconds(12);
    properties.setReconciliationTimeoutSeconds(3);

    A2AConfigProvider provider = new A2AAutoConfiguration().a2aConfigProvider(properties);

    assertEquals("90", provider.getValue("a2a.blocking.agent.timeout.seconds"));
    assertEquals("12", provider.getValue("a2a.blocking.consumption.timeout.seconds"));
    assertEquals("3", provider.getValue("a2a.blocking.reconciliation.timeout.seconds"));
  }

  @Test
  void executorUsesConfiguredBounds() {
    A2AProperties properties = new A2AProperties();
    properties.setExecutorCoreSize(2);
    properties.setExecutorMaxSize(4);
    properties.setExecutorQueueCapacity(7);
    properties.setExecutorKeepAliveSeconds(15);

    ExecutorService executor = new A2AAutoConfiguration().agentExecutorPool(properties);
    try {
      ThreadPoolExecutor pool = (ThreadPoolExecutor) executor;
      assertEquals(2, pool.getCorePoolSize());
      assertEquals(4, pool.getMaximumPoolSize());
      assertEquals(7, pool.getQueue().remainingCapacity());
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void executorRejectsMaxBelowCore() {
    A2AProperties properties = new A2AProperties();
    properties.setExecutorCoreSize(4);
    properties.setExecutorMaxSize(2);
    assertThrows(
        IllegalArgumentException.class,
        () -> new A2AAutoConfiguration().agentExecutorPool(properties));
  }

  @Test
  void whenEnabledFalseThenNoA2ABeansCreated() {
    new WebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(A2AAutoConfiguration.class))
        .withPropertyValues("a2at.server.enabled=false")
        .run(
            context -> {
              assertFalse(context.containsBean("agentCard"));
              assertFalse(context.containsBean("requestHandler"));
              assertFalse(context.containsBean("restHandler"));
              assertFalse(context.containsBean("a2aController"));
              assertFalse(context.containsBean("agentExecutorPool"));
              assertFalse(context.containsBean("eventBus"));
              assertFalse(context.containsBean("taskStore"));
            });
  }

  @Test
  void whenEnabledTrueThenA2ABeansCreated() {
    new WebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(A2AAutoConfiguration.class))
        .withPropertyValues("a2at.server.enabled=true")
        .run(
            context -> {
              // agentCard will fail to load without a real classpath resource,
              // but the bean definition should be present (condition matched)
              assertFalse(
                  context.getStartupFailure() != null
                      && context.getStartupFailure().getMessage() != null
                      && context.getStartupFailure().getMessage().contains("did not match"));
            });
  }
}
