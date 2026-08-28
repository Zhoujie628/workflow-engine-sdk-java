# A2A-T Workflow Execution Engine - API Reference

## Package Overview

| Package                           | Description                                     |
|-----------------------------------|-------------------------------------------------|
| `dev.openan.workflow.engine.client`   | A2A message transport, auth, extensions, config |
| `dev.openan.workflow.engine.control`  | User decision points and event system           |
| `dev.openan.workflow.engine.core`     | DAG traversal engine and context assembly        |
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
| `engineClient(WorkflowEngineClient)`    | optional | null        | Pre-configured client (null = auto-create)  |
| `runtimeIntent(String)`                  | optional | `""`        | Natural-language intent for context assembly |
| `lang(String)`                           | optional | `"zh"`      | Language hint (`"zh"` or `"en"`)             |
| `a2atEnvPath(String)`                    | optional | null        | Path to `.env` file for A2A-T SDK            |
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
        .a2atEnvPath(".env")
        .sslVerify(false)
        .execute()
        .get(10, TimeUnit.MINUTES);
```

**Returns:** `CompletableFuture<ExecutionResult>`

---

## dev.openan.workflow.engine.client

### WorkflowEngineClient

Primary interface for sending A2A messages to agents.

```java
public interface WorkflowEngineClient {
    // Send a message with optional context ID and preset metadata
    CompletableFuture<SendMessageResult> sendMessage(
            String agentName, String message,
            String contextId, Map<String, Object> metadata);

    // Convenience: no context ID, no metadata
    CompletableFuture<SendMessageResult> sendMessage(
            String agentName, String message);

    void setControlPoint(ControlPoint controlPoint);

    void setEventCallback(EventCallback callback);

    // A2A-T template queries (empty when the SDK is not configured; never throws)
    List<PromptTemplate> getPrompts();               // all extensions' templates
    List<PromptTemplate> getNegotiationPrompts();    // negotiation templates (3 types x 2 phases + abort)
    Optional<PromptTemplate> getPrompt(TemplateUri); // single template by URI

    // Task lifecycle (A2A protocol operations)
    CompletableFuture<SendMessageResult> getTask(String agentName, String taskId);
    CompletableFuture<SendMessageResult> cancelTask(String agentName, String taskId);
    CompletableFuture<SendMessageResult> subscribeToTask(
            String agentName, String taskId, Consumer<Map<String, Object>> callback);

    void close();
}
```

> Pre-positioning lives on `ExtensionSender`, not here: Authorization-T is a one-shot request and Notification-T is a
> long-lived subscription. See the `ExtensionSender` section below.

#### sendMessage

| Parameter   | Type                  | Description                                   |
|-------------|-----------------------|-----------------------------------------------|
| `agentName` | `String`              | Target agent name (must match AgentCard.name) |
| `message`   | `String`              | Full assembled message text                   |
| `contextId` | `String`              | Optional context ID (null = auto-generated)   |
| `metadata`  | `Map<String, Object>` | Optional preset metadata                      |

**Returns:** `CompletableFuture<SendMessageResult>` containing response text, task, metadata, and task state.

The engine internally handles before sending:

1. Task-T prompt generation (if AgentCard declares Task-T)
2. Negotiation-T metadata injection (for follow-up messages)
3. Auth header injection (from credentials or AuthProvider)
4. A2A-Extensions header (only extensions present in metadata)

After receiving:

1. Response text extraction from SSE events
2. Metadata extraction (task-level + artifact-level)
3. Negotiation-T auto-loop (triggered when metadata carries the Negotiation-T key; see "Negotiation auto-loop behavior" below)

#### sendMessageFromData (structured-data input path)

```java
CompletableFuture<SendMessageResult> sendMessageFromData(
        String agentName,
        String message,
        Map<String, Object> data,
        Map<String, Object> schema,
        TemplateUri templateUri);
