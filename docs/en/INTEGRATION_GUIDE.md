# A2A-T Workflow Execution Engine - Integration Guide

## 1. Overview

The A2A-T Workflow Execution Engine is a Java SDK for orchestrating multi-agent workflows using the A2A protocol with
A2A-T telecom extensions.

The engine schedules A2A tasks, envelopes final content, manages authentication/transport and waits for results. Host
callbacks own A2A-T generation, semantic validation, schemas and any LLM calls.

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

```java
interface ControlPoint {
    CompletableFuture<MessageContent> onTask(TaskRequest request);
    CompletableFuture<TaskResult> onSelfTask(TaskRequest request);
    CompletableFuture<RouteDecision> onRoute(RouteRequest request);
    CompletableFuture<NegotiationReply> onNegotiation(NegotiationRequest request);
}
```

onTask returns final parts/metadata/extensions; the engine sends them without generating or rewriting content.
onSelfTask returns local TaskResult, onRoute selects an allowed candidate, and onNegotiation returns Send or Stop.
Unimplemented callbacks fail explicitly. No echo-success, first-branch choice or automatic consent.
See [Business callback contract](BUSINESS_CALLBACKS.md) for fields and working examples.

```java
ControlPoint callbacks = ControlPoint.builder()
        .onTask(request -> CompletableFuture.completedFuture(
                MessageContent.text(request.getInstruction())))
        .onSelfTask(request -> CompletableFuture.completedFuture(
                TaskResult.success(List.of(Map.of(
                        "sourceResults", request.getWorkflowInput().upstreamResults())))))
        .onRoute(request -> CompletableFuture.failedFuture(
                new IllegalStateException("Supply a routing policy for " + request.stepName())))
        .onNegotiation(request -> CompletableFuture.completedFuture(
                new NegotiationReply.Stop("manual.required", "Manual confirmation required")))
        .build();
```

### 4.4 Execute

```java
ExecutionResult result = ExecutePsop.builder()
        .psop(workflow)
        .agentCards(List.of(card1, card2))
        .controlPoint(new MyControlPoint())
        .runtimeIntent("SPN cross-city fault diagnosis")
        .lang("zh")
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

The engine does not read A2A-T .env files or create LLM clients. If host callbacks use A2A-T, initialize
A2ATClient/A2ATServer with a host-owned environment file containing provider/model/key/base URL and A2AT_LANGUAGE. The
sample's a2atEnvPath setting is only a host/demo setting, not an engine builder option. Do not put OMC credential
decryption ownership in LLM configuration: pass the secret explicitly through WorkflowEngineClientConfig.builder ()
.credentialEncryptionKey (key) when using encrypted built-in credentials, then pass that configured engineClient to
ExecutePsop. Custom AuthProvider owns its own token/configuration. Tests use the current SDK SPI with an offline
provider, not template overrides or production fallbacks.

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
3. Re-encrypt all passwords:
   `java -cp workflow-engine.jar dev.openan.workflow.engine.client.CredentialCrypto "plaintext" new-key`
4. Update the `enc:...` results in the credentials JSON file

> The `.env` file should not be committed to version control. Add it to `.gitignore`.

### 5.3 Custom Authentication (AuthProvider)

When tokens are obtained by the workbench or an external identity service, or the mechanism is non-standard, implement
the `AuthProvider` interface. It has a single method:

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
- `securitySchemes` lists authentication methods the agent supports; `securityRequirements` marks the methods required
  by this integration. Empty `securityRequirements` disables built-in credential authentication, but `AuthProvider` is
  still called
- `AuthProvider` can be the sole authentication source even when `securityRequirements` is non-empty
- If both credentials and `AuthProvider` are configured, their headers are generated independently and merged; different
  values for the same header fail fast
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

Both `securitySchemes` and `securityRequirements` are optional. The former lists authentication methods the agent
supports; the latter marks methods required by this integration. `securityRequirements: []` disables built-in credential
authentication.

## 7. A2A-T Extensions

Only a remote `INPUT_REQUIRED` carrying valid Negotiation-T Propose enters `onNegotiation`. Terminal responses never
restart negotiation; ordinary `INPUT_REQUIRED` fails explicitly. The host validates/interprets the proposal and
generates the final Accept/Reject/Abort with its own A2A-T client. Use `A2atMessages.contextOf(request.received())` to
obtain the received context; reply with the same id, round and maxRounds. The last allowed round can still be answered.
Do not call nextRound for an ending reply or return a new Propose.

Return `new NegotiationReply.Send(content)` to send that exact content. Return `new NegotiationReply.Stop(code, reason)`
to stop locally without a generated Abort. Repeated task/session/round events do not repeat the callback or submission.
Unchanged waiting state is observed with getTask.
`maxNegotiationExchanges` (default 3) bounds local interactions, independently of the SDK context's maxRounds. Timeout,
exhausted budget or a missing handler fails locally; no implicit Accept or synthesized Abort. Accept/Reject ACKs in
SUBMITTED/WORKING remain pending and are observed without resending the command. A business-sent Abort is never
diagnosis success, even if the remote acknowledges it with COMPLETED.

```java
CompletableFuture<SendMessageResult> sendAuthorization(String agentName, MessageContent content);

