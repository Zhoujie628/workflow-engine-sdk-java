# A2A-T Workflow Execution Engine - Integration Guide

## 1. Overview

The A2A-T Workflow Execution Engine is a Java SDK for orchestrating multi-agent workflows using the A2A protocol with
A2A-T telecom extensions.

The engine handles all protocol mechanics automatically (message transport, SSE streaming, Task-T prompt generation,
Negotiation-T auto-loop, authentication, TLS). You focus on business decisions only.

## 2. Prerequisites

| Requirement | Version |
|-------------|---------|
| JDK         | 17+     |
| Maven       | 3.6+    |

## 3. Maven Dependency

```xml

<dependency>
    <groupId>dev.openan.workflow.sdk</groupId>
    <artifactId>workflow-engine</artifactId>
<version>1.0.0</version>
</dependency>
```

## 4. Quick Start

Four steps: define workflow -> load AgentCard -> implement ControlPoint -> execute.

### 4.1 Define a Workflow

```java
Workflow workflow = Workflow.builder()
        .name("Fault Diagnosis")
        .steps(List.of(
                WorkflowStep.builder()
                        .name("diagnose")
                        .subtasks(List.of(
                                Task.builder()
                                        .agent("SPN Domain Agent")
                                        .skill("diagnosis")
                                        .description("Diagnose fault")
                                        .build()))
                        .next(List.of(
                                JumpCondition.builder()
                                        .step("merge")
                                        .condition("success")
                                        .build()))
                        .layer(0)
                        .build(),
                WorkflowStep.builder()
                        .name("merge")
                        .stepType(StepType.SELF_LOOP)   // self-loop: workbench merges locally, no A2A-T to self
                        .subtasks(List.of(
                                Task.builder()
                                        .agent("Transport Workbench Agent")
                                        .skill("aggregate")
                                        .description("Merge results")
                                        .build()))
                        .next(List.of(
                                JumpCondition.builder()
                                        .step("end")
                                        .condition("success")
                                        .build()))
                        .layer(1)
                        .contextFrom(List.of("*"))
                        .build()
        ))
        .build();
```

### 4.2 Load AgentCards

```java
// Option A: From JSON files
ObjectMapper mapper = new ObjectMapper()
                .registerModule(new AgentCardJacksonModule());
AgentCard card = mapper.readValue(
        new File("agentcard/my_agent.json"), AgentCard.class);

// Option B: From Registry Center
RegistryClient registry = new RegistryClient("https://127.0.0.1:5000", false);
List<Map<String, Object>> cards = registry.fetchAgentCards();
```

### 4.3 Implement ControlPoint

Extend `DefaultControlPoint` and override the methods you need:

```java
public class MyControlPoint extends DefaultControlPoint {
    @Override
    public CompletableFuture<TaskResponse> onTask(
            TaskRequest request, TaskDispatcher dispatcher) {
        return dispatcher.dispatch(TaskSubmission.fromText(
                        request.getAgentName(), request.getMessage()))
                .thenApply(r -> {
                    String state = r.getTaskState();
                    boolean success = state == null || state.isBlank()
                            ? r.getText() != null && !r.getText().isBlank()
                            : state.endsWith("COMPLETED");
                    return TaskResponse.builder()
                            .success(success)
                            .output(r.getText())
                            .build();
                });
    }

    @Override
    public CompletableFuture<TaskResponse> onSelfTask(TaskRequest request) {
        // SELF_LOOP step: handled locally, no engineClient, no A2A-T message.
        // request.getMessage() already carries upstream step results as context.
        String summary = summarizeLocally(request.getMessage());
        return CompletableFuture.completedFuture(
                TaskResponse.builder().success(true).output(summary).build());
    }

    @Override
    public CompletableFuture<RouteDecision> onRoute(
            String stepName, Map<String, Object> results,
            List<JumpCondition> conditions) {
        return CompletableFuture.completedFuture(
                RouteDecision.builder()
                        .nextStep(conditions.get(0).getStep())
                        .build());
    }

    @Override
    public CompletableFuture<NegotiationDecision> onNegotiation(
            NegotiationRequest request) {
        return CompletableFuture.completedFuture(
                NegotiationDecision.acceptText(
                        "Please proceed with available information."));
    }
}
```