```

| Parameter     | Type                  | Description                                                        |
|---------------|-----------------------|---------------------------------------------------------------------|
| `agentName`   | `String`              | Target agent name                                                   |
| `message`     | `String`              | Short accompanying message text (A2A message parts text)           |
| `data`        | `Map<String, Object>` | Structured business data (raw fields, not a rendered prompt)       |
| `schema`      | `Map<String, Object>` | JSON schema describing what each data field means                  |
| `templateUri` | `TemplateUri`         | Explicit current SDK target template; must not be null             |

**Choosing a track**: when the caller holds structured data (e.g. complaint-ticket fields) prefer
this method — the SDK uses the schema-aware
`generateTaskPromptFromDataWithSchema` pipeline. It bypasses scenario recognition, but slot
mapping may still invoke the SDK-configured LLM. With free-form
natural-language input use `sendMessage` (the SDK scenario-recognition pipeline). Both tracks run
through the full Negotiation-T auto-loop, auth, and extension-header injection.

`ExtensionSender.sendExtensionMessageFromData(agentName, instruction, data, schema, templateUri, extension)`
provides the same structured-data track for independent Authorization-T operations;
`openNotificationFromData` is the preferred structured Notification-T subscription API. All
Notification-T entry points require an explicit SDK template so callers can select either
`SERVICE_RECOVERY` or `SUBSCRIBE_INCIDENT`.

#### Negotiation auto-loop behavior

- **Trigger**: the response metadata carries the Negotiation-T extension key (the agent opened a
  negotiation via `Message(ROLE_AGENT)` + `INPUT_REQUIRED`) together with SDK-generated
  `templateUri` and `negotiationContext={id,round,maxRounds,performative}`. Missing fields fail closed.
- **Each round**: `validateProposePromptAndDataFilling` validates and extracts the Propose →
  `ControlPoint.onNegotiation` decides → the SDK content layer renders Accept / Reject / Abort →
  Accept is checked with `validateAcceptPromptAndDataFilling` before sending → the engine copies
  the ending message preserves the received Propose `NegotiationContext` and round → send → recurse.
- **Typed decisions** (the `onNegotiation` return value):
  - `NegotiationDecision.acceptText/acceptData` — Accept through SDK fromText/fromData
  - `NegotiationDecision.rejectText/rejectData` — Reject through SDK fromText/fromData
  - `NegotiationDecision.abortText/abortData` — Abort through SDK fromText/fromData
  - String control prefixes such as `data:`, `reject:`, and `abort:` are not accepted.
  - Information Reject is itemized: every `rejectData` key names an unavailable requested item and
    its value gives that item's concrete non-provision reason. Do not merge several requested items
    into one aggregate rejection reason.
- **Round exhaustion**: beyond `maxNegotiationRounds` the loop stops, a terminal message is sent
  via the SDK abort template (best effort — delivery failures are logged only), a
  `NEGOTIATION_FAILED` event fires, and the last agent reply stands as the final response.
- **Negotiation context**: the engine does not support the legacy `startNegotiation`,
  `receiveNegotiation`, or `continueNegotiation` state-machine entry points. It accepts only
  canonical `negotiationContext` with
  `{id, round, maxRounds, performative}` and does not read legacy `negotiation_context` metadata.

#### Template queries

The template queries expose the SDK content layer's template catalog and return
`PromptTemplate` (templateUri / description / content):

- `getPrompts()`: every A2A-T extension's (Task-T / Notification-T / Authorization-T /
  Negotiation-T) loadable templates for the configured language (`.env` `A2AT_LANGUAGE`), sorted by URI.
- `getNegotiationPrompts()`: the full negotiation set (propose and accept-reject for information,
  target and feasibility negotiation, plus the common abort template), fixed order.
- `getPrompt(uri)`: one template by `TemplateUri`; empty when missing.

Use the SDK's `StandardTemplates` constants for template URIs (e.g.
`StandardTemplates.INFORMATION_NEGOTIATION_PROPOSE`) instead of hand-written URI strings.

### ExtensionSender

Independent-protocol facade over caller-owned, dedicated `A2ATransport` instances. It sends one-shot
Authorization-T requests and establishes long-lived Notification-T subscriptions at independent
business times; neither operation is a workflow DAG node. It bypasses Task-T prompt
generation and the Negotiation-T auto-loop; later Notification-T events are delivered through the subscription callback.

```java
public interface ExtensionSender {
    CompletableFuture<SendMessageResult> sendExtensionMessage(
            String agentName, String instruction,
            String naturalLanguageInput, TemplateUri templateUri,
            A2ATExtension extension); // Authorization-T only

    // Convenience: Authorization-T
    CompletableFuture<SendMessageResult> sendAuthorization(
            String agentName, String instruction, String naturalLanguageInput);

