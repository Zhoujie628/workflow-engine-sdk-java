# Developer Guide

This guide is for contributors and advanced users who want to understand the internal architecture, extend the SDK, or
contribute patches.

## 1. Installation

Add to your `pom.xml`:

```xml
<dependency>
    <groupId>dev.openan.workflow.sdk</groupId>
    <artifactId>workflow-engine</artifactId>
    <version>1.0.0</version>
</dependency>
```

The engine transitively pulls in the A2A protocol SDK (`a2a-java-sdk-client` with REST,
JSON-RPC, and gRPC transports) and the A2A-T extension SDK (`a2a-t-client`). No additional
dependencies are needed.

## 2. Core Concepts

| Layer    | Entry Point             | What It Handles                                      | What You Provide                       |
|----------|-------------------------|------------------------------------------------------|----------------------------------------|
| 2 (high) | `ExecutePsop.builder()` | Event collection, lifecycle, onFinish                | ControlPoint + AgentCards + config     |
| 1 (mid)  | `WorkflowExecutor`      | DAG traversal, context, dispatch (onTask/onSelfTask) | ControlPoint + EngineClient + Workflow |
| 0 (low)  | `WorkflowEngineClient`  | A2A send, response extraction                        | AgentCards + A2AJavaClientRuntime      |

## 3. Implement ControlPoint

Only two methods are required:

```java
public class MyControlPoint implements ControlPoint {
    @Override
    public CompletableFuture<TaskResponse> onTask(
            TaskRequest request, TaskDispatcher dispatcher) {
        return dispatcher.dispatch(TaskSubmission.fromText(
                        request.getAgentName(), request.getMessage(),
                        StandardTemplates.PRIVATE_LINE_COMPLAINT))
                .thenApply(result -> {
                    String state = result.getTaskState();
                    boolean success = state == null || state.isBlank()
                            || "TASK_STATE_COMPLETED".equals(state);
                    return TaskResponse.builder()
                            .success(success)
                            .output(result.getText())
                            .build();
                });
    }

    @Override
    public CompletableFuture<RouteDecision> onRoute(
            String stepName, Map<String, Object> results,
            List<JumpCondition> conditions) {
        return CompletableFuture.completedFuture(
                RouteDecision.builder()
                        .nextStep(conditions.get(0).getStep())
                        .reason("picked first")
                        .build());
    }
}
```

`onNegotiation` has a default that returns a generic `acceptText` decision. Authorization and notification are independent protocol operations sent via dedicated `ExtensionSender`/transport instances at a workbench-selected business time, not `ControlPoint` callbacks or workflow DAG nodes.

## 4. Execute via Builder (recommended)

```java
ExecutionResult result = ExecutePsop.builder()
        .psop(workflow)
        .agentCards(agentCards)
        .controlPoint(new MyControlPoint())
        .runtimeIntent("Diagnose SPN fault")
        .lang("zh")
        .sslVerify(false)
        .a2atEnvPath(".env")
        .credentialsConfigPath("agent_credentials.json")
        .eventCallback(new EventCallback())
        .onFinish((r, e) -> {
            persist(r);
            return CompletableFuture.completedFuture(null);
        })
        .execute()
        .join();
```

Required: `psop`, `controlPoint`. All others have sensible defaults.
`onFinish` accepts both the async `BiFunction<..., CompletableFuture<Void>>`
and a sync `BiConsumer` overload.

## 5. Event Types

Events come from three layers: the runner (lifecycle bracket), the executor (step/task/routing), and the engine client
(agent traffic, negotiation).

