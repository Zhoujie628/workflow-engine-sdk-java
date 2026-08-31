# A2A-T Workflow Execution Engine - API Reference

## Package Overview

| Package                               | Description                                     |
|---------------------------------------|-------------------------------------------------|
| `dev.openan.workflow.engine.client`   | A2A message transport, auth, extensions, config |
| `dev.openan.workflow.engine.control`  | User decision points and event system           |
| `dev.openan.workflow.engine.core`     | DAG traversal engine and context assembly       |
| `dev.openan.workflow.engine.model`    | Data models (Workflow, Task, results)           |
| `dev.openan.workflow.engine.registry` | PSOP loading and AgentCard registry             |
| `dev.openan.workflow.engine.runner`   | Entry point for workflow execution              |

---

## dev.openan.workflow.engine.runner

### ExecutePsop

Entry point for executing a PSOP workflow. Uses the Builder pattern.

#### ExecutePsop.Builder

| Method                                   | Type     | Default     | Description                                  |
|------------------------------------------|----------|-------------|----------------------------------------------|
| `psop(Workflow)`                         | required | -           | PSOP workflow definition                     |
| `agentCards(List<AgentCard>)`            | required | `List.of()` | Agent cards for all agents in the workflow   |
| `controlPoint(ControlPoint)`             | required | -           | User decision implementation                 |
| `engineClient(WorkflowEngineClient)`     | optional | null        | Pre-configured client (null = auto-create)   |
| `runtimeIntent(String)`                  | optional | `""`        | Natural-language intent for context assembly |
| `lang(String)`                           | optional | `"zh"`      | Language hint (`"zh"` or `"en"`)             |
| `credentialsConfigPath(String)`          | optional | null        | Path to credentials JSON file                |
| `sslVerify(boolean)`                     | optional | `true`      | Whether to verify TLS certificates           |
| `caCertsPath(String)`                    | optional | null        | Path to CA certificates PEM file             |
| `a2aClientRuntime(A2AJavaClientRuntime)` | optional | null        | Custom runtime (null = auto-create)          |
| `eventCallback(EventCallback)`           | optional | null        | Real-time event callback                     |
| `onFinish(BiConsumer)`                   | optional | null        | Called when execution completes              |
| `onEvent(Function)`                      | optional | null        | Per-event transformation hook                |

```java
ExecutionResult result = ExecutePsop.builder()
        .psop(workflow)
        .agentCards(cards)
        .controlPoint(cp)
        .runtimeIntent("diagnose fault")
        .sslVerify(false)
        .execute()
        .get(10, TimeUnit.MINUTES);
```

**Returns:** `CompletableFuture<ExecutionResult>`

---

## dev.openan.workflow.engine.client

### WorkflowEngineClient

```java
CompletableFuture<SendMessageResult> dispatch(TaskRequest request, MessageContent content, ControlPoint callbacks);

CompletableFuture<SendMessageResult> sendMessage(String agentName, MessageContent content);

CompletableFuture<SendMessageResult> getTask(String agentName, String taskId);

CompletableFuture<SendMessageResult> cancelTask(String agentName, String taskId);

CompletableFuture<SendMessageResult> subscribeToTask(String agentName, String taskId, Consumer<Map<String, Object>> callback);

long callbackTimeoutSeconds();

void setControlPoint(ControlPoint callbacks);

void setEventCallback(EventCallback callback);

void close();
```

The executor calls dispatch; onTask does not send. Content is final parts/metadata/extensions. The engine manages
envelopes and interactions, never instantiates A2ATClient or generates content from AgentCard declarations. Template
queries and generation belong to the host SDK.

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

### ExtensionSender

```java
CompletableFuture<SendMessageResult> sendAuthorization(String agentName, MessageContent content);

NotificationSubscription openNotification(String agentName, MessageContent content,
                                          BiConsumer<NotificationSubscription, ReceivedMessage> listener);
```