    CompletableFuture<SendMessageResult> sendExtensionMessageFromData(
            String agentName, String instruction,
            Map<String, Object> data, Map<String, Object> schema,
            TemplateUri templateUri, A2ATExtension extension);

    // Convenience: Notification-T (long-lived SSE)
    CompletableFuture<SendMessageResult> sendNotification(
            String agentName, String instruction, String naturalLanguageInput,
            TemplateUri templateUri, Consumer<Map<String, Object>> eventCallback);

    // Interface contract: Notification-T (long-lived SSE + event callback)
    CompletableFuture<NotificationSubscription> openNotification(
            String agentName, String instruction,
            String naturalLanguageInput, TemplateUri templateUri,
            Consumer<Map<String, Object>> eventCallback);

    CompletableFuture<NotificationSubscription> openNotificationFromData(
            String agentName, String instruction,
            Map<String, Object> data, Map<String, Object> schema,
            TemplateUri templateUri,
            Consumer<Map<String, Object>> eventCallback);
}
```

| Parameter              | Type            | Description                                     |
|------------------------|-----------------|-------------------------------------------------|
| `agentName`            | `String`        | Target agent name (must match `AgentCard.name`) |
| `instruction`          | `String`        | Short instruction text; becomes `parts[].text` in the A2A message body |
| `naturalLanguageInput` | `String`        | Natural-language track input; production should prefer the SDK-rendered structured-data methods |
| `templateUri`          | `TemplateUri`   | Explicit current-SDK template (bundled or caller-provided resource) |
| `extension`            | `A2ATExtension` | Extension enum (never hardcode URIs)            |
| `eventCallback`        | `Consumer<Map<String, Object>>` | Optional SSE event callback. The stable Map includes `event_kind`, `agent`, `task_id` and event-specific state/artifact fields |

`NotificationSubscription` separates the initial ACK (`acknowledgement()`) from stream completion
(`completion()`), and exposes `heartbeat()`, `isHealthy(maximumIdle)`, and idempotent `close()`.
Notification-T is deliberately rejected by the generic one-shot methods; use
`openNotification` / `openNotificationFromData` so the lifecycle handle cannot be lost.
Task, Authorization, and Notification must use different transport/runtime instances. Completing a
workflow must not close a Notification-T subscription.

**Wire format**: The resulting A2A message sent to the agent has `parts[].text = instruction` and `metadata = { "<extension-URI>": "<extension value>" }`. For example, Authorization-T produces:

```json
{
  "parts": [{"text": "Authorize diagnosis operations"}],
  "metadata": {
    "https://projects.tmforum.org/a2aproject/telecommunication/extensions/Authorization-T/v1": "<structured authorization policy>"
  }
}
```

### WorkflowEngineClientConfig

Builder-based configuration for the workflow engine client.

| Property                        | Type                     | Default | Description                                  |
|---------------------------------|--------------------------|---------|----------------------------------------------|
| `sslVerify`                     | `boolean`                | `true`  | TLS chain verification for HTTP/JSON-RPC; disabling it keeps hostname checks and the mTLS client identity. Disabling it uses plaintext for default gRPC |
| `caCertsPath`                   | `String`                 | null    | Path to CA certs PEM file                    |
| `clientCertPath`                | `String`                 | null    | Path to the mTLS client certificate chain; default gRPC requires `sslVerify=true` |
| `clientKeyPath`                 | `String`                 | null    | Path to a PKCS#8 PEM/DER mTLS private key    |
| `clientKeyPassword`             | `String`                 | null    | Password for an encrypted PKCS#8 private key |
| `crlPath`                       | `String`                 | null    | X.509 CRL for HTTP/JSON-RPC; the default gRPC runtime rejects this unsupported combination |
| `sendTimeoutSeconds`            | `long`                   | `600`   | SSE stream timeout (10 min default)          |
| `notificationAckTimeoutSeconds` | `long`                   | `5`     | Wait for the first Notification-T ACK/event  |
| `sendExecutorCoreSize`          | `int`                    | `4`     | Send executor core threads                   |
| `sendExecutorMaxSize`           | `int`                    | `16`    | Send executor maximum threads                |
| `sendExecutorQueueCapacity`     | `int`                    | `256`   | Bounded send executor queue capacity         |
| `authProvider`                  | `AuthProvider`           | null    | Custom auth provider                         |
| `credentialsConfigPath`         | `String`                 | null    | Path to credentials JSON; explicit missing or malformed files fail startup |
| `credentialsConfig`             | `Map`                    | null    | Inline credentials config; must match AgentCard security requirements |
| `a2atEnvPath`                   | `String`                 | null    | Path to `.env` file                          |
| `maxNegotiationRounds`          | `int`                    | `3`     | Max negotiation auto-loop rounds             |
| `customHandlers`                | `List<ExtensionHandler>` | null    | Custom extension handlers                    |
| `negotiationParamSchema`        | `Map`                    | null    | Business parameter JSON schema for the negotiation validate-and-fill; null = empty schema (context params only). Declaring your domain fields keeps the engine core domain-agnostic |

```java
WorkflowEngineClientConfig config = WorkflowEngineClientConfig.builder()
        .sslVerify(true)
        .caCertsPath("ca.pem")
        .clientCertPath("client-cert.pem")
        .clientKeyPath("client-key.pem")
        .sendTimeoutSeconds(900)
        .a2atEnvPath(".env")
        .credentialsConfigPath("creds.json")
        .maxNegotiationRounds(5)
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