| Event                   | Layer              | When                                             | Key Data                                                |
|-------------------------|--------------------|--------------------------------------------------|---------------------------------------------------------|
| `start`                 | runner             | Workflow begins                                  | `workflow`, `steps`                                     |
| `step_start`            | executor           | Step begins                                      | `step`                                                  |
| `task_request`          | executor           | A subtask is dispatched to `onTask`/`onSelfTask` | `step`, `agent`, `task`                                 |
| `task_response`         | executor           | `onTask`/`onSelfTask` returned a `TaskResponse`  | `step`, `agent`, `task`, `output`                       |
| `task_status_changed`   | executor           | Task status changed (pending → running → success/failed) | `step`, `agent`, `task`, `status`                |
| `route_decision`        | executor           | Branch chosen                                    | `step`, `next`, `reason`                                |
| `step_complete`         | executor           | Step finished                                    | `step`, `results`                                       |
| `workflow_complete`     | executor           | All steps finished                               | `history`, `step_outputs`                               |
| `agent_request`         | engine client      | Message sent to agent                            | `agent`, `request`, `metadata`                          |
| `agent_response`        | engine client      | Response from agent                              | `agent`, `response`                                     |
| `agent_status_update`   | engine client      | Agent SSE status update                          | `agent`, `state`, `is_final`                            |
| `agent_artifact_update` | engine client      | Agent SSE artifact update                        | `agent`, `artifact_name`, `text`                        |
| `negotiation_request`   | engine client      | Agent needs clarification                        | `agent`, `round`, `concern`                             |
| `negotiation_resolved`  | engine client      | Negotiation decision generated                   | `agent`, `round`, `decision`                            |
| `negotiation_failed`    | engine client      | Negotiation failed                               | `agent`, `round`, `reason`                              |
| `complete`              | runner             | Workflow succeeded                               | `history`, `step_outputs`                               |
| `error`                 | runner or executor | Workflow failed                                  | runner: `error`, `history`; executor: `step`, `results` |
| `close`                 | runner             | Cleanup done                                     | (empty)                                                 |

## 6. Mid-Level (Layer 1: WorkflowExecutor)

```java
try(var client = new DefaultWorkflowEngineClient(agentCards, a2aRuntime,
        WorkflowEngineClientConfig.builder()
                .sslVerify(false)
                .credentialsConfigPath("etc/conf/agent_credentials.json")
                .a2atEnvPath(".env")
                .build())){
WorkflowExecutor executor = new WorkflowExecutor(
        workflow,
        new MyControlPoint(),
        client,
        new EventCallback(),
        "Diagnose fault",
        "zh"
);
ExecutionResult result = executor.run().join();
}
```

### 6.1 Negotiation Auto-Loop

After dispatch, the engine client automatically handles negotiation:
when the agent returns `INPUT_REQUIRED`, the engine extracts the negotiation text from response metadata, calls
`ControlPoint.onNegotiation()`
for a typed decision, and sends it back as a follow-up message. The loop repeats up to `maxNegotiationRounds` (default
3).

Override `onNegotiation()` in your `ControlPoint` to provide business-specific clarifications:

```java

@Override
public CompletableFuture<NegotiationDecision> onNegotiation(
        NegotiationRequest request) {
    return myLlm.generate(
                    "Agent " + request.agentName() + " needs: " + request.concern())
            .thenApply(Response::text)
            .thenApply(NegotiationDecision::acceptText);
}
```

Returning `null` fails the round. Use `acceptData/rejectData/abortData` for structured business values; invalid
names, blank values, and the literal string `"null"` fail before SDK invocation.

### 6.2 Workflow Model Fields

| Field                 | Where                 | Meaning                                                                                                                                                                                                         |
|-----------------------|-----------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `steps[].stepType`    | `WorkflowStep`        | `AllSuccess` (default): every subtask must succeed; `AnySuccess`: any subtask success suffices; `SelfLoop`: the workflow agent handles the step locally via `onSelfTask` (no A2A-T message to the named agent). |
| `steps[].subtasks[]`  | `Task`                | Each has `agent`, `skill`, `description`. One `onTask` (or `onSelfTask` for SelfLoop) call per subtask.                                                                                                         |
| `steps[].next[]`      | `List<JumpCondition>` | Branch targets. `step` = next step name; `condition` = rule text.                                                                                                                                               |
| `steps[].layer`       | `WorkflowStep`        | `layer == 0` starts the DAG (context = runtime intent only). Higher layers get upstream results.                                                                                                                |
| `steps[].contextFrom` | `WorkflowStep`        | Optional step names whose outputs fold into context. `"*"` = all ancestors.                                                                                                                                     |

### 6.3 AgentCard Type

The Java SDK uses `org.a2aproject.sdk.spec.AgentCard` (strongly typed record)
throughout. `RegistryClient.fetchAgentCards()` returns
`List<Map<String, Object>>` (normalized from OpenAPI format). Use
`AgentCardJacksonModule` with Jackson to deserialize JSON to `AgentCard`:

```java
ObjectMapper mapper = new ObjectMapper()
        .registerModule(new AgentCardJacksonModule());
AgentCard card = mapper.readValue(json, AgentCard.class);
```

## 7. Agent Authentication