Authorization and notification accept host-generated final content. Use separate transport/runtime/context instances;
their outcomes do not gate the workflow. openNotification registers a handle before I/O, and passes it plus
ReceivedMessage directly to the listener. acknowledgement () is the real ACK; timeout fails. close () requests closure;
completion () observes actual stream termination.

### WorkflowEngineClientConfig

Builder-based configuration for the workflow engine client.

| Property                        | Type           | Default | Description                                                                                                                                             |
|---------------------------------|----------------|---------|---------------------------------------------------------------------------------------------------------------------------------------------------------|
| `sslVerify`                     | `boolean`      | `true`  | TLS chain verification for HTTP/JSON-RPC; disabling it keeps hostname checks and the mTLS client identity. Disabling it uses plaintext for default gRPC |
| `caCertsPath`                   | `String`       | null    | Path to CA certs PEM file                                                                                                                               |
| `clientCertPath`                | `String`       | null    | Path to the mTLS client certificate chain; default gRPC requires `sslVerify=true`                                                                       |
| `clientKeyPath`                 | `String`       | null    | Path to a PKCS#8 PEM/DER mTLS private key                                                                                                               |
| `clientKeyPassword`             | `String`       | null    | Password for an encrypted PKCS#8 private key                                                                                                            |
| `crlPath`                       | `String`       | null    | X.509 CRL for HTTP/JSON-RPC; the default gRPC runtime rejects this unsupported combination                                                              |
| `sendTimeoutSeconds`            | `long`         | `600`   | SSE stream timeout (10 min default)                                                                                                                     |
| `notificationAckTimeoutSeconds` | `long`         | `5`     | Wait for the first Notification-T ACK/event                                                                                                             |
| `sendExecutorCoreSize`          | `int`          | `4`     | Send executor core threads                                                                                                                              |
| `sendExecutorMaxSize`           | `int`          | `16`    | Send executor maximum threads                                                                                                                           |
| `sendExecutorQueueCapacity`     | `int`          | `256`   | Bounded send executor queue capacity                                                                                                                    |
| `authProvider`                  | `AuthProvider` | null    | Custom auth provider                                                                                                                                    |
| `credentialsConfigPath`         | `String`       | null    | Path to credentials JSON; explicit missing or malformed files fail startup                                                                              |
| `credentialEncryptionKey`       | `String`       | null    | Host-supplied decryption key; never loaded from LLM .env                                                                                                |
| `credentialsConfig`             | `Map`          | null    | Inline credentials config; must match AgentCard security requirements                                                                                   |
| `maxNegotiationExchanges`       | `int`          | `3`     | Local interaction budget, independent of SDK maxRounds                                                                                                  |

```java
WorkflowEngineClientConfig config = WorkflowEngineClientConfig.builder()
        .sslVerify(true)
        .caCertsPath("ca.pem")
        .clientCertPath("client-cert.pem")
        .clientKeyPath("client-key.pem")
        .sendTimeoutSeconds(900)
        .credentialsConfigPath("creds.json")
        .maxNegotiationExchanges(5)
        .authProvider(myProvider)
        .build();
```

### AuthProvider

Custom authentication provider for non-standard auth mechanisms.

```java
public interface AuthProvider {
    void applyAuth(String agentName, AgentCard agentCard,
                   Map<String, String> headers);
}
```

Called for every message send. The `headers` map is mutable; add `Authorization`, custom headers, etc. `AuthProvider`
can be the sole authentication source, including when `securityRequirements` is non-empty and credentials are not
configured. When credentials are also configured, both sets are computed independently and merged; different values for
the same header name throw `SecurityException` instead of silently overwriting either value.

### A2atMessages

Submit custom extensions via MessageContent (parts, metadata, extensions); no engine content-handler registration.
A2atMessages.from (MetadataContent, List<Part<?>>) preserves SDK metadata and activates the corresponding URI; contextOf
(ReceivedMessage) or contextOf (Map<String,Object>) reads/checks canonical negotiation context. This adapter uses
a2a-t-core only, never content generation or semantic validation.

