/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.examples.gateway;


import dev.openan.workflow.engine.client.A2AJavaClientRuntime;
import dev.openan.workflow.engine.client.ConversationScopedA2AJavaClientRuntime;
import dev.openan.workflow.engine.client.SslContextFactory;

import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.client.transport.spi.interceptors.ClientCallContext;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.MessageSendParams;

import javax.net.ssl.SSLContext;
import java.net.URI;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Local simulation wrapper which keeps {@link OrderGatewayClientRuntime} as the runtime under test.
 *
 * <p>Only the external Order SDK session is replaced with {@link MockOrderHttpSessionClient};
 * AgentCard routing, streaming selection, timeout conversion, response parsing, terminal
 * detection, logging and session lifecycle all execute through the production adapter.
 */
public final class MockGatewayClientRuntime
        implements A2AJavaClientRuntime, ConversationScopedA2AJavaClientRuntime {
    private static final Map<String, String> AGENT_NE_ROUTES =
            Map.of(
                    "SPN Domain Agent City1", "mock-ne-city1",
                    "SPN Domain Agent City2", "mock-ne-city2");
    private static final Map<String, String> NE_TARGETS =
            Map.of(
                    "mock-ne-city1", "https://127.0.0.1:26335",
                    "mock-ne-city2", "https://127.0.0.1:26336");

    private final OrderGatewayClientRuntime delegate;

    public MockGatewayClientRuntime(String gatewayUrl) {
        OrderGatewayClientRuntime.OrderConfig config =
                OrderGatewayClientRuntime.OrderConfig.builder()
                        .host(gatewayHost(gatewayUrl))
                        .port(gatewayPort(gatewayUrl))
                        .username("mock-workbench")
                        .password("mock-password")
                        .agentNeRoutes(AGENT_NE_ROUTES)
                        .timeoutSeconds(600)
                        .build();
        delegate =
                new OrderGatewayClientRuntime(
                        config,
                        new ConfiguredAgentGatewayRouteResolver(AGENT_NE_ROUTES, null),
                        new MockOrderSessionFactory(gatewayUrl),
                        new GatewayA2AResponseParser());
    }

    @Override
    public Iterable<ClientEvent> sendMessage(
            AgentCard agentCard,
            MessageSendParams params,
            ClientCallContext callContext,
            Consumer<ClientEvent> eventSink,
            Consumer<String> logSink) {
        return delegate.sendMessage(agentCard, params, callContext, eventSink, logSink);
    }

    @Override
    public void closeConversation(AgentCard agentCard, String contextId) {
        delegate.closeConversation(agentCard, contextId);
    }

    @Override
    public void close() {
        delegate.close();
    }

    private static String gatewayHost(String gatewayUrl) {
        String host = URI.create(gatewayUrl).getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Mock gateway URL must contain a host");
        }
        return host;
    }

    private static int gatewayPort(String gatewayUrl) {
        URI uri = URI.create(gatewayUrl);
        if (uri.getPort() > 0) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static final class MockOrderSessionFactory
            implements OrderGatewayClientRuntime.OrderSessionFactory {
        private final String gatewayUrl;
        private final SSLContext sslContext = SslContextFactory.createTrustAll();

        private MockOrderSessionFactory(String gatewayUrl) {
            this.gatewayUrl = gatewayUrl;
        }

        @Override
        public OrderGatewayClientRuntime.OrderSession open(AgentGatewayRoute route) {
            String target = NE_TARGETS.get(route.ne());
            if (target == null) {
                throw new IllegalArgumentException("Unknown mock NE: " + route.ne());
            }
            return new MockOrderHttpSessionClient(gatewayUrl, sslContext, target);
        }
    }
}