| Method            | When Called                               | What You Do                                      |
|-------------------|-------------------------------------------|--------------------------------------------------|
| `onTask`          | A step dispatches a task to another agent | Submit a typed `TaskSubmission` to `TaskDispatcher` |
| `onSelfTask`      | A `SELF_LOOP` step runs locally           | Handle locally, return result (no A2A-T message) |
| `onRoute`         | After step completes, before next step    | Pick the next step from candidates               |
| `onNegotiation`   | Agent returns `INPUT_REQUIRED`            | Return a typed `NegotiationDecision`              |

`onNegotiation` defaults to a generic `acceptText` decision. For structured business data use
`acceptData/rejectData/abortData`; never encode a decision in a string prefix.

**Independent operations (Authorization-T / Notification-T)**: Both use `ExtensionSender` at a workbench-selected business time and have no ordering dependency on workflow execution. Authorization-T is a one-shot request whose transport closes after a successful acknowledgement. Notification-T is a long-lived subscription on a transport independent of any single workflow; `openNotificationFromData` returns a `NotificationSubscription`, and later events are delivered to its callback until the expected result arrives, it is canceled, or it is explicitly closed.

**Self-loop steps (SelfLoop)**: When a step is the workflow-executing agent's own task (e.g. merging multiple agents'
diagnostic results), set `stepType` to `SELF_LOOP`. The engine calls `onSelfTask` locally instead of sending an A2A-T
message to the agent itself. `onSelfTask` takes no `engineClient` parameter — this enforces at the API level that
self-loop tasks never send A2A-T. Only steps targeting other agents go through `onTask` + A2A-T.

### 4.4 Execute

```java
ExecutionResult result = ExecutePsop.builder()
        .psop(workflow)
        .agentCards(List.of(card1, card2))
        .controlPoint(new MyControlPoint())
        .runtimeIntent("SPN cross-city fault diagnosis")
        .lang("zh")
        .a2atEnvPath(".env")
        .credentialsConfigPath("credentials.json")
        .sslVerify(true)
        .onFinish((r, history) -> {
            System.out.println("Result: " + r.isSuccess());
        })
        .execute()
        .get(10, TimeUnit.MINUTES);
```

Required: `psop`, `controlPoint`. All other config items have defaults.

## 5. Configuration

### 5.1 .env File

Configures the LLM and prompt runtime:

```ini
A2AT_LANGUAGE=zh-CN
A2AT_LLM_PROVIDER=openai
A2AT_LLM_MODEL=deepseek-v4-flash
A2AT_LLM_API_KEY=sk-xxxxxxxxxxxxxxxx
A2AT_LLM_BASE_URL=https://api.deepseek.com
A2AT_LLM_MAX_TOKENS=2000
A2AT_LLM_TEMPERATURE=0
A2AT_LLM_TIMEOUT_SECONDS=60
A2AT_CRED_KEY=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef
```

When `.env` is not configured, Task-T prompt generation is unavailable. All other features work normally.

### 5.2 Credentials File

For agents requiring authentication, provide a JSON credentials file:

```json
{
  "SPN Domain Agent": {
    "bearerAuth": {
      "login_url": "https://127.0.0.1:26335/rest/plat/smapp/v1/oauth/token",
      "method": "PUT",
      "request_fields": {
        "grantType": "password",
        "userName": "admin",
        "value": "enc:<base64-iv>:<base64-ciphertext>",
        "ipaddr": "*"
      },
      "token_field": "accessSession",
      "token_ttl": 3600
    }
  }
}
```

- Encrypted passwords use `enc:<iv>:<ciphertext>` format, key from `A2AT_CRED_KEY`
- Plaintext values (no `enc:` prefix) are also accepted
- Tokens are cached and refreshed automatically

### 5.2.1 Credential Encryption and Key Management

Passwords in the credentials file support encrypted storage to avoid plaintext exposure.

**Generate a key**

```bash
openssl rand -hex 32
```

Example output:

```
4f8a2b1c3d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a2b
```

Write the key to the `.env` file:

```
A2AT_CRED_KEY=4f8a2b1c3d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a2b
```

**Encrypt a password**