### A2AJavaClientRuntime

Runtime seam for A2A SDK message transport. Implement to customize HTTP transport behavior.

```java
public interface A2AJavaClientRuntime {
    Iterable<ClientEvent> sendMessage(
            AgentCard agentCard, MessageSendParams params,
            ClientCallContext callContext,
            Consumer<ClientEvent> eventSink,
            Consumer<String> logSink);

    void close();
}
```

A default implementation is provided. Implement this interface only if you need custom HTTP transport.

### ConversationScopedA2AJavaClientRuntime

Optional lifecycle callback for runtime implementations whose transport session spans multiple A2A requests (e.g. a
gateway login that must stay alive across all negotiation rounds).

```java
public interface ConversationScopedA2AJavaClientRuntime {
    void closeConversation(AgentCard agentCard, String contextId);
}
```

When the runtime also implements this interface, the engine calls `closeConversation` after the full send + negotiation
cycle completes — not after each individual HTTP request. This allows gateway sessions to be released only after the
logical conversation is done.

Implement alongside `A2AJavaClientRuntime`:

```java
public class MyGatewayRuntime
        implements A2AJavaClientRuntime, ConversationScopedA2AJavaClientRuntime {
    @Override
    public Iterable<ClientEvent> sendMessage(...) { ...}

    @Override
    public void closeConversation(AgentCard agentCard, String contextId) {
        // release gateway session
    }

    @Override
    public void close() { ...}
}
```

### AgentCardJacksonModule

Jackson module for deserializing AgentCard JSON with security scheme normalization. Handles the OpenAPI-format
`securitySchemes` / `securityRequirements` fields that the A2A SDK's strongly-typed `AgentCard` record expects.

```java
ObjectMapper mapper = new ObjectMapper()
        .registerModule(new AgentCardJacksonModule());
AgentCard card = mapper.readValue(
        new File("agentcard/my_agent.json"), AgentCard.class);
```

### AgentCardNormalizer

Utility that normalizes a raw `Map<String, Object>` (as returned by the Registry Center API) into the `AgentCard`
-compatible format. Used internally by `RegistryClient.fetchAgentCards()`. Also available as a public static method for
custom normalization:

```java
Map<String, Object> normalized = AgentCardNormalizer.normalize(rawMap);
```

---

## dev.openan.workflow.engine.control

### ControlPoint

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

### EventCallback

```java
public class EventCallback {
    public void onEvent(String eventType, Map<String, Object> data) {
    }
}
```

Override to receive real-time execution events. Event types are defined in `EventType` constants.

### EventType

| Constant                 | Description                                                  |
|--------------------------|--------------------------------------------------------------|
| `STEP_START`             | A workflow step began                                        |
| `STEP_COMPLETE`          | A workflow step completed                                    |
| `TASK_REQUEST`           | A task was dispatched to an agent                            |
| `TASK_RESPONSE`          | A task response was received                                 |
| `TASK_STATUS_CHANGED`    | A task's status changed (pending → running → success/failed) |
| `AGENT_REQUEST`          | A message was sent to an agent                               |
| `AGENT_RESPONSE`         | A response was received from an agent                        |
| `AGENT_STATUS_UPDATE`    | Agent SSE status update (SUBMITTED, WORKING, etc.)           |
| `AGENT_ARTIFACT_UPDATE`  | Agent SSE artifact update                                    |
| `AGENT_MESSAGE_EVENT`    | Agent SSE message event                                      |
| `NEGOTIATION_REQUEST`    | Agent requested negotiation (INPUT_REQUIRED)                 |
| `NEGOTIATION_RESOLVED`   | Clarification was sent to agent                              |
| `NEGOTIATION_FAILED`     | Negotiation could not be resolved                            |
| `AUTHORIZATION_REQUEST`  | Agent requested authorization                                |
| `AUTHORIZATION_RESOLVED` | Authorization decision was made                              |
| `NOTIFICATION`           | Notification received from agent                             |
| `ROUTE_DECISION`         | Route decision was made                                      |
| `WORKFLOW_COMPLETE`      | The workflow completed (all steps finished)                  |
| `START`                  | Workflow execution started                                   |
| `COMPLETE`               | Workflow execution completed successfully                    |
| `ERROR`                  | Workflow execution failed                                    |
| `CLOSE`                  | Engine client closed                                         |