NotificationSubscription openNotification(String agentName, MessageContent content,
                                          BiConsumer<NotificationSubscription, ReceivedMessage> listener);
```

The host generates final Authorization-T/Notification-T content and calls these methods on separate
transport/runtime/context instances. The listener receives handle and complete ReceivedMessage and closes on the
business recovery event. acknowledgement () and completion () separately represent ACK and actual stream exit, never
workflow prerequisites.

## 8. HTTPS Configuration

```java
// Controlled local diagnostics only: skip chain validation, but still verify the host name
.sslVerify(false)

// Production: enable verification + custom CA certs
.

sslVerify(true)
.

caCertsPath("/path/to/ca-certs.pem")

// Optional mTLS and CRL. Private keys support PKCS#8 PEM/DER; encrypted keys require a password
.

clientCertPath("/path/to/client-cert.pem")
.

clientKeyPath("/path/to/client-key.pem")
.

clientKeyPassword("change-me")
.

crlPath("/path/to/revocations.crl")
```

For HTTP/JSON-RPC, TLS policy is scoped to the current client and never changes JVM-wide hostname verification;
disabling chain verification still loads the mTLS client identity. Keep `sslVerify(true)` in production and trust
self-signed certificates through `caCertsPath`. The default gRPC runtime uses plaintext when `sslVerify(false)` is set,
so mTLS and `crlPath` cannot be combined with that mode and fail fast instead of being ignored.

## 9. Logging

The `PROTOCOL` logger at DEBUG records observations at the actual transport boundary. HTTP/JSON-RPC logs preserve the
serialized body and application headers after A2A SDK processing, including A2A-Version when actually present. gRPC
records actual metadata and a protobuf JSON view; that view is not an HTTP JSON body. The engine never adds a missing
header just to make logs look uniform. Automatic network headers, HTTP/2 frames, TLS records and server-side bytes are
not captured.

On dev, `ORDER_FORWARD_REQUEST` records the vendor SDK input and `ORDER_SDK_RESPONSE` records the status, multi-value
headers and text delivered by the SDK. `sdk-sse-text` assembles available SSE framing from SDK string chunks; original
byte encoding and the platform-to-OMC wire remain unobserved. It is not OMC packet-capture evidence. `MODEL_PREVIEW` is
optional, disabled by default, and never wire proof.

```properties
logger.protocol.name=PROTOCOL
logger.protocol.level=DEBUG
logger.protocol.additivity=true
WORKFLOW_ENGINE_PROTOCOL_INCLUDE_BODY=true
WORKFLOW_ENGINE_PROTOCOL_MAX_BODY_CHARS=100000
```

Body observation defaults to enabled when DEBUG is enabled; disable it explicitly for sensitive deployments. JVM
properties take precedence over same-named environment variables. Header credentials/cookies/tokens and recognized
secret body fields are always redacted; this cannot be disabled. This is field-based redaction, not a classifier for all
personal/business-sensitive content. Bodies are bounded (raw collectors use the configured numeric limit as bytes;
emitted text uses characters). Oversized SSE frames are dropped whole until the next delimiter and marked
`dropped-capacity`; disabled, truncated and interrupted observations are labeled. UTF-8 is decoded after assembling
chunks. Observers cannot fail delivery. File references are recorded as references and are never downloaded for logging.
requestId correlates each call; workflow calls additionally carry executionId/logicalTaskId/attempt,
agent/contextId/channel and remoteTaskId when known. These are local log fields, not wire metadata.

### Inspect negotiation in the local Demo

Running the local SpringSpnDemo without VM options now negotiates missing City1 input; City2 diagnoses directly. This is
a local sample scenario, not an engine default. To use complete inputs in both cities, add this IDEA **VM option**:

```text
-Da2at.samples.negotiation=false
```

By default only City1 loses its Task-T task object. Add `-Da2at.samples.negotiation.city=city2` or `both`
to exercise City2 or both cities. The host retains city-scoped authoritative input and the complaint context is
preserved. Expect DEMO_NEGOTIATION → INPUT_REQUIRED/PROPOSE → onNegotiation → ACCEPT → both diagnoses → one aggregate.
External-OMC mode defaults to no injection and rejects an explicit true switch. The Demo passes its setting into the
current Spring application context without modifying JVM-wide properties. Protocol observations are in the console and
`logs/spn-demo.log` relative to the run directory.

Run `SpringSpnDemoE2ETest` (direct) and, on dev, `SpringSpnDemoOrderE2ETest` sequentially. Each tests the no-VM-option
single-city default, explicit disable/enable and both-city negotiation with current SDK resources and an offline LLM
provider. This is local protocol E2E evidence, not real model/platform/OMC validation.

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

ExecutePsop.

builder()
    .

eventCallback(callback)
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

Construct final MessageContent (parts, metadata, extensions), with host-owned content generation/validation. No engine
handler or SDK instance registration. A2atMessages.from copies A2A-T metadata; other extensions can directly supply
metadata and activation URIs. AgentCard declarations never cause implicit generation.

## 14. Troubleshooting: Eastcom Order SDK 1.1.18 ByteBuf Leak

### Symptom

Order mode can log the following while the forwarded request itself continues:

```text
ResourceLeakDetector - LEAK: ByteBuf.release() was not called
Created at: WrapperHttpClient.lambda$null$9(WrapperHttpClient.java:164)
```

### Root Cause and Compatibility Fix

`order-shaded-client:1.1.18` allocates the request body from
`PooledByteBufAllocator.DEFAULT`. Its `ReactorNettyBridgeHandler.write(...)` copies that buffer into an RPC protobuf and
consumes the Netty write without releasing the source message. This is a vendor resource-ownership defect, not an A2A-T
parsing failure, and repeated requests can consume pooled direct memory.

`EastcomOrder118ByteBufWorkaround` installs a handler immediately before the consuming bridge in outbound traversal. It
delegates to the bridge first and then calls
`ReferenceCountUtil.safeRelease(msg)` in `finally`. The workaround neither replaces the vendor jar nor disables leak
detection. It deliberately fails if the expected 1.1.18 bridge is absent, so a vendor upgrade cannot silently cause a
double release. Once Eastcom publishes a fixed version, upgrade the dependency, remove the workaround, and repeat the
Order end-to-end and longevity tests with Netty leak detection set to `paranoid`.

## 15. Troubleshooting: Eastcom Order Mode Concurrent NE Timeout

> **Critical pitfall — read before modifying the Order simulator or gateway adapter.**

### Symptom

In Order mode, when sending Task-T diagnosis tasks to two different NEs concurrently (or while independent
Notification-T and Authorization-T channels target different NEs), one NE's request fails with
`Timeout on blocking read for 10000000000 NANOSECONDS` in `WrapperHttpClient.loadNeResource`.

### Root Cause 1: SDK Shared RSocket Connection

The Eastcom SDK's `ServiceReference` caches RSocket connections in a `static final Map INVOKERS` keyed by
`className + host + port`. All requests to the same platform address share one RSocket connection, regardless of the NE
name.

`WrapperHttpClient.loadNeResource()` calls `Mono.block(Duration.ofSeconds(10))` — a hardcoded 10-second timeout that
cannot be changed via configuration.

### Root Cause 2: Blocking I/O on RSocket Event Loop

The `EastcomOrderSimulatorServer.execute()` method (handling RSocket `requestChannel`) calls `forwardParsed()` which
uses `HttpURLConnection` with blocking `reader.read()`. When City1's Notification-T stream is long-lived (waiting for
OMC to push recovery events), `reader.read()` blocks indefinitely. If this runs on the RSocket event loop thread
(`reactor-tcp-nio-*`), the thread is occupied and cannot process City2's `loadNeResource` (RSocket `requestResponse`) on
the same connection — causing the 10-second timeout.

### Fix 1: Move Forwarding I/O Off the Event Loop Thread

Add `.subscribeOn(Schedulers.boundedElastic())` to the `forwardParsed` call so blocking I/O runs on worker threads,
freeing the RSocket event loop:

```java
forwardParsed(httpMethod, httpPath, parsedHeaders, body, forwardUrl, streaming)
        .

