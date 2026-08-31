# A2A-T Workflow Execution Engine (Java)

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-17+-orange.svg)](https://adoptium.net/)
[![Maven](https://img.shields.io/badge/Maven-3.6+-red.svg)](https://maven.apache.org/)

A standalone SDK for executing multi-agent workflows over the [A2A protocol](https://a2aproject.github.io/a2a-java/) with [A2A-T](https://projects.tmforum.org/a2aproject/telecommunication/extensions/) telecom extensions.

The engine handles workflow scheduling, A2A envelopes, transport, task waiting, authentication and TLS. Host callbacks return final message content and own any A2A-T generation, schema, validation or LLM calls.

## Features

- **A2A-T Extension Support**: Task-T (structured task prompts), Negotiation-T (stateless auto negotiation loop), Authorization-T (independent whitelist operation), Notification-T (independent long-lived SSE subscription)
- **Content-neutral callbacks**: final MessageContent, complete ReceivedMessage, local multi-output TaskResult and explicit NegotiationReply.Send/Stop
- **Minimal A2A-T dependency**: a2a-t-core only in the engine; content generation and template queries use the host's explicit a2a-t-client dependency
- **DAG Workflow Execution**: Parallel dispatch, self-loop steps, conditional routing
- **Multi-Protocol Transport**: REST, JSON-RPC, and gRPC auto-selected from AgentCard
- **Authentication**: Bearer token login with TTL cache, AES-256-GCM encrypted credentials, custom `AuthProvider`
- **HTTPS/TLS**: Configurable trust store, self-signed cert support for development
- **Protocol Logging**: actual HTTP/JSON-RPC boundaries, gRPC metadata/protobuf views and dev Order SDK observations; bodies default on at DEBUG with mandatory secret-field redaction

The SPN sample treats the protocol document as an input, not as executable truth. The pinned A2A-T
SDK templates, slot schemas, canonical URIs, and validation results are authoritative. Protocol
generation and validation fail closed; raw text is never sent under an A2A-T URI as a fallback.

In `SpringSpnDemo`, WAIMO sends a Task-T complaint to the workbench. The workbench loads the PSOP,
dispatches two city-specific OMC diagnoses in parallel, joins both branches exactly once, and
returns the real merged result over direct HTTP+JSON transport. Task-T, Authorization-T, and
Notification-T each use an independent transport/runtime/context. Authorization and Notification
are independently triggered workbench operations rather than DAG nodes; Notification keeps an
explicit long-lived subscription until the recovery result, cancellation, or shutdown.

## Quick Start

### 1. Add Maven dependency

A2A-T SDK `1.1.0` is published to Maven Central. Maven resolves it automatically;
no SDK source checkout or local SDK build is required. The engine depends only on
`a2a-t-core`; hosts using content generation explicitly add `a2a-t-client:1.1.0`
(and `a2a-t-server:1.1.0` for receiving-side validation).
See the [A2A-T SDK dependency guide](docs/zh/A2AT-SDK-DEPENDENCY.md) for IDEA setup and upgrade checks.

```xml
<dependency>
    <groupId>dev.openan.workflow.sdk</groupId>
    <artifactId>workflow-engine</artifactId>
    <version>1.0.0</version>
</dependency>
```

For Spring Boot server-side integration:

```xml
<dependency>
    <groupId>dev.openan.workflow.sdk</groupId>
    <artifactId>spring-boot-starter</artifactId>
    <version>1.0.0</version>
</dependency>
```

### 2. Execute a workflow

```java
import java.util.concurrent.*;

// 1. Load workflow (PSOP) from orchestration center
Workflow workflow = LoadPsop.load(
        "https://127.0.0.1:5001", "psop-id", null, false);

// 2. Load agent cards
RegistryClient registry = new RegistryClient("https://127.0.0.1:5000", false);
List<AgentCard> agentCards = registry.fetchAgentCards();

// 3. Create transport + engine client
A2ATransport transport = new A2ATransport(agentCards, null,
        WorkflowEngineClientConfig.builder()
                .sslVerify(true)
                .credentialsConfigPath("credentials.json")
                .build());
WorkflowEngineClient client = new DefaultWorkflowEngineClient(transport);

// 4. Plain A2A example; use host A2A-T SDK generation for Task-T.
ControlPoint controlPoint = ControlPoint.builder()
    .onTask(request -> CompletableFuture.completedFuture(
        MessageContent.text(request.getInstruction())))
    .build();
// Implement local, conditional-route and negotiation callbacks as needed.
// Complete contract: docs/en/BUSINESS_CALLBACKS.md

// 5. Execute
ExecutionResult result = ExecutePsop.builder()
        .psop(workflow)
        .agentCards(agentCards)
        .controlPoint(controlPoint)
        .engineClient(client)
        .runtimeIntent("Diagnose fault")
        .lang("zh")
        .execute()
        .get(10, TimeUnit.MINUTES);

System.out.println("Success: " + result.isSuccess());
```


Final content callbacks, complete dependency inputs and negotiation Send/Stop: [English](docs/en/BUSINESS_CALLBACKS.md) / [中文](docs/zh/BUSINESS_CALLBACKS.md).

## Architecture

```mermaid
graph TD
    L2["Layer 2 — Orchestration<br/>ExecutePsop<br/>lifecycle, event stream, onFinish hook"]
    L1["Layer 1 — Traversal<br/>WorkflowExecutor<br/>DAG walk, parallel dispatch, context assembly, routing"]
    L0["Layer 0 — Transport<br/>WorkflowEngineClient / A2ATransport<br/>A2A send, auth, extensions, SSL, SSE"]
    F["Foundation — Decision<br/>ControlPoint<br/>user-implemented business decisions"]

    L2 --> L1 --> L0
    L0 -.-> F
```

| Layer | Entry Point | Responsibility |
|-------|-------------|----------------|
| High | `ExecutePsop.Builder` | Event stream, lifecycle, `onFinish` persistence |
| Mid | `WorkflowExecutor` | DAG traversal, context assembly, ControlPoint dispatch |
| Low | `WorkflowEngineClient` / `A2ATransport` | A2A send, auth, extensions, SSL, SSE normalization |
| Foundation | `ControlPoint` | User-implemented business decisions (onTask, onSelfTask, onRoute, onNegotiation) |

## Package Structure

```mermaid
graph TD
    root["dev.openan.workflow.engine"]
    client["client<br/>A2A transport, auth, extensions"]
    control["control<br/>User decision interfaces"]
    core["core<br/>DAG traversal, context assembly"]
    model["model<br/>Data models"]
    registry["registry<br/>LoadPsop, RegistryClient"]
    runner["runner<br/>ExecutePsop entry point"]

    root --> client
    root --> control
    root --> core
    root --> model
    root --> registry
    root --> runner
```

| Package | Key Classes | Description |
|---------|-------------|-------------|
| `client` | `WorkflowEngineClient`, `DefaultWorkflowEngineClient`, `ExtensionSender`, `A2ATransport`, `AuthProvider`, `AgentAuthManager`, `WorkflowEngineClientConfig`, `CredentialCrypto`, `AgentCardJacksonModule` | A2A transport, auth, extensions (package-private internals) |
| `control` | `ControlPoint`, `DefaultControlPoint`, `EventCallback`, `EventType`, `NegotiationStrategy` | User-facing decision interfaces |
| `core` | `WorkflowExecutor`, `ContextBuilder` | DAG traversal, context assembly |
| `model` | `Workflow`, `WorkflowStep`, `Task`, `TaskRequest`, `MessageContent`, `TaskResult`, `NegotiationRequest`, `NegotiationReply`, `ExecutionResult` | Data models |
| `registry` | `LoadPsop`, `RegistryClient` | PSOP loading and AgentCard registry |
| `runner` | `ExecutePsop` | Entry point for workflow execution |

> **Note:** `LlmHelper` lives in the **samples** module (`dev.openan.workflow.engine.examples`), not in the `client` package. The workflow engine itself does not call an LLM directly.

## Documentation

### English

- [Integration Guide](docs/en/INTEGRATION_GUIDE.md) - Setup, configuration, secondary development
- [API Reference](docs/en/API_REFERENCE.md) - Public interface and class documentation
- [Design Document](docs/en/DESIGN.md) - Architecture, module structure, design decisions
- [Developer Guide](docs/en/DEVELOPER_GUIDE.md) - Internal architecture, contribution, debugging

### 中文

- [集成指南](docs/zh/INTEGRATION_GUIDE.md) - 安装、配置、二次开发
- [API 参考](docs/zh/API_REFERENCE.md) - 公共接口和类文档
- [架构设计](docs/zh/DESIGN.md) - 架构、模块结构、设计决策
- [开发者指南](docs/zh/DEVELOPER_GUIDE.md) - 内部架构、贡献、调试
- [工作台集成指南](docs/zh/工作台集成指南.md) - 工作台 Spring Boot 集成完整指南

## Modules

| Module | Description |
|--------|-------------|
| `workflow-engine` | Core SDK: workflow execution, A2A transport, extensions, auth |
| `spring-boot-starter` | Spring Boot auto-configuration for A2A server side |
| `samples` | Demo applications (embedded + Spring Boot variants) |

## License

[Apache License 2.0](LICENSE)

### Inspect negotiation in the local Demo

Running the local SpringSpnDemo without VM options now negotiates missing City1 input; City2 diagnoses directly.
This is a local sample scenario, not an engine default. To use complete inputs in both cities, add this IDEA **VM option**:

```text
-Da2at.samples.negotiation=false
```

By default only City1 loses its Task-T task object. Add `-Da2at.samples.negotiation.city=city2` or `both`
to exercise City2 or both cities. The host retains city-scoped authoritative input and the complaint context is preserved.
Expect DEMO_NEGOTIATION → INPUT_REQUIRED/PROPOSE → onNegotiation → ACCEPT → both diagnoses → one aggregate.
External-OMC mode defaults to no injection and rejects an explicit true switch.
The Demo passes its setting into the current Spring application context without modifying JVM-wide properties.
Protocol observations are in the console and `logs/spn-demo.log` relative to the run directory.

Run `SpringSpnDemoE2ETest` (direct) and, on dev, `SpringSpnDemoOrderE2ETest` sequentially.
Each tests the no-VM-option single-city default, explicit disable/enable and both-city negotiation with current SDK resources and an offline LLM provider.
This is local protocol E2E evidence, not real model/platform/OMC validation.