---

## dev.openan.workflow.engine.registry

### LoadPsop

Load and search PSOP workflows from the orchestration center.

#### load

```java
static Workflow load(String baseUrl, String psopId,
                     String accessToken, boolean sslVerify)

static Workflow load(String baseUrl, String psopId)
```

GET `/api/v1/orchestrate/psop/{psop_id}`. Returns the full workflow with steps, subtasks, and routing conditions.

#### search

```java
static List<WorkflowSearchResult> search(
        String baseUrl, String intent, int topN,
        String accessToken, boolean sslVerify)

static List<WorkflowSearchResult> search(
        String baseUrl, String intent)
```

POST `/api/v1/orchestrate/search`. Returns ranked workflow summaries matched by natural-language intent.

### RegistryClient

The two-argument constructor uses a 30-second complete-response deadline. Override it with
`new RegistryClient(url, sslVerify, Duration.ofSeconds(15))`; the duration must be positive.
The deadline includes response-body consumption, and interruption cancels the pending request.
Registry methods return JSON maps; convert them to AgentCard with AgentCardJacksonModule as shown in the integration guide.


Fetch and register AgentCards from the Registry Center.

```java
new RegistryClient("https://127.0.0.1:5000",false)

List<Map<String, Object>> fetchAgentCards()

Map<String, Object> fetchAgentCard(String name)

Map<String, Object> fetchAgentCard(String name, String organization)

Map<String, Object> registerAgentCard(Map<String, Object> agentCard)
```

- `fetchAgentCards`: GET all cards from registry
- `fetchAgentCard`: GET a specific card by name (and optionally organization)
- `registerAgentCard`: POST a card to the registry

---

## dev.openan.workflow.engine.core

### WorkflowExecutor

Mid-layer DAG traversal engine. Walks workflow steps, selects typed upstream execution results through `ContextBuilder`,
dispatches subtasks in parallel, applies step success policies (`ALL_SUCCESS` / `ANY_SUCCESS` / `SELF_LOOP`), and routes
to the next step.

Not typically instantiated directly by SDK users — `ExecutePsop` wraps it internally. Exposed for advanced integrations
that need to run the traversal layer without the runner's lifecycle management.

```java
WorkflowExecutor executor = new WorkflowExecutor(
        workflow, controlPoint, engineClient,
        eventCallback, runtimeIntent, lang);
ExecutionResult result = executor.run().join();
```

### ContextBuilder

Package-private helper that selects upstream step results and creates a `WorkflowInput` according to `contextFrom`
(omitted = direct predecessors, `[]` = no upstream input, `"*"` = all ancestors, explicit names = selective
inheritance). It does not render prompts and is not part of the public API surface.

---

## dev.openan.workflow.engine.model

### Workflow

| Field         | Type                 | Description            |
|---------------|----------------------|------------------------|
| `id`          | `String`             | Workflow ID            |
| `name`        | `String`             | Workflow name          |
| `description` | `String`             | Description            |
| `steps`       | `List<WorkflowStep>` | Ordered workflow steps |

Static factory: `Workflow.fromMap(Map<String, Object>)` parses from orchestration center API response.

### WorkflowStep

| Field         | Type                  | Default       | Description                                                                                                       |
|---------------|-----------------------|---------------|-------------------------------------------------------------------------------------------------------------------|
| `name`        | `String`              | -             | Step name (unique within workflow)                                                                                |
| `subtasks`    | `List<Task>`          | `List.of()`   | Subtasks dispatched in this step                                                                                  |
| `next`        | `List<JumpCondition>` | `List.of()`   | Conditional next steps                                                                                            |
| `layer`       | `int`                 | `0`           | Design-time hint; dependency graph selects context                                                                |
| `contextFrom` | `List<String>`        | null          | Aggregation source: omitted = direct predecessors, `[]` = none, `"*"` = all ancestors, or explicit ancestor names |
| `stepType`    | `StepType`            | `ALL_SUCCESS` | Execution mode                                                                                                    |