subscribeOn(Schedulers.boundedElastic())
        .

subscribe(sink::next, sink::error, () ->sink.

complete());
```

### Fix 2: Use HTTP Host Header Instead of Shared `lastResolvedTarget`

The original simulator used a `volatile String lastResolvedTarget` field set by `loadNeResource` and read by `execute`.
When two NEs call `loadNeResource` concurrently, the second overwrites the first's target. The SDK's
`configureHeaderHost()` sets the HTTP `Host` header to the full `neUrl` per request, so each request carries its own
target:

```java
String hostHeader = findHeader(parsedHeaders, "Host");
String targetBase = (hostHeader != null && hostHeader.startsWith("http"))
        ? withoutTrailingSlash(hostHeader)
        : (lastResolvedTarget != null ? lastResolvedTarget : defaultUrl);
```

### Why `readTimeout=65s` Alone Doesn't Help

The simulator's `connection.setReadTimeout(65_000)` only controls `HttpURLConnection`'s read timeout. It does not affect
the SDK's internal `Mono.block(10s)` in `loadNeResource`. The root cause is the RSocket event loop being blocked, not
HTTP read timeout.

### Diagnostic Checklist

1. Confirm timeout location: `WrapperHttpClient.loadNeResource` in stack trace = RSocket timeout, not HTTP
2. Confirm thread: `FORWARD_CHUNK`/`FORWARD_DONE` logs should show `boundedElastic-*`, not `reactor-tcp-nio-*`
3. Confirm target: `FORWARD_START target=` should match `LOAD_NE_ACCEPTED target=` for the same NE
4. Confirm session reuse: `SESSION_REUSE` in logs, not `SESSION_OPEN` every time
5. Confirm singleton: `ClientRuntimeFactory.create()` returns singleton in ORDER mode

## 13. Interface Reference

| Interface/Class                                        | Purpose                                                                 |
|--------------------------------------------------------|-------------------------------------------------------------------------|
| `ExecutePsop.Builder`                                  | Workflow execution entry point                                          |
| `ControlPoint` / `DefaultControlPoint`                 | Business decisions (onTask, onSelfTask, onRoute, onNegotiation, etc.)   |
| `WorkflowEngineClient` / `DefaultWorkflowEngineClient` | Workflow send (sendMessage, auth, extensions)                           |
| `ExtensionSender` / `DefaultExtensionSender`           | Independent Authorization-T operations and Notification-T subscriptions |
| `A2ATransport`                                         | Shared wire layer (A2A Java client runtime, auth, SSE consumer)         |
| `WorkflowEngineClientConfig`                           | Configuration (SSL, auth, A2A-T, negotiation rounds, custom handlers)   |
| `AuthProvider`                                         | Custom authentication                                                   |
| `EventCallback` / `EventType`                          | Event callback                                                          |
| `LoadPsop` / `RegistryClient`                          | Workflow loading / AgentCard fetching                                   |
| `Workflow` / `WorkflowStep` / `Task` / `JumpCondition` | Workflow definition                                                     |
| `ExecutionResult`                                      | Execution result                                                        |
| `SendMessageResult` / `TaskResult`                     | Message/task response                                                   |