When AgentCards declare `securitySchemes`, `DefaultWorkflowEngineClient`
logs in via `AgentCredentialService`, caches the token for `token_ttl`
seconds, and attaches the auth header to outbound requests.

### 7.1 Credential File

```json
{
  "SPN Domain Agent": {
    "bearerAuth": {
      "login_url": "https://127.0.0.1:8080/auth/login",
      "method": "POST",
      "request_fields": {
        "username": "...",
        "password": "..."
      },
      "token_field": "access_token",
      "token_ttl": 3600
    }
  }
}
```

Passwords can be AES-GCM encrypted with `enc:<iv>:<ciphertext>` prefix. The decryption key is read from `A2AT_CRED_KEY`
(env var or system property, loaded from `.env` by `EnvFileLoader`).

### 7.2 Custom AuthProvider

For non-standard auth (SSO, API keys, custom headers):
```java
WorkflowEngineClientConfig.builder()
    .authProvider((agentName, agentCard, headers) -> {
        headers.put("Authorization", "Bearer " + mySsoToken);
        headers.put("X-Custom", "value");
    })
    .build();
```

### 7.3 Credential File Fields

| Field                | Required | Default            | Description                               |
|----------------------|----------|--------------------|-------------------------------------------|
| `login_url`          | Yes      | -                  | URL to obtain the access token            |
| `method`             | No       | `POST`             | HTTP method                               |
| `content_type`       | No       | `application/json` | Content type                              |
| `request_fields`     | No       | -                  | Body fields (overrides username/password) |
| `token_field`        | No       | `accessSession`    | Dot-separated token path                  |
| `token_ttl`          | No       | `3600`             | Token cache TTL (seconds)                 |
| `auth_header`        | No       | `Authorization`    | Custom header name                        |
| `auth_header_prefix` | No       | (empty)            | Prefix before token                       |
| `accept_header`      | No       | -                  | Custom Accept header                      |

## 8. SSL / TLS

```java
WorkflowEngineClientConfig config = WorkflowEngineClientConfig.builder()
        .sslVerify(true)
        .caCertsPath("/etc/ssl/certs/ca-bundle.crt")
        .build();
```

Set `sslVerify=false` only for dev with self-signed certs.

## 9. A2A-T Environment (.env)

```ini
A2AT_LLM_PROVIDER=openai
A2AT_LLM_MODEL=deepseek-chat
A2AT_LLM_API_KEY=sk-...
A2AT_LLM_BASE_URL=https://api.deepseek.com
A2AT_LANGUAGE=zh-CN
A2AT_CRED_KEY=<32-byte hex>
```

When `a2atEnvPath` is null, unstructured `sendMessage` sends plain A2A text and does not claim
Task-T semantics. Structured Task-T, Authorization-T, Notification-T, and Negotiation-T operations
fail explicitly.

**Offline tests**: test `.env` resources select `OfflineA2ATLlmClient` through the SDK SPI. This
keeps the real A2ATClient/A2ATServer generation, validation, and parameter-filling pipelines
repeatable without network access. There is no production fallback that disables the SDK and sends
default negotiation text.

## 10. Integration Patterns

### SSE Server (Spring WebFlux)

```java

@GetMapping("/execute/{psopId}")
public Flux<String> execute(@PathVariable String psopId) {
    Workflow workflow = LoadPsop.load(baseUrl, psopId, token, false);

    return Flux.create(sink -> {
        ExecutePsop.builder()
                .psop(workflow)
                .agentCards(cards)
                .controlPoint(cp)
                .eventCallback(new EventCallback() {
                    @Override
                    public void onEvent(String type, Map<String, Object> data) {
                        sink.next("data: " + toJson(type, data) + "\n\n");
                    }
                })
                .onFinish((r, e) -> {
                    sink.complete();
                    return CompletableFuture.completedFuture(null);
                })
                .execute();
    });
}
```

### Cancellation

`ExecutePsop.builder().execute()` returns a `CompletableFuture`. You can
`cancel(true)` it, but the internal executor does not actively interrupt a running A2A call. For SSE, drop the
subscriber and let the future complete.

## 11. Checklist

1. Add Maven dependencies
2. Implement `ControlPoint` (at minimum `onTask` + `onRoute`; `onSelfTask` and `onNegotiation` have defaults)
3. Get AgentCards (from registry or JSON files)
4. Load Workflow (via `LoadPsop` or build your own)
5. Configure `.env` and credentials file
6. Call `ExecutePsop.builder().execute()`
7. Handle events + onFinish persistence