### StepType

| Value         | Description                                                                                                            |
|---------------|------------------------------------------------------------------------------------------------------------------------|
| `ALL_SUCCESS` | All subtasks must succeed                                                                                              |
| `ANY_SUCCESS` | Any subtask success is sufficient                                                                                      |
| `SELF_LOOP`   | The workflow agent handles the task locally via `onSelfTask`; no A2A-T message is sent. Success follows `ALL_SUCCESS`. |

### TaskStatus

Task lifecycle status, used in `TASK_STATUS_CHANGED` events for cross-SDK consistency with the Python SDK.

| Value     | String      | Description                   |
|-----------|-------------|-------------------------------|
| `PENDING` | `"pending"` | Task created, not yet started |
| `RUNNING` | `"running"` | Task in progress              |
| `SUCCESS` | `"success"` | Task completed successfully   |
| `FAILED`  | `"failed"`  | Task failed                   |

### Task

| Field         | Type     | Description                         |
|---------------|----------|-------------------------------------|
| `agent`       | `String` | Agent name (matches AgentCard.name) |
| `skill`       | `String` | Agent skill ID                      |
| `description` | `String` | Task description                    |

### JumpCondition

| Field       | Type     | Description                                        |
|-------------|----------|----------------------------------------------------|
| `step`      | `String` | Next step name (`"end"` for terminal)              |
| `condition` | `String` | Condition expression (`"success"`, `"fail"`, etc.) |

### TaskRequest / BusinessInput

TaskRequest uses getXxx () accessors:

| Field                        | Meaning                                                                           |
|------------------------------|-----------------------------------------------------------------------------------|
| executionId / taskId         | Local execution/logical task identities, not remote protocol IDs                  |
| stepName / agentName / skill | Current workflow step, destination and skill                                      |
| instruction / language       | Current instruction only; no appended history                                     |
| input                        | BusinessInput: exactly one of text or arbitrary JSON-serializable data; no schema |
| workflowInput                | WorkflowInput(runtimeIntent, upstreamResults), separate from current input        |

BusinessInput.text (value) / BusinessInput.data (value) create input snapshots. WorkflowInput, UpstreamStepResult,
ReceivedMessage and NegotiationRequest use record field () accessors.

| contextFrom             | Upstream selection                           |
|-------------------------|----------------------------------------------|
| absent / null           | Available direct predecessors                |
| []                      | No upstream results; runtimeIntent remains   |
| ["*"]                   | Available ancestors                          |
| explicit ancestor names | Available named results in declaration order |

contextFrom selects evidence, not scheduling edges; define dependencies with next. Unknown/non-ancestor names or mixing
"*" with names is invalid. Unselected/inactive branches are not fabricated. The engine never appends upstream results to
instruction/parts or invokes an LLM to map them. The host decides how to consume the window or map it to the next
agent's input.

The window is stepName → taskResults[] → outputs[] / receivedMessages[]. TaskExecutionResult also retains agentName,
skill, logical taskId, taskDescription, status, error, errorCode and errorDetails. Multiple subtasks remain
distinguishable. Nested arrays remain one output value; there is no requirement that outputs originated from an LLM.

### MessageContent / ReceivedMessage

```java
record MessageContent(List<Part<?>> parts, Map<String, Object> metadata, Set<String> extensions) {
}

record ReceivedMessage(MessageContent message, Map<String, Object> taskMetadata, List<Artifact> artifacts) {
}
```

MessageContent.text (text), MessageContent.parts (parts), or the canonical constructor create snapshots. TextPart,
DataPart and FilePart preserve order and part metadata. File references are not fetched. MessageContent has no role,
target, messageId, taskId, contextId or auth headers. A business metadata key named contextId does not override the A2A
envelope.