Called for every message send. The `headers` map is mutable; add `Authorization`, custom headers, etc. `AuthProvider` can be the sole authentication source, including when `securityRequirements` is non-empty and credentials are not configured. When credentials are also configured, both sets are computed independently and merged; different values for the same header name throw `SecurityException` instead of silently overwriting either value.

### ExtensionHandler

Extension handler for custom A2A-T extensions.

```java
public interface ExtensionHandler {
    String extensionKeyword();

    CompletableFuture<Map<String, Object>> beforeSend(
            AgentCard agentCard, String messageText,
            Map<String, Object> metadata,
            A2ATClient a2atClient, ControlPoint controlPoint);

    CompletableFuture<SendMessageResult> afterReceive(
            AgentCard agentCard, SendMessageResult result,
            A2ATClient a2atClient, ControlPoint controlPoint,
            EventCallback eventCallback);

    // Business parameter schema for the negotiation validate-and-fill (default: empty schema)
    Map<String, Object> negotiationParamSchema();
}
```

Built-in: Task-T and Negotiation-T are handled automatically. You can register custom handlers via customHandlers in the
config (a later registration with the same keyword overrides the built-in).

### A2ATContentFacade

Engine facade over the SDK's negotiation content layer, obtained via
`A2ATransport.getContentFacade()` (null when no `.env` is configured). It exposes the SDK's full
message-generation / validation / template-query surface, one method per `A2ATClient` counterpart:

| Group | Methods | Notes |
|-------|---------|-------|
| fromData generation | `generateProposeFromData` / `generateAcceptFromData` / `generateRejectFromData` / `generateAbortFromData` | deterministic rendering from typed data, no LLM |
| fromText generation | `generateProposeFromText` / `generateAcceptFromText` / `generateRejectFromText` / `generateAbortFromText` | free text + one LLM extraction step |
| Stateless context | `MetadataContent.buildMetadataContent` / `NegotiationContext` | carry `{id, round, maxRounds, performative}`; generation stamps the output performative |
| Validate and fill | `validatePropose` / `validateAccept` / `validateReject` / `validateAbort` | rule gate + LLM semantic validation + param merge; requires a `NegotiationContext` (null = not a negotiation message) |
| Template queries | `getPrompts` / `getNegotiationPrompts` / `getPrompt` / `getNegotiationPrompt` | catalog enumeration and lookup |
| Utility | `toMetadata(MetadataContent)` | generated message → A2A metadata map (with `templateUri`, `negotiationContext` keys) |

Pass template URIs via `StandardTemplates` constants. Failures throw `A2ATError` subtypes and must
fail closed: callers must never degrade by placing raw text under an A2A-T extension URI.

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

Optional lifecycle callback for runtime implementations whose transport session spans multiple A2A requests (e.g. a gateway login that must stay alive across all negotiation rounds).

```java
public interface ConversationScopedA2AJavaClientRuntime {
    void closeConversation(AgentCard agentCard, String contextId);
}
```

When the runtime also implements this interface, the engine calls `closeConversation` after the full send + negotiation cycle completes — not after each individual HTTP request. This allows gateway sessions to be released only after the logical conversation is done.

Implement alongside `A2AJavaClientRuntime`:

```java
public class MyGatewayRuntime
        implements A2AJavaClientRuntime, ConversationScopedA2AJavaClientRuntime {
    @Override
    public Iterable<ClientEvent> sendMessage(...) { ... }

    @Override
    public void closeConversation(AgentCard agentCard, String contextId) {
        // release gateway session
    }

    @Override
    public void close() { ... }
}
```

### AgentCardJacksonModule

Jackson module for deserializing AgentCard JSON with security scheme normalization. Handles the OpenAPI-format `securitySchemes` / `securityRequirements` fields that the A2A SDK's strongly-typed `AgentCard` record expects.

```java
ObjectMapper mapper = new ObjectMapper()
        .registerModule(new AgentCardJacksonModule());
AgentCard card = mapper.readValue(
        new File("agentcard/my_agent.json"), AgentCard.class);
```

### AgentCardNormalizer

Utility that normalizes a raw `Map<String, Object>` (as returned by the Registry Center API) into the `AgentCard`-compatible format. Used internally by `RegistryClient.fetchAgentCards()`. Also available as a public static method for custom normalization:

```java
Map<String, Object> normalized = AgentCardNormalizer.normalize(rawMap);
```

---

## dev.openan.workflow.engine.control

### TaskDispatcher / TaskSubmission

`onTask` receives only the narrow `TaskDispatcher` capability. Business code uses:

```java
dispatcher.dispatch(TaskSubmission.fromText(agentName, text, templateUri));
dispatcher.dispatch(TaskSubmission.fromUnclassifiedText(agentName, text));
dispatcher.dispatch(TaskSubmission.fromData(
        agentName, instruction, data, schema, templateUri));
```

The first form invokes the SDK's explicit natural-language API for a known Task-T template without
reclassifying the scenario; the second invokes SDK scenario recognition only when the template is
genuinely unknown; the third invokes schema-aware fromData. `TaskSubmission` validates the target, instruction, schema, and Task-T template and
defensively copies business data before it reaches the protocol layer.

### ControlPoint

User-facing decision interface. Each method has a single responsibility.

```java
public interface ControlPoint {
    // Take over a remote task and submit natural-language or structured Task-T input.
    CompletableFuture<TaskResponse> onTask(
            TaskRequest request, TaskDispatcher taskDispatcher);

    // Self-loop step: handled locally, no A2A-T message to self.
    default CompletableFuture<TaskResponse> onSelfTask(TaskRequest request);

    // Conditional branch decision. Only decide which step to go to.
    CompletableFuture<RouteDecision> onRoute(
            String stepName, Map<String, Object> results,
            List<JumpCondition> conditions);

    // Return a typed Accept / Reject / Abort decision.
    default CompletableFuture<NegotiationDecision> onNegotiation(
            NegotiationRequest request);
}
```

| Method            | When Called                                | Return                            |
|-------------------|--------------------------------------------|-----------------------------------|
| `onTask`          | A step dispatches a task to another agent  | `TaskResponse` (success + output) |
| `onSelfTask`      | A `SELF_LOOP` step runs locally (no A2A-T) | `TaskResponse` (success + output) |
| `onRoute`         | After step completes, before next step     | `RouteDecision` (nextStep)        |
| `onNegotiation`   | When agent returns `INPUT_REQUIRED`        | `NegotiationDecision`             |

`NegotiationRequest` exposes `agentName`, `concern`, `sessionId`, `round`, `maxRounds`, the typed
`NegotiationPerformative`, negotiation `kind`, `templateUri`, read-only SDK-extracted business
`parameters`, and read-only `metadata`. Business decisions should prefer `parameters`; `metadata`
is retained for advanced diagnostics. Only a
`PROPOSE` with a complete context reaches the callback; missing context, invalid rounds, or an
unsupported template fail closed. Business code only returns `acceptText/acceptData`,
`rejectText/rejectData`, or `abortText/abortData`; it does not send a message or construct protocol
metadata.
For an Information Reject, provide a separate reason for every unavailable requested item, for example:

```java
NegotiationDecision.rejectData(Map.of(
        "access-port name", "The current account cannot query the resource system",
        "complaint category", "The current account cannot query the resource system"));
```

### DefaultControlPoint

Default implementation with sensible defaults:

- `onTask`: dispatches `TaskSubmission.fromUnclassifiedText` by default and returns success/output;
  hosts with a known template should override it and use `fromText(..., templateUri)` or `fromData(...)`
