/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 *    Licensed under the Apache License, Version 2.0 (the License); you may
 *    not use this file except in compliance with the License. You may obtain
 *    a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an AS IS BASIS, WITHOUT
 *    WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *    License for the specific language governing permissions and limitations
 *    under the License.
 */

package dev.openan.workflow.engine.client;

import io.grpc.ManagedChannelBuilder;

import org.a2aproject.sdk.client.Client;
import dev.openan.workflow.engine.model.SendMessageResult;
import org.a2aproject.sdk.spec.TaskQueryParams;
import org.a2aproject.sdk.spec.TaskIdParams;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.CancelTaskParams;
import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.client.MessageEvent;
import org.a2aproject.sdk.client.TaskEvent;
import org.a2aproject.sdk.client.TaskUpdateEvent;
import org.a2aproject.sdk.client.http.A2AHttpClient;
import org.a2aproject.sdk.client.http.JdkA2AHttpClient;
import org.a2aproject.sdk.client.transport.grpc.GrpcTransport;
import org.a2aproject.sdk.client.transport.grpc.GrpcTransportConfig;
import org.a2aproject.sdk.client.transport.jsonrpc.JSONRPCTransport;
import org.a2aproject.sdk.client.transport.jsonrpc.JSONRPCTransportConfig;
import org.a2aproject.sdk.client.transport.rest.RestTransport;
import org.a2aproject.sdk.client.transport.rest.RestTransportConfig;
import org.a2aproject.sdk.client.transport.spi.interceptors.ClientCallContext;
import org.a2aproject.sdk.spec.A2AClientException;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentInterface;
import org.a2aproject.sdk.spec.TaskArtifactUpdateEvent;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Default implementation of {@link A2AJavaClientRuntime} that uses the a2a-java SDK's {@link
 * Client} with {@link RestTransport}.
 *
 * <p>This replaces the engine's previous hand-written HTTP fallback ({@code sendViaRawHttp}) with
 * the SDK's built-in transport, SSE parsing, and error handling. Clients are cached by endpoint,
 * protocol, AgentCard version, and TLS policy so connections and TLS sessions can be reused.
 *
 * <p>SSL handling: when {@code sslVerify=false}, certificate-chain verification is disabled only
 * for clients created by this runtime. Hostname verification remains enabled and no JVM-wide TLS
 * property is changed.
 */