```bash
# Option 1: set env var first
set A2AT_CRED_KEY=4f8a2b1c3d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a2b
java -cp workflow-engine.jar dev.openan.workflow.engine.client.CredentialCrypto "Admin@123"

# Option 2: pass key as second argument
java -cp workflow-engine.jar dev.openan.workflow.engine.client.CredentialCrypto "Admin@123" 4f8a2b1c3d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a2b
```

Output:

```
enc:uHQcTeKZMVNRM9Ga:o5vm4weRozBXBs04phrLq7j7+/yRVyDsrw==
```

Paste the output into the `value` field of the credentials JSON.

**Rotating the key**

1. Generate a new key: `openssl rand -hex 32`
2. Update `A2AT_CRED_KEY` in `.env`
3. Re-encrypt all passwords: `java -cp workflow-engine.jar dev.openan.workflow.engine.client.CredentialCrypto "plaintext" new-key`
4. Update the `enc:...` results in the credentials JSON file

> The `.env` file should not be committed to version control. Add it to `.gitignore`.
### 5.3 Custom Authentication (AuthProvider)

When tokens are obtained by the workbench or an external identity service, or the mechanism is non-standard, implement the `AuthProvider` interface. It has a single method:

```java
public interface AuthProvider {
    void applyAuth(String agentName, AgentCard agentCard, Map<String, String> headers);
}
```

`applyAuth` is called before every message send. The implementation adds auth headers to the mutable `headers` map.

**Scenario 1: Enterprise SSO / External Token Service**

```java
public class SsoAuthProvider implements AuthProvider {
    private final SsoClient ssoClient;

    public SsoAuthProvider(SsoClient ssoClient) {
        this.ssoClient = ssoClient;
    }

    @Override
    public void applyAuth(String agentName, AgentCard agentCard, Map<String, String> headers) {
        String token = ssoClient.getToken(agentName);
        headers.put("Authorization", "Bearer " + token);
    }
}

// Register
WorkflowEngineClientConfig config = WorkflowEngineClientConfig.builder()
        .authProvider(new SsoAuthProvider(mySsoClient))
        .sslVerify(true)
        .a2atEnvPath(".env")
        .build();
```

**Scenario 2: AgentCard has no securitySchemes, but server requires auth**

```java
WorkflowEngineClientConfig config = WorkflowEngineClientConfig.builder()
        .authProvider((agentName, agentCard, headers) -> {
            headers.put("X-API-Key", "static-api-key-value");
        })
        .build();
```

**Scenario 3: Custom header name (non-standard Authorization)**

```java
WorkflowEngineClientConfig config = WorkflowEngineClientConfig.builder()
        .authProvider((agentName, agentCard, headers) -> {
            String token = refreshTokenIfNeeded(agentName);
            headers.put("X-Auth-Token", token);
            headers.put("X-Tenant-Id", "tenant-001");
        })
        .build();
```

**Notes:**

- `applyAuth` is called on every message send; implement token caching/refresh logic inside
- `securitySchemes` lists authentication methods the agent supports; `securityRequirements` marks the methods required by this integration. Empty `securityRequirements` disables built-in credential authentication, but `AuthProvider` is still called
- `AuthProvider` can be the sole authentication source even when `securityRequirements` is non-empty
- If both credentials and `AuthProvider` are configured, their headers are generated independently and merged; different values for the same header fail fast
- On auth failure (e.g. token retrieval throws), the exception propagates to `send()` and the request is blocked
## 6. AgentCard Definition

AgentCards declare extensions via `capabilities.extensions`:

```json
{
  "name": "SPN Domain Agent",
  "capabilities": {
    "streaming": true,
    "extensions": [
      {
        "uri": "https://projects.tmforum.org/a2aproject/telecommunication/extensions/Task-T/v1",
        "description": "Structured task prompt",
        "required": false
      },
      {
        "uri": "https://projects.tmforum.org/a2aproject/telecommunication/extensions/Negotiation-T/v1",
        "description": "Negotiation text exchange",
        "required": false
      },
      {
        "uri": "https://projects.tmforum.org/a2aproject/telecommunication/extensions/Authorization-T/v1",
        "description": "Authorization whitelist",
        "required": false
      },
      {
        "uri": "https://projects.tmforum.org/a2aproject/telecommunication/extensions/Notification-T/v1",
        "description": "Result notification subscription",
        "required": false
      }
    ]
  },
  "securitySchemes": {
    "bearerAuth": {
      "type": "http",
      "scheme": "bearer"
    }
  },
  "securityRequirements": [
    {
      "schemes": {
        "bearerAuth": []
      }
    }
  ],
  "supportedInterfaces": [
    {
      "protocolBinding": "HTTP+JSON",
      "protocolVersion": "1.0",
      "url": "https://127.0.0.1:26335/a2a/json"
    }
  ]
}
```

Extension URIs must match the A2A-T definitions exactly.

Both `securitySchemes` and `securityRequirements` are optional. The former lists authentication methods the agent supports; the latter marks methods required by this integration. `securityRequirements: []` disables built-in credential authentication.

## 7. A2A-T Extensions

The engine handles four A2A-T extensions automatically. You do not need to deal with protocol details:

### Task-T (automatic)

In `onTask`, select `TaskSubmission.fromText(...)` or `TaskSubmission.fromData(...)` and pass it to
`TaskDispatcher.dispatch(...)`. The engine calls the matching A2A-T SDK API and injects the generated metadata.

### Negotiation-T (automatic)

When an agent returns `INPUT_REQUIRED`, the engine creates a `NegotiationRequest`, calls your
`onNegotiation()` for a typed Accept/Reject/Abort decision, invokes the matching SDK fromText/fromData API,
and sends the rendered follow-up. Auto-loops up to `maxNegotiationRounds` (default 3).

### Authorization-T (independent authorization operation)

Invoke this when the workbench needs to manage a recovery whitelist; it is not a workflow node.
Authorization-T uses a dedicated transport/runtime/context:

```java
ExtensionSender authorizationSender = new DefaultExtensionSender(authorizationTransport);
authorizationSender.sendExtensionMessageFromData(
    "SPN Domain Agent",
    "Authorization-T structured operation",
    authorizationData,
    authorizationSchema,
    A2ATExtension.AUTHORIZATION_T).join();
```

`A2ATExtension.AUTHORIZATION_T` is used internally; never hardcode the URI. The SPN agent stores the strategy and
compares subsequent operations against the whitelist. Operations within the whitelist are executed; others are rejected.

### Notification-T (independent long-lived subscription)

Invoke this when the workbench needs recovery-result notifications. It uses a transport/runtime/context
separate from Task-T and Authorization-T:

```java
NotificationSubscription subscription = notificationSender.openNotificationFromData(
    "SPN Domain Agent",
    "Notification-T subscription",
    notificationData,
    notificationSchema,
    event -> {
        if ("recovery-result".equals(event.get("artifact_name"))) {
            persistRecoveryResult(event);
        }
    }).join();
SendMessageResult ack = subscription.acknowledgement().join();
```

The ACK only confirms that the subscription was established. Retain the `NotificationSubscription` and
call its idempotent `close()` after the expected `recovery-result`, cancellation, or workbench shutdown.
Normal workflow completion does not close this channel.

## 8. HTTPS Configuration

```java
// Controlled local diagnostics only: skip chain validation, but still verify the host name
.sslVerify(false)

// Production: enable verification + custom CA certs
.sslVerify(true)
.caCertsPath("/path/to/ca-certs.pem")

// Optional mTLS and CRL. Private keys support PKCS#8 PEM/DER; encrypted keys require a password
.clientCertPath("/path/to/client-cert.pem")
.clientKeyPath("/path/to/client-key.pem")
.clientKeyPassword("change-me")
.crlPath("/path/to/revocations.crl")
```

For HTTP/JSON-RPC, TLS policy is scoped to the current client and never changes JVM-wide hostname verification;
disabling chain verification still loads the mTLS client identity. Keep `sslVerify(true)` in production and trust
self-signed certificates through `caCertsPath`. The default gRPC runtime uses plaintext when `sslVerify(false)` is set,
so mTLS and `crlPath` cannot be combined with that mode and fail fast instead of being ignored.

## 9. Logging