- `onSelfTask`: echoes the task message back (override for local logic)
- `onRoute`: picks first non-terminal branch
- `onNegotiation`: returns a generic `acceptText` decision

Extend this class and override only the methods you need.

### EventCallback

```java
public class EventCallback {
    public void onEvent(String eventType, Map<String, Object> data) {
    }
}
```

Override to receive real-time execution events. Event types are defined in `EventType` constants.

### EventType

| Constant                 | Description                                        |
|--------------------------|----------------------------------------------------|
| `STEP_START`             | A workflow step began                              |
| `STEP_COMPLETE`          | A workflow step completed                          |
| `TASK_REQUEST`           | A task was dispatched to an agent                  |
| `TASK_RESPONSE`          | A task response was received                       |
| `TASK_STATUS_CHANGED`    | A task's status changed (pending → running → success/failed) |
| `AGENT_REQUEST`          | A message was sent to an agent                     |
| `AGENT_RESPONSE`         | A response was received from an agent              |
| `AGENT_STATUS_UPDATE`    | Agent SSE status update (SUBMITTED, WORKING, etc.) |
| `AGENT_ARTIFACT_UPDATE`  | Agent SSE artifact update                          |
| `AGENT_MESSAGE_EVENT`    | Agent SSE message event                            |
| `NEGOTIATION_REQUEST`    | Agent requested negotiation (INPUT_REQUIRED)       |
| `NEGOTIATION_RESOLVED`   | Clarification was sent to agent                    |
| `NEGOTIATION_FAILED`     | Negotiation could not be resolved                  |
| `AUTHORIZATION_REQUEST`  | Agent requested authorization                      |
| `AUTHORIZATION_RESOLVED` | Authorization decision was made                    |
| `NOTIFICATION`           | Notification received from agent                   |
| `ROUTE_DECISION`         | Route decision was made                            |
| `WORKFLOW_COMPLETE`      | The workflow completed (all steps finished)        |
| `START`                  | Workflow execution started                         |
| `COMPLETE`               | Workflow execution completed successfully          |
| `ERROR`                  | Workflow execution failed                          |
| `CLOSE`                  | Engine client closed                               |

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

Mid-layer DAG traversal engine. Walks the workflow steps, assembles upstream context via `ContextBuilder`, dispatches subtasks in parallel, applies step success policies (`ALL_SUCCESS` / `ANY_SUCCESS` / `SELF_LOOP`), and routes to the next step.

Not typically instantiated directly by SDK users — `ExecutePsop` wraps it internally. Exposed for advanced integrations that need to run the traversal layer without the runner's lifecycle management.

```java
WorkflowExecutor executor = new WorkflowExecutor(
        workflow, controlPoint, engineClient,
        eventCallback, runtimeIntent, lang);
ExecutionResult result = executor.run().join();
```

### ContextBuilder

Package-private helper that assembles the context message for each step by folding upstream step outputs according to `contextFrom` rules (`"*"` = all ancestors, specific names = selective inheritance). Not part of the public API surface.

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

| Field         | Type                  | Default       | Description                                              |
|---------------|-----------------------|---------------|----------------------------------------------------------|
| `name`        | `String`              | -             | Step name (unique within workflow)                       |
| `subtasks`    | `List<Task>`          | `List.of()`   | Subtasks dispatched in this step                         |
| `next`        | `List<JumpCondition>` | `List.of()`   | Conditional next steps                                   |
| `layer`       | `int`                 | `0`           | Context layer (0 = runtime intent only)                  |
| `contextFrom` | `List<String>`        | null          | Steps to inherit context from (`"*"` = all predecessors) |
| `stepType`    | `StepType`            | `ALL_SUCCESS` | Execution mode                                           |

### StepType

| Value         | Description                                                                                                            |
|---------------|------------------------------------------------------------------------------------------------------------------------|
| `ALL_SUCCESS` | All subtasks must succeed                                                                                              |
| `ANY_SUCCESS` | Any subtask success is sufficient                                                                                      |
| `SELF_LOOP`   | The workflow agent handles the task locally via `onSelfTask`; no A2A-T message is sent. Success follows `ALL_SUCCESS`. |

### TaskStatus

Task lifecycle status, used in `TASK_STATUS_CHANGED` events for cross-SDK consistency with the Python SDK.