ReceivedMessage preserves message metadata, task metadata and each artifact's identity/parts/metadata separately.
message can be null; metadata-only results remain accessible through the complete view. For convenience, outputs
projects TextPart values and DataPart data from artifacts in order; if there are no artifacts, a successful
final/standalone message can supply these values. FilePart remains available only in the complete view. No text parsing,
adjacent-part concatenation or nested-array flattening. Failed task status messages are evidence, not outputs; valid
partial artifacts are retained. Streaming append/replace is assembled per artifact; terminal snapshots do not duplicate
earlier chunks.

TaskResult.success (List<Object>) represents local output, including an empty list. TaskResult.failure (code, message)
separates failure from output; its builder can retain valid partial outputs. TaskResult includes receivedMessages for
remote evidence; remote convenience outputs derive from that view. A remote task succeeds only in COMPLETED, or a
standalone A2A Message can complete the interaction. Progress and negotiation are never a successful workflow task
merely because they have text.

### NegotiationRequest / NegotiationReply

See [BUSINESS_CALLBACKS](BUSINESS_CALLBACKS.md).

### SendMessageResult

getReceivedMessages () preserves response levels; getOutputs () is a convenience projection. getTask ()/getTaskState ()
retain actual remote state; failureCode/failureMessage describe independent local interaction failure. text and
flattened metadata are transport diagnostics, not substitutes for the complete business response.

### ExecutionResult

| Field         | Type               | Description                |
|---------------|--------------------|----------------------------|
| `success`     | `boolean`          | Whether workflow succeeded |
| `history`     | `List<Map>`        | Per-step execution history |
| `stepOutputs` | `Map<String, Map>` | Outputs keyed by step name |
| `error`       | `String`           | Error message (if failed)  |

### RouteDecision

| Field      | Type     | Description          |
|------------|----------|----------------------|
| `nextStep` | `String` | Next step to execute |
| `reason`   | `String` | Decision reason      |

### WorkflowSearchResult

| Field            | Type           | Description        |
|------------------|----------------|--------------------|
| `workflowId`     | `String`       | Workflow ID        |
| `workflowType`   | `String`       | Type               |
| `name`           | `String`       | Name               |
| `description`    | `String`       | Description        |
| `tags`           | `List<String>` | Tags               |
| `createdAt`      | `String`       | Creation timestamp |
| `score`          | `double`       | Relevance score    |
| `userIntent`     | `String`       | Matched intent     |
| `relatedPreflow` | `String`       | Related preflow    |
| `tasksSummary`   | `String`       | Task summary       |

---

## Extension URI Constants

| Extension       | URI                                                                                       |
|-----------------|-------------------------------------------------------------------------------------------|
| Task-T          | `https://projects.tmforum.org/a2aproject/telecommunication/extensions/Task-T/v1`          |
| Negotiation-T   | `https://projects.tmforum.org/a2aproject/telecommunication/extensions/Negotiation-T/v1`   |
| Authorization-T | `https://projects.tmforum.org/a2aproject/telecommunication/extensions/Authorization-T/v1` |
| Notification-T  | `https://projects.tmforum.org/a2aproject/telecommunication/extensions/Notification-T/v1`  |

Canonical negotiation metadata is `templateUri` plus
`negotiationContext={id,round,maxRounds,performative}`. Legacy state-machine keys are not accepted.

---

## Thread Safety

- The engine client is thread-safe. Concurrent collections are used internally.
- `ControlPoint` implementations must be thread-safe if used from multiple workflow executions concurrently.
- `EventCallback.onEvent` is called from multiple threads (main + SSE worker threads). Use synchronization if needed.

## Error Handling

- Missing/null/exceptional/timed-out callbacks fail explicitly. Host SDK errors may be mapped to BusinessFailure with
  safe code/details.