The dedicated `PROTOCOL` logger emits protocol request/response summaries. Bodies are disabled by
default. Sensitive headers such as Authorization, cookies, API keys, tokens, and secrets are
redacted by default. Enable DEBUG and bodies only temporarily in a controlled diagnostic environment:

```properties
logger.protocol.name=PROTOCOL
logger.protocol.level=DEBUG
# Reuse the root console/file appenders; do not set false without a valid appenderRef
logger.protocol.additivity=true
```

Control the content with environment variables or same-named JVM system properties:

```properties
WORKFLOW_ENGINE_PROTOCOL_INCLUDE_BODY=true
WORKFLOW_ENGINE_PROTOCOL_MAX_BODY_CHARS=100000
WORKFLOW_ENGINE_PROTOCOL_INCLUDE_SENSITIVE_HEADERS=false
```

Enable sensitive headers only for isolated, controlled local diagnostics; the engine emits a security warning when this opt-in is active.

## 10. Event Callback

Subscribe to execution events for real-time monitoring:

```java
EventCallback callback = new EventCallback() {
    @Override
    public void onEvent(String eventType, Map<String, Object> data) {
        switch (eventType) {
            case EventType.STEP_START -> System.out.println("Step started: " + data.get("step"));
            case EventType.AGENT_STATUS_UPDATE -> System.out.println(
                    data.get("agent") + " state: " + data.get("state"));
            case EventType.NEGOTIATION_REQUEST -> System.out.println(
                    "Negotiation from " + data.get("agent"));
            case EventType.COMPLETE -> System.out.println("Workflow complete");
        }
    }
};

ExecutePsop.builder()
    .eventCallback(callback)
    // ...
```

Common event types: `STEP_START`, `STEP_COMPLETE`, `AGENT_REQUEST`,
`AGENT_RESPONSE`, `NEGOTIATION_REQUEST`, `NEGOTIATION_RESOLVED`,
`COMPLETE`, `ERROR`.

## 11. Load Workflows from Orchestration Center

```java
// Search by intent
List<WorkflowSearchResult> results = LoadPsop.search(
                "https://127.0.0.1:5001", "SPN cross-city fault diagnosis", 5, null, false);

// Load full workflow by ID
Workflow workflow = LoadPsop.load(
        "https://127.0.0.1:5001", results.get(0).getWorkflowId(), null, false);
```

## 12. Custom Extensions

To add a new A2A-T extension, implement `ExtensionHandler`:

```java
public class MyExtensionHandler implements ExtensionHandler {
    @Override
    public String extensionKeyword() {
        return "My-Extension";
    }

    @Override
    public CompletableFuture<Map<String, Object>> beforeSend(
            AgentCard agentCard, String messageText,
            Map<String, Object> metadata,
            A2ATClient a2atClient, ControlPoint controlPoint) {
        metadata.put("https://example.com/extensions/My-Extension/v1", "value");
        return CompletableFuture.completedFuture(metadata);
    }

    @Override
    public CompletableFuture<SendMessageResult> afterReceive(
            AgentCard agentCard, SendMessageResult result,
            A2ATClient a2atClient, ControlPoint controlPoint,
            EventCallback eventCallback) {
        return CompletableFuture.completedFuture(result);
    }
}
```

Register via config:

```java
WorkflowEngineClientConfig.builder()
    .customHandlers(List.of(new MyExtensionHandler()))
    .build();
```

## 14. Troubleshooting: Eastcom Order Mode Concurrent NE Timeout

> **Critical pitfall — read before modifying the Order simulator or gateway adapter.**

### Symptom

In Order mode, when sending Task-T diagnosis tasks to two different NEs concurrently (or while independent Notification-T and Authorization-T channels target different NEs), one NE's request fails with `Timeout on blocking read for 10000000000 NANOSECONDS` in `WrapperHttpClient.loadNeResource`.

### Root Cause 1: SDK Shared RSocket Connection

The Eastcom SDK's `ServiceReference` caches RSocket connections in a `static final Map INVOKERS` keyed by `className + host + port`. All requests to the same platform address share one RSocket connection, regardless of the NE name.

`WrapperHttpClient.loadNeResource()` calls `Mono.block(Duration.ofSeconds(10))` — a hardcoded 10-second timeout that cannot be changed via configuration.