| Value     | String     | Description                          |
|-----------|------------|--------------------------------------|
| `PENDING` | `"pending"` | Task created, not yet started        |
| `RUNNING` | `"running"` | Task in progress                     |
| `SUCCESS` | `"success"` | Task completed successfully          |
| `FAILED`  | `"failed"`  | Task failed                          |

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

### TaskRequest

| Field          | Type     | Description               |
|----------------|----------|---------------------------|
| `agentName`    | `String` | Target agent              |
| `skill`        | `String` | Agent skill               |
| `message`      | `String` | Full message text         |
| `description`  | `String` | Task description          |
| `context`      | `String` | Context message           |
| `stepName`     | `String` | Source step name          |
| `subtaskIndex` | `int`    | Subtask index within step |

### TaskResponse

| Field      | Type      | Description                |
|------------|-----------|----------------------------|
| `success`  | `boolean` | Whether the task succeeded |
| `output`   | `String`  | Response text              |
| `error`    | `String`  | Error message (if failed)  |
| `metadata` | `Map`     | Response metadata          |

### SendMessageResult

| Field       | Type     | Description                                    |
|-------------|----------|------------------------------------------------|
| `text`      | `String` | Extracted response text                        |
| `task`      | `Task`   | SDK Task object                                |
| `metadata`  | `Map`    | Response metadata (merged task + artifact)     |
| `taskState` | `String` | Final task state (e.g. `TASK_STATE_COMPLETED`) |

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
| Negotiation-T   | `https://projects.tmforum.org/a2aproject/telecommunication/extensions/Negotiation-T/v1`  |
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

- Agent call failures throw `RuntimeException` wrapping the cause.
- Negotiation beyond `maxNegotiationRounds` rounds: a `NEGOTIATION_FAILED` event fires, a terminal
  message is sent to the agent via the SDK abort template (best effort), and the last agent reply
  stands as the final response; the loop does not continue.
- SDK content-generation failures for negotiation follow-ups fail closed; raw business input is never
  disguised as Negotiation-T metadata.
- Missing required credentials or authentication failures fail the request; it is never sent unauthenticated.
- SSE stream errors after terminal events are logged at `DEBUG` level (expected behavior).

---

## spring-boot-starter Module

The `spring-boot-starter` module provides Spring Boot auto-configuration for the A2A **server** side (not the client/workflow side). When on the classpath of a Spring Boot web application, it auto-registers all A2A SDK server components as Spring beans.

### A2AProperties

Configuration properties prefixed with `a2at.server`:

| Property | Default | Description |
|----------|---------|-------------|
| `a2at.server.agent-card` | `classpath:agentcard.json` | Path to the AgentCard JSON file (classpath: or file: prefix supported) |
| `a2at.server.path-prefix` | `/a2a/json` | URL path prefix for A2A endpoints |
| `a2at.server.agent-timeout-seconds` | `30` | Agent execution timeout in seconds |
| `a2at.server.consumption-timeout-seconds` | `5` | Consumption timeout in seconds |
| `a2at.server.reconciliation-timeout-seconds` | `1` | Reconciliation wait timeout in seconds |
| `a2at.server.executor-core-size` | `8` | Server executor core threads |
| `a2at.server.executor-max-size` | `8` | Server executor maximum threads |
| `a2at.server.executor-queue-capacity` | `100` | Bounded server executor queue capacity |
| `a2at.server.executor-keep-alive-seconds` | `60` | Non-core thread keep-alive in seconds |

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

| Bean | Type | Purpose |
|------|------|---------|
| `agentCard` | `AgentCard` | Loaded from `a2at.server.agent-card` path via Jackson |
| `a2aConfigProvider` | `A2AConfigProvider` | SDK configuration values |
| `taskStore` | `InMemoryTaskStore` | In-memory task storage |
| `eventBus` | `MainEventBus` | Event bus for SSE streaming |
| `queueManager` | `InMemoryQueueManager` | Event queue manager |
| `pushStore` | `PushNotificationConfigStore` | Push notification config storage |
| `agentExecutorPool` | `ExecutorService` | Thread pool for agent execution (8 threads, daemon) |
| `eventBusProcessor` | `MainEventBusProcessor` | Event bus processor |
| `requestHandler` | `RequestHandler` | Default request handler |
| `restHandler` | `RestHandler` | REST protocol handler |
| `a2aController` | `A2AController` | Spring MVC controller (`message:send` + `message:stream`) |

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