- Exhausted maxNegotiationExchanges or local Stop does not generate Abort. Business Send (Abort) is never diagnosis
  success.
- Remote state/errors stay separate from outputs; failed status text is evidence, while partial artifacts remain
  available.
- Missing required credentials reject the request; the engine never silently sends unauthenticated.
- Stream-exit/transport-error observations do not independently define workflow outcome; inspect ExecutionResult and
  remote task state.

---

## spring-boot-starter Module

The `spring-boot-starter` module provides Spring Boot auto-configuration for the A2A **server** side (not the
client/workflow side). When on the classpath of a Spring Boot web application, it auto-registers all A2A SDK server
components as Spring beans.

### A2AProperties

Configuration properties prefixed with `a2at.server`:

| Property                                     | Default                    | Description                                                            |
|----------------------------------------------|----------------------------|------------------------------------------------------------------------|
| `a2at.server.agent-card`                     | `classpath:agentcard.json` | Path to the AgentCard JSON file (classpath: or file: prefix supported) |
| `a2at.server.path-prefix`                    | `/a2a/json`                | URL path prefix for A2A endpoints                                      |
| `a2at.server.agent-timeout-seconds`          | `30`                       | Agent execution timeout in seconds                                     |
| `a2at.server.consumption-timeout-seconds`    | `5`                        | Consumption timeout in seconds                                         |
| `a2at.server.reconciliation-timeout-seconds` | `1`                        | Reconciliation wait timeout in seconds                                 |
| `a2at.server.executor-core-size`             | `8`                        | Server executor core threads                                           |
| `a2at.server.executor-max-size`              | `8`                        | Server executor maximum threads                                        |
| `a2at.server.executor-queue-capacity`        | `100`                      | Bounded server executor queue capacity                                 |
| `a2at.server.executor-keep-alive-seconds`    | `60`                       | Non-core thread keep-alive in seconds                                  |

```yaml
a2at:
  server:
    agent-card: classpath:agentcard/my_agent.json
    path-prefix: /a2a/json
    agent-timeout-seconds: 30
    executor-core-size: 8
    executor-max-size: 16
    executor-queue-capacity: 200
```

### A2AAutoConfiguration

Auto-configures the following beans (all `@ConditionalOnMissingBean`, so you can override any):

| Bean                | Type                          | Purpose                                                   |
|---------------------|-------------------------------|-----------------------------------------------------------|
| `agentCard`         | `AgentCard`                   | Loaded from `a2at.server.agent-card` path via Jackson     |
| `a2aConfigProvider` | `A2AConfigProvider`           | SDK configuration values                                  |
| `taskStore`         | `InMemoryTaskStore`           | In-memory task storage                                    |
| `eventBus`          | `MainEventBus`                | Event bus for SSE streaming                               |
| `queueManager`      | `InMemoryQueueManager`        | Event queue manager                                       |
| `pushStore`         | `PushNotificationConfigStore` | Push notification config storage                          |
| `agentExecutorPool` | `ExecutorService`             | Thread pool for agent execution (8 threads, daemon)       |
| `eventBusProcessor` | `MainEventBusProcessor`       | Event bus processor                                       |
| `requestHandler`    | `RequestHandler`              | Default request handler                                   |
| `restHandler`       | `RestHandler`                 | REST protocol handler                                     |
| `a2aController`     | `A2AController`               | Spring MVC controller (`message:send` + `message:stream`) |

### A2AController

Spring MVC controller that exposes A2A REST endpoints:

- `POST {path-prefix}/message:send` — blocking send
- `POST {path-prefix}/message:stream` — SSE streaming

### Usage

The partner only needs to provide an `AgentExecutor` implementation:

```java
@Component
public class MyAgentExecutor implements AgentExecutor {
    @Override
    public ExecuteResult execute(ExecuteRequest request) {
        // business logic
        return ExecuteResult.builder()
                .addTextPart("result text")
                .build();
    }
}
```

All other beans (AgentCard, RequestHandler, RestHandler, A2AController, etc.) are auto-configured.