public class DefaultA2AJavaClientRuntime
        implements A2AJavaClientRuntime, ConversationScopedA2AJavaClientRuntime {

    private static final Logger log = LoggerFactory.getLogger(DefaultA2AJavaClientRuntime.class);

    private final boolean sslVerify;
    private final String caCertsPath;
    private final String clientCertPath;
    private final String clientKeyPath;
    private final String clientKeyPassword;
    private final String crlPath;
    private final long sendTimeoutSeconds;
    private final String preferredProtocol;
    private final java.util.concurrent.ExecutorService httpClientExecutor;
    private final Map<ClientCacheKey, Client> clientCache = new ConcurrentHashMap<>();
    /** Notification-T streams need a client that can be closed without disrupting task traffic. */
    private final Map<StreamClientKey, Client> streamClients = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * Create a runtime with the given SSL configuration.
     *
     * @param sslVerify whether to verify server TLS certificates
     * @param caCertsPath optional path to a PEM CA trust store (null = use default)
     * @param sendTimeoutSeconds SSE response wait timeout in seconds
     */
    public DefaultA2AJavaClientRuntime(
            boolean sslVerify,
            String caCertsPath,
            long sendTimeoutSeconds,
            String preferredProtocol) {
        this(
                sslVerify,
                caCertsPath,
                null,
                null,
                null,
                null,
                sendTimeoutSeconds,
                preferredProtocol,
                4,
                16,
                256);
    }

    public DefaultA2AJavaClientRuntime(
            boolean sslVerify,
            String caCertsPath,
            String clientCertPath,
            String clientKeyPath,
            String clientKeyPassword,
            String crlPath,
            long sendTimeoutSeconds,
            String preferredProtocol,
            int executorCoreSize,
            int executorMaxSize,
            int executorQueueCapacity) {
        this.sslVerify = sslVerify;
        this.caCertsPath = caCertsPath;
        this.clientCertPath = clientCertPath;
        this.clientKeyPath = clientKeyPath;
        this.clientKeyPassword = clientKeyPassword;
        this.crlPath = crlPath;
        this.sendTimeoutSeconds = sendTimeoutSeconds;
        this.preferredProtocol = preferredProtocol;
        this.httpClientExecutor =
                new ThreadPoolExecutor(
                        executorCoreSize,
                        executorMaxSize,
                        60L,
                        TimeUnit.SECONDS,
                        new ArrayBlockingQueue<>(executorQueueCapacity),
                        r -> {
                            Thread t = new Thread(r, "a2a-client");
                            t.setDaemon(true);
                            return t;
                        },
                        new ThreadPoolExecutor.CallerRunsPolicy());
        if (!sslVerify) {
            log.warn(
                    "[A2ARuntime] TLS certificate-chain verification is disabled; hostname verification remains enabled");
        }
        log.info(
                "[A2ARuntime] Initialized: sslVerify={}, caCerts={}, timeout={}s",
                sslVerify,
                caCertsPath,
                sendTimeoutSeconds);
    }

    /** Simplified constructor using strict TLS verification and the JVM default trust store. */
    public DefaultA2AJavaClientRuntime() {
        this(true, null, 600L, null);
    }

    private static String extractAgentUrl(AgentCard agentCard) {
        if (!agentCard.supportedInterfaces().isEmpty()) {
            return agentCard.supportedInterfaces().get(0).url();
        }
        return "?";
    }

    private static void onEvent(
            String agentName,
            ClientEvent event,
            List<ClientEvent> events,
            AtomicReference<ClientEvent> lastEventRef,
            Consumer<ClientEvent> eventSink,
            CountDownLatch done) {
        events.add(event);
        lastEventRef.set(event);
        logEvent(agentName, event);
        ProtocolLogger.logResponseEvent(agentName, event);
        if (eventSink != null) {
            try {
                eventSink.accept(event);
            } catch (Exception e) {
                log.warn(
                        "[A2ARuntime] eventSink callback failed for {} (event_class={}): {}",
                        agentName,
                        event.getClass().getSimpleName(),
                        e.getMessage(),
                        e);
            }
        }
        if (isTerminal(event)) {
            log.info(
                    "[A2ARuntime] Terminal event for '{}': {}",
                    agentName,
                    describeTerminalEvent(event));
            done.countDown();
        }
    }

    private static void onError(
            String agentName,
            Throwable error,
            CountDownLatch done,
            AtomicReference<Throwable> errorRef) {
        if (done.getCount() == 0) {
            log.debug(
                    "[A2ARuntime] Connection closed after terminal event for '{}': {}",
                    agentName,
                    error.getMessage());
        } else {
            String msg = error.getMessage() != null ? error.getMessage() : "";
            boolean connectionClosed =
                    msg.contains("connection closed locally")
                            || msg.contains("chunked transfer encoding, state: READING_LENGTH");
            if (connectionClosed) {
                log.debug("[A2ARuntime] Connection closed for '{}': {}", agentName, msg);
            } else {
                errorRef.set(error);
                log.error(
                        "[A2ARuntime] Error callback for '{}': {}",
                        agentName,
                        error.getMessage(),
                        error);
            }
            done.countDown();
        }
    }

    private static boolean isTerminal(ClientEvent event) {
        if (event instanceof TaskEvent taskEvent) {
            return isTerminal(taskEvent.getTask().status().state());
        }
        if (event instanceof TaskUpdateEvent taskUpdateEvent) {
            if (taskUpdateEvent.getUpdateEvent() instanceof TaskStatusUpdateEvent statusUpdate) {
                return isTerminal(statusUpdate.status().state());
            }
            // Artifact updates are not terminal
            if (taskUpdateEvent.getUpdateEvent() instanceof TaskArtifactUpdateEvent) {
                return false;
            }
        }
        return false;
    }

    private static boolean isTerminal(TaskState state) {
        return state == TaskState.TASK_STATE_COMPLETED
                || state == TaskState.TASK_STATE_FAILED
                || state == TaskState.TASK_STATE_CANCELED
                || state == TaskState.TASK_STATE_REJECTED
                || state == TaskState.TASK_STATE_INPUT_REQUIRED
                || state == TaskState.TASK_STATE_AUTH_REQUIRED;
    }

    private static void logEvent(String agentName, ClientEvent event) {
        if (event instanceof TaskEvent te) {
            TaskStatus st = te.getTask().status();
            log.info(
                    "[A2ARuntime] Event[Task] agent='{}', state={}, final={}",
                    agentName,
                    st.state(),
                    isTerminal(st.state()));
        } else if (event instanceof TaskUpdateEvent tue) {
            if (tue.getUpdateEvent() instanceof TaskStatusUpdateEvent sue) {
                TaskStatus st = sue.status();
                log.info(
                        "[A2ARuntime] Event[StatusUpdate] agent='{}', state={}, final={}",
                        agentName,
                        st.state(),
                        sue.isFinal());
            } else if (tue.getUpdateEvent() instanceof TaskArtifactUpdateEvent ae) {
                log.info(
                        "[A2ARuntime] Event[ArtifactUpdate] agent='{}', name={}, append={}, lastChunk={}",
                        agentName,
                        ae.artifact().name(),
                        ae.append(),
                        ae.lastChunk());
            }
        } else if (event instanceof MessageEvent me) {
            log.info(
                    "[A2ARuntime] Event[Message] agent='{}', role={}, parts={}",
                    agentName,
                    me.getMessage().role(),
                    me.getMessage().parts().size());
        } else {
            log.debug(
                    "[A2ARuntime] Event[{}] agent='{}'",
                    event.getClass().getSimpleName(),
                    agentName);
        }
    }

    private static String describeTerminalEvent(ClientEvent event) {
        if (event instanceof TaskEvent te) {
            return te.getTask().status().state().name();
        }
        if (event instanceof TaskUpdateEvent tue
                && tue.getUpdateEvent() instanceof TaskStatusUpdateEvent sue) {
            return sue.status().state().name();
        }
        return event.getClass().getSimpleName();
    }

    @Override
    public Iterable<ClientEvent> sendMessage(
            AgentCard agentCard,
            org.a2aproject.sdk.spec.MessageSendParams params,
            ClientCallContext callContext,
            Consumer<ClientEvent> eventSink,
            Consumer<String> logSink) {
        if (closed.get()) throw new IllegalStateException("A2A client runtime is closed");
        String agentUrl = extractAgentUrl(agentCard);
        Client client =
                isNotificationStream(params)
                        ? getOrCreateStreamClient(
                                agentCard,
                                params.message() != null ? params.message().contextId() : null)
                        : getOrCreateClient(agentCard, agentUrl);
        List<ClientEvent> events = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> errorRef = new AtomicReference<>();
        AtomicReference<ClientEvent> lastEventRef = new AtomicReference<>();

        if (logSink != null) logSink.accept("[A2A] Sending message to " + agentCard.name());

        try {
            client.sendMessage(
                    params,
                    List.of(
                            (event, card) ->
                                    onEvent(
                                            agentCard.name(),
                                            event,
                                            events,
                                            lastEventRef,
                                            eventSink,
                                            done)),
                    error -> onError(agentCard.name(), error, done, errorRef),
                    callContext);
        } catch (A2AClientException e) {
            throw new RuntimeException(
                    "A2A message:send failed for " + agentCard.name() + ": " + e.getMessage(), e);
        }

        awaitCompletion(agentCard.name(), done, events, lastEventRef);

        if (errorRef.get() != null) {
            throw new RuntimeException(
                    "A2A message:send failed for "
                            + agentCard.name()
                            + ": "
                            + errorRef.get().getMessage(),
                    errorRef.get());
        }
        log.info("[A2ARuntime] Completed for '{}': {} event(s)", agentCard.name(), events.size());
        return events;
    }

    private Client getOrCreateClient(AgentCard agentCard, String agentUrl) {
        AgentInterface selected = selectInterface(agentCard);
        String protocolBinding = selected.protocolBinding();
        ClientCacheKey key =
                new ClientCacheKey(
                        agentCard.name(),
                        agentCard.version(),
                        selected.url(),
                        protocolBinding,
                        sslVerify,
                        caCertsPath,
                        clientCertPath,
                        clientKeyPath,
                        crlPath);
        return clientCache.computeIfAbsent(
                key,
                ignored -> {
                    A2AHttpClient httpClient = createHttpClient();
                    try {
                        Client client =
                                buildClientWithTransport(agentCard, protocolBinding, httpClient);
                        log.info(
                                "[A2ARuntime] Created cached transport: {} for '{}' ({})",
                                protocolBinding,
                                agentCard.name(),
                                selected.url());
                        return client;
                    } catch (A2AClientException e) {
                        throw new IllegalStateException(
                                "Failed to create a2a-java client for "
                                        + agentCard.name()
                                        + " at "
                                        + agentUrl,
                                e);
                    }
                });
    }

    private Client getOrCreateStreamClient(AgentCard agentCard, String contextId) {
        if (contextId == null || contextId.isBlank()) {
            throw new IllegalArgumentException("Notification-T contextId must not be blank");
        }
        StreamClientKey key = new StreamClientKey(agentCard.name(), contextId);
        return streamClients.computeIfAbsent(
                key,
                ignored -> {
                    AgentInterface selected = selectInterface(agentCard);
                    try {
                        Client client =
                                buildClientWithTransport(
                                        agentCard, selected.protocolBinding(), createHttpClient());
                        log.info(
                                "[A2ARuntime] Created conversation-scoped Notification-T transport for '{}' (contextId={})",
                                agentCard.name(),
                                contextId);
                        return client;
                    } catch (A2AClientException e) {
                        throw new IllegalStateException(
                                "Failed to create Notification-T client for "
                                        + agentCard.name()
                                        + " context "
                                        + contextId,
                                e);
                    }
                });
    }

    private static boolean isNotificationStream(
            org.a2aproject.sdk.spec.MessageSendParams params) {
        return params.message() != null
                && params.message().metadata() != null
                && params.message().metadata().containsKey(A2ATExtension.NOTIFICATION_T.uri());
    }

    @Override
    public void closeConversation(AgentCard agentCard, String contextId) {
        if (agentCard == null || contextId == null || contextId.isBlank()) {
            return;
        }
        Client client = streamClients.remove(new StreamClientKey(agentCard.name(), contextId));
        if (client != null) {
            client.close();
            log.info(
                    "[A2ARuntime] Closed conversation-scoped Notification-T transport for '{}' (contextId={})",
                    agentCard.name(),
                    contextId);
        }
    }

    /** Select the best AgentInterface based on preferredProtocol or first available. */
    private AgentInterface selectInterface(AgentCard agentCard) {
        List<AgentInterface> interfaces = agentCard.supportedInterfaces();
        if (interfaces == null || interfaces.isEmpty()) {
            throw new RuntimeException("AgentCard has no supportedInterfaces: " + agentCard.name());
        }
        if (preferredProtocol != null && !preferredProtocol.isBlank()) {
            for (AgentInterface iface : interfaces) {
                if (preferredProtocol.equalsIgnoreCase(iface.protocolBinding())) {
                    log.info(
                            "[A2ARuntime] Selected preferred protocol {} for '{}'",
                            preferredProtocol,
                            agentCard.name());
                    return iface;
                }
            }
            log.warn(
                    "[A2ARuntime] Preferred protocol '{}' not in supportedInterfaces for '{}', using first available: {}",
                    preferredProtocol,
                    agentCard.name(),
                    interfaces.get(0).protocolBinding());
        }
        return interfaces.get(0);
    }

    /**
     * Build the client with the transport matching the protocol binding.
     *
     * <p>HTTP+JSON and JSONRPC use A2AHttpClient for SSL configuration. GRPC uses
     * GrpcTransportConfig with a custom Channel factory. Insecure gRPC is plaintext and cannot
     * carry mTLS or CRL options; such combinations fail fast instead of being ignored.
     */
    private Client buildClientWithTransport(
            AgentCard agentCard, String protocolBinding, A2AHttpClient httpClient)
            throws A2AClientException {
        if ("JSONRPC".equalsIgnoreCase(protocolBinding)) {
            return Client.builder(agentCard)
                    .withTransport(JSONRPCTransport.class, new JSONRPCTransportConfig(httpClient))
                    .build();
        }
        if ("GRPC".equalsIgnoreCase(protocolBinding)) {
            GrpcTransportConfig grpcConfig = new GrpcTransportConfig(url -> createGrpcChannel(url));
            return Client.builder(agentCard).withTransport(GrpcTransport.class, grpcConfig).build();
        }
        return Client.builder(agentCard)
                .withTransport(RestTransport.class, new RestTransportConfig(httpClient))
                .build();
    }

    private void awaitCompletion(
            String agentName,
            CountDownLatch done,
            List<ClientEvent> events,
            AtomicReference<ClientEvent> lastEventRef) {
        try {
            if (!done.await(sendTimeoutSeconds, TimeUnit.SECONDS)) {
                ClientEvent last = lastEventRef.get();
                log.error(
                        "[A2ARuntime] TIMEOUT for '{}' after {}s: received {} event(s), last event_class={}",
                        agentName,
                        sendTimeoutSeconds,
                        events.size(),
                        last != null ? last.getClass().getSimpleName() : "none");
                throw new RuntimeException("A2A message:send timed out for " + agentName);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("A2A message:send interrupted for " + agentName, e);
        }
    }

    @Override
    public Task getTask(AgentCard agentCard, String taskId, ClientCallContext callContext) {
        if (closed.get()) throw new IllegalStateException("A2A client runtime is closed");
        Client client = getOrCreateClient(agentCard, extractAgentUrl(agentCard));
        try {
            return client.getTask(new TaskQueryParams(taskId), callContext);
        } catch (A2AClientException e) {
            throw new RuntimeException("A2A getTask failed for " + agentCard.name() + ": " + e.getMessage(), e);
        }
    }

    @Override
    public Task cancelTask(AgentCard agentCard, String taskId, ClientCallContext callContext) {
        if (closed.get()) throw new IllegalStateException("A2A client runtime is closed");
        Client client = getOrCreateClient(agentCard, extractAgentUrl(agentCard));
        try {
            return client.cancelTask(CancelTaskParams.builder().id(taskId).build(), callContext);
        } catch (A2AClientException e) {
            throw new RuntimeException("A2A cancelTask failed for " + agentCard.name() + ": " + e.getMessage(), e);
        }
    }

    @Override
    public java.util.concurrent.CompletableFuture<SendMessageResult> subscribeToTask(
            AgentCard agentCard,
            String taskId,
            ClientCallContext callContext,
            java.util.function.Consumer<ClientEvent> eventSink) {
        if (closed.get()) {
            return java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("A2A client runtime is closed"));
        }
        java.util.concurrent.CompletableFuture<SendMessageResult> future = new java.util.concurrent.CompletableFuture<>();
        Client client = getOrCreateClient(agentCard, extractAgentUrl(agentCard));
        List<ClientEvent> events = Collections.synchronizedList(new ArrayList<>());
        try {
            client.subscribeToTask(
                    new TaskIdParams(taskId),
                    List.of((event, card) -> {
                        events.add(event);
                        if (eventSink != null) {
                            try { eventSink.accept(event); } catch (Exception ignored) {}
                        }
                        if (isTerminal(event) && !future.isDone()) {
                            List<ClientEvent> snapshot;
                            synchronized (events) {
                                snapshot = List.copyOf(events);
                            }
                            future.complete(SendMessageResult.builder()
                                    .text(A2ATransport.extractResponseText(snapshot))
                                    .task(A2ATransport.extractResponseTask(snapshot))
                                    .taskState(A2ATransport.extractResponseTaskState(snapshot))
                                    .metadata(A2ATransport.extractResponseMetadata(snapshot))
                                    .build());
                        }
                    }),
                    error -> future.completeExceptionally(
                            new RuntimeException(
                                    "A2A subscribeToTask stream failed for "
                                            + agentCard.name()
                                            + ": "
                                            + error.getMessage(),
                                    error)),
                    callContext);
        } catch (A2AClientException e) {
            return java.util.concurrent.CompletableFuture.failedFuture(
                    new RuntimeException("A2A subscribeToTask failed for " + agentCard.name() + ": " + e.getMessage(), e));
        }
        return future;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        clientCache.values().forEach(
                client -> {
                    try {
                        client.close();
                    } catch (RuntimeException e) {
                        log.warn("[A2ARuntime] Failed to close cached client: {}", e.getMessage());
                    }
                });
        clientCache.clear();
        streamClients.values().forEach(
                client -> {
                    try {
                        client.close();
                    } catch (RuntimeException e) {
                        log.warn(
                                "[A2ARuntime] Failed to close Notification-T client: {}",
                                e.getMessage());
                    }
                });
        streamClients.clear();
        httpClientExecutor.shutdownNow();
        log.info("[A2ARuntime] Closed cached clients and HTTP executor");
    }

    private record StreamClientKey(String agentName, String contextId) {}

    /**
     * Create a gRPC channel with SSL settings matching the engine config.
     *
     * <p>When sslVerify=false, uses plaintext (HTTP/2 without TLS). mTLS and CRL configuration
     * require TLS and therefore cannot be combined with that mode in the default gRPC runtime.
     */
    private io.grpc.Channel createGrpcChannel(String url) {
        if (!sslVerify) {
            if ((clientCertPath != null && !clientCertPath.isBlank())
                    || (clientKeyPath != null && !clientKeyPath.isBlank())) {
                throw new IllegalArgumentException(
                        "sslVerify=false uses plaintext gRPC and cannot carry mTLS configuration");
            }
            if (crlPath != null && !crlPath.isBlank()) {
                throw new IllegalArgumentException(
                        "sslVerify=false uses plaintext gRPC and cannot check a CRL");
            }
            return ManagedChannelBuilder.forTarget(url).usePlaintext().intercept(new WireGrpcInterceptor()).build();
        }
        if (crlPath != null && !crlPath.isBlank()) {
            throw new IllegalArgumentException(
                    "CRL configuration is not supported by the default gRPC transport");
        }
        try {
            io.grpc.TlsChannelCredentials.Builder credentials =
                    io.grpc.TlsChannelCredentials.newBuilder();
            if (caCertsPath != null && !caCertsPath.isBlank()) {
                credentials.trustManager(new File(caCertsPath));
            }
            boolean hasCert = clientCertPath != null && !clientCertPath.isBlank();
            boolean hasKey = clientKeyPath != null && !clientKeyPath.isBlank();
            if (hasCert != hasKey) {
                throw new IllegalArgumentException(
                        "Both client certificate and private key are required for gRPC mTLS");
            }
            if (hasCert) {
                if (clientKeyPassword == null || clientKeyPassword.isEmpty()) {
                    credentials.keyManager(new File(clientCertPath), new File(clientKeyPath));
                } else {
                    credentials.keyManager(
                            new File(clientCertPath),
                            new File(clientKeyPath),
                            clientKeyPassword);
                }
            }
            return io.grpc.Grpc.newChannelBuilder(url, credentials.build()).intercept(new WireGrpcInterceptor()).build();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Failed to configure gRPC TLS", e);
        }
    }

    private A2AHttpClient createHttpClient() {
        HttpClient httpClient =
                JdkHttpClientFactory.create(
                        sslVerify,
                        caCertsPath,
                        clientCertPath,
                        clientKeyPath,
                        clientKeyPassword,
                        crlPath,
                        Duration.ofSeconds(60),
                        httpClientExecutor);
        return new JdkA2AHttpClient(new ObservedHttpClient(httpClient));
    }

    private record ClientCacheKey(
            String agentName,
            String agentVersion,
            String endpoint,
            String protocolBinding,
            boolean sslVerify,
            String caCertsPath,
            String clientCertPath,
            String clientKeyPath,
            String crlPath) {}
}