### Root Cause 2: Blocking I/O on RSocket Event Loop

The `EastcomOrderSimulatorServer.execute()` method (handling RSocket `requestChannel`) calls `forwardParsed()` which uses `HttpURLConnection` with blocking `reader.read()`. When City1's Notification-T stream is long-lived (waiting for OMC to push recovery events), `reader.read()` blocks indefinitely. If this runs on the RSocket event loop thread (`reactor-tcp-nio-*`), the thread is occupied and cannot process City2's `loadNeResource` (RSocket `requestResponse`) on the same connection — causing the 10-second timeout.

### Fix 1: Move Forwarding I/O Off the Event Loop Thread

Add `.subscribeOn(Schedulers.boundedElastic())` to the `forwardParsed` call so blocking I/O runs on worker threads, freeing the RSocket event loop:

```java
forwardParsed(httpMethod, httpPath, parsedHeaders, body, forwardUrl, streaming)
        .subscribeOn(Schedulers.boundedElastic())
        .subscribe(sink::next, sink::error, () -> sink.complete());
```

### Fix 2: Use HTTP Host Header Instead of Shared `lastResolvedTarget`

The original simulator used a `volatile String lastResolvedTarget` field set by `loadNeResource` and read by `execute`. When two NEs call `loadNeResource` concurrently, the second overwrites the first's target. The SDK's `configureHeaderHost()` sets the HTTP `Host` header to the full `neUrl` per request, so each request carries its own target:

```java
String hostHeader = findHeader(parsedHeaders, "Host");
String targetBase = (hostHeader != null && hostHeader.startsWith("http"))
        ? withoutTrailingSlash(hostHeader)
        : (lastResolvedTarget != null ? lastResolvedTarget : defaultUrl);
```

### Why `readTimeout=65s` Alone Doesn't Help

The simulator's `connection.setReadTimeout(65_000)` only controls `HttpURLConnection`'s read timeout. It does not affect the SDK's internal `Mono.block(10s)` in `loadNeResource`. The root cause is the RSocket event loop being blocked, not HTTP read timeout.

### Diagnostic Checklist

1. Confirm timeout location: `WrapperHttpClient.loadNeResource` in stack trace = RSocket timeout, not HTTP
2. Confirm thread: `FORWARD_CHUNK`/`FORWARD_DONE` logs should show `boundedElastic-*`, not `reactor-tcp-nio-*`
3. Confirm target: `FORWARD_START target=` should match `LOAD_NE_ACCEPTED target=` for the same NE
4. Confirm session reuse: `SESSION_REUSE` in logs, not `SESSION_OPEN` every time
5. Confirm singleton: `ClientRuntimeFactory.create()` returns singleton in ORDER mode

## 13. Interface Reference

| Interface/Class                                        | Purpose                                                               |
|--------------------------------------------------------|-----------------------------------------------------------------------|
| `ExecutePsop.Builder`                                  | Workflow execution entry point                                        |
| `ControlPoint` / `DefaultControlPoint`                 | Business decisions (onTask, onSelfTask, onRoute, onNegotiation, etc.) |
| `WorkflowEngineClient` / `DefaultWorkflowEngineClient` | Workflow send (sendMessage, auth, extensions)                         |
| `ExtensionSender` / `DefaultExtensionSender`           | Independent Authorization-T operations and Notification-T subscriptions |
| `A2ATransport`                                         | Shared wire layer (httpx runtime, auth, SSE consumer)                 |
| `WorkflowEngineClientConfig`                           | Configuration (SSL, auth, A2A-T, negotiation rounds, custom handlers) |
| `AuthProvider`                                         | Custom authentication                                                 |
| `ExtensionHandler`                                     | Custom extension handler                                              |
| `EventCallback` / `EventType`                          | Event callback                                                        |
| `LoadPsop` / `RegistryClient`                          | Workflow loading / AgentCard fetching                                 |
| `Workflow` / `WorkflowStep` / `Task` / `JumpCondition` | Workflow definition                                                   |
| `ExecutionResult`                                      | Execution result                                                      |
| `SendMessageResult` / `TaskResponse`                   | Message/task response                                                 |
