# A2A-T Engine SDK - Design

> Architecture and design rationale for the A2A-T Workflow Execution Engine.
> This document describes the system as shipped in v1.0. It is written for
> engineers integrating or extending the SDK, not as a walkthrough of any
> particular bug-fix history.

---

## 1. Overview

The SDK is an embedded workflow protocol scheduler, not a separately deployed business service. The host supplies final
content; the engine owns DAG execution, parallel scheduling, A2A envelopes, authentication, transport, waiting and
result association. Host callbacks choose whether/how to invoke A2A-T natural-language or structured content APIs.
Templates, schemas, LLMs and semantic validation belong to the host. The engine uses a2a-t-core only for canonical
negotiation context and metadata copying, not content generation.

| Engine responsibility                                           | Host responsibility                                           |
|-----------------------------------------------------------------|---------------------------------------------------------------|
| DAG, task/context association, concurrency and route validation | Final content, routing policy and local analysis              |
| Select complete upstream results with contextFrom               | Consume evidence and map downstream business input            |
| A2A authentication and transport mechanics                      | Credential source, AuthProvider and deployment                |
| Independent sends, subscription handles and lifecycle           | Authorization policy, subscription content and closure timing |

## 2. Host Integration and Surrounding Systems

```mermaid
%%{
  init: {
    "flowchart": {
      "curve": "basis",
      "nodeSpacing": 55,
      "rankSpacing": 75
    }
  }
}%%
flowchart TB
    classDef external fill: #EEF4FF, stroke: #2563EB, color: #172554, stroke-width: 1.5px
    classDef host fill: #FFF7ED, stroke: #EA580C, color: #431407, stroke-width: 1.5px
    classDef sdk fill: #ECFDF5, stroke: #059669, color: #052E16, stroke-width: 1.5px
    classDef local fill: #F8FAFC, stroke: #64748B, color: #0F172A, stroke-dasharray: 5 3
    classDef agent fill: #F5F3FF, stroke: #7C3AED, color: #2E1065, stroke-width: 1.5px

    subgraph CONTROL["周边控制面与上游系统"]
        direction LR
        WAIMO["WAIMO<br/>上游任务发起方"]:::external
        REG["注册中心 / Registry Center<br/>AgentCard 发布与查询"]:::external
        ORCH["编排中心 / Orchestration Center<br/>工作流检索与 PSOP 加载"]:::external
    end

    subgraph HOST["宿主智能体：集成方 / Integrator Agent"]
        direction TB
        ENTRY["A2A 服务端入口<br/>接收 Task-T，解析诊断意图"]:::host
        ADAPTER["宿主集成层<br/>准备 AgentCard、Workflow、配置和业务上下文"]:::host

        subgraph ENGINE["嵌入式 Workflow Execution Engine SDK"]
            direction TB
            LOAD["发现辅助 API<br/>RegistryClient / LoadPsop"]:::sdk
            RUN["工作流协议调度<br/>ExecutePsop → WorkflowExecutor<br/>Task-T + Negotiation-T"]:::sdk
            CALLBACK["宿主回调接口<br/>ControlPoint / EventCallback / onFinish"]:::sdk
            EXT["流程外协议操作<br/>ExtensionSender<br/>Authorization-T / Notification-T"]:::sdk
        end

        BIZ["集成方业务实现<br/>业务接管、路由、汇总、持久化、通知处理"]:::host
    end

    subgraph SAMPLE["Demo 本地替代路径（非生产数据源）"]
        direction LR
        LOCALCARD["本地 AgentCard JSON<br/>WorkbenchAgentCatalog"]:::local
        LOCALPSOP["本地 PSOP fallback<br/>仅用于离线演示"]:::local
    end

    subgraph DOWNSTREAM["下游业务智能体"]
        OMCS["地市 OMC 智能体群<br/>地市 1 OMC ｜ 地市 2 OMC"]:::agent
    end

    WAIMO <-->|" Task-T 诊断任务 / 汇总 artifact "| ENTRY
    ENTRY -->|" 运行意图 "| ADAPTER
    REG -->|" 生产：查询 AgentCard "| LOAD
    ORCH -->|" search / load PSOP "| LOAD
    LOCALCARD -.->|" Demo 替代注册中心 "| ADAPTER
    LOCALPSOP -.->|" Demo 检索失败时 "| ADAPTER
    LOAD -->|" 发现结果交给宿主 "| ADAPTER
    ADAPTER -->|" Workflow + AgentCards "| RUN
    RUN <-->|" 业务决策与执行结果 "| CALLBACK
    CALLBACK <-->|" 回调 "| BIZ
    ADAPTER -->|" 独立业务时机 "| EXT
    RUN <-->|" 向两地市并行 Task-T<br/>必要时 Negotiation-T / 返回诊断结果 "| OMCS
    EXT <-->|" Authorization-T 一次性授权<br/>Notification-T 长连接订阅与结果 SSE "| OMCS
    BIZ -->|" 汇总结果 "| ENTRY
```

The SDK in this diagram is a library embedded in the host-agent process, not a separately deployed orchestration
service. In `SpringSpnDemo`, the transport integrator is the host agent:

1. WAIMO invokes the integrator's A2A server endpoint with a Task-T diagnosis request.
2. The integrator prepares execution inputs. In production it obtains downstream AgentCards from the registry center and
   searches/loads a PSOP from the orchestration center. The SDK offers optional
   `RegistryClient` and `LoadPsop` helpers, while discovery timing, caching, and failure policy remain host
   responsibilities.
3. The integrator passes the `Workflow`, AgentCards, runtime intent, and business callbacks to
   `ExecutePsop`. The engine walks the DAG, dispatches both city tasks in parallel, and handles Negotiation-T when
   needed. `ControlPoint` returns control to the integrator for local aggregation, routing, and clarification decisions.
4. The integrator returns the aggregate as a Task-T artifact and terminal status to WAIMO.
5. Authorization-T and Notification-T are invoked by the integrator at independent business times through
   `ExtensionSender`. They are outside the PSOP DAG and do not share a transport, runtime, or context with workflow
   tasks.

For offline execution, the demo loads AgentCards from the classpath through `WorkbenchAgentCatalog`
and uses a local PSOP fallback only if orchestration-center search or load fails. These are sample substitutes, not
production discovery or disaster-recovery rules. The editable diagram source is
[`docs/diagrams/workflow-engine-surrounding-systems.mmd`](../diagrams/workflow-engine-surrounding-systems.mmd).

---

## 3. Layered Architecture

The SDK is structured in four layers. Each layer builds on the one below; each has a single responsibility and a clear
entry point.

```mermaid
graph TD
    L2["Layer 2 - Orchestration<br/>execute_psop / ExecutePsop<br/>lifecycle, event stream, cancellation, onFinish persistence"]
    L1["Layer 1 - Traversal<br/>WorkflowExecutor<br/>DAG walk, parallel dispatch, context assembly, routing"]
    L0["Layer 0 - Communication<br/>A2ATransport + two facades<br/>WorkflowEngineClient (workflow send) | ExtensionSender (independent operations)"]
    F["Foundation - Decision<br/>ControlPoint<br/>user-implemented business decisions"]

    L2 --> L1 --> L0
    L0 -.-> F
```

### 3.1 Layer 0 - Communication

A2ATransport uses A2A SDK REST, JSON-RPC and gRPC bindings for authentication, delivery and complete response assembly.
WorkflowEngineClient accepts MessageContent and manages task association and negotiation continuation. ExtensionSender
provides independent sendAuthorization and openNotification operations. The implementations are reusable; task,
authorization and notification do not share transport/runtime/context instances. ProtocolResponses assembles artifact
deltas by identity and ReceivedMessage preserves metadata at each level.

### 3.2 Layer 1 - Traversal

**`WorkflowExecutor`** walks the DAG. At each step it selects typed upstream results according to `contextFrom`
(`ContextBuilder`), dispatches subtasks concurrently, applies the step's success policy, and determines the next step
(s). It delegates every *decision* to
`ControlPoint` and every *send* to `WorkflowEngineClient`.

TaskRequest separates current input from workflowInput. ContextBuilder selects complete upstream views and convenience
outputs; it never formats or generates downstream content. The host decides how to consume them.

Step dispatch rules:

- Steps whose predecessors are all satisfied are collected and dispatched in parallel (`CompletableFuture`), so steps at
  the same layer run concurrently.
- Subtasks within a step also run in parallel.
- `ALL_SUCCESS` - all subtasks must succeed.
- `ANY_SUCCESS` - the first successful subtask wins; the rest are cancelled.
- `SELF_LOOP` - the task is handled locally via `onSelfTask`, with no A2A-T message sent to the agent.

### 3.3 Layer 2 - Orchestration

**`ExecutePsop`** is the high-level runner. It wraps the executor with a lifecycle (start / complete / error / close),
event serialization, client-disconnect cancellation, and an
`onFinish` persistence hook. Most integrations use this layer.

---

## 4. Decision Interfaces

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

## 5. A2A-T Extension Model

Task-T: the host generates final content; the engine envelopes and sends it. AgentCard declarations do not trigger
generation.

Negotiation-T:

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

Authorization-T and Notification-T are independent of the DAG. The host generates content and uses dedicated senders;
failure does not affect the workflow. Whitelists only control optional OMC recovery. Subscriptions stay open until the
host closes them.

## 6. Condition Routing

A step's `next` list holds `JumpCondition(step, condition)` entries. The routing rule is:

- **No `next`** - terminal; the step completes the branch.
- **All conditions empty** - unconditional fan-out: dispatch every non-terminal next step in parallel.
- **Has conditions** - conditional: call `ControlPoint.onRoute`, which returns a single `RouteDecision.nextStep`. The
  engine enforces that the returned step is among the declared conditions; an invalid return fails the workflow with an
  error.

This makes conditional branches an N-choose-1 selection and keeps unconditional fan-out as automatic parallel dispatch.

---

## 7. Event Model

Events are emitted to an optional `EventCallback` as stable string types (`EventType`). They are grouped by origin:

- **Runner lifecycle** - `start`, `complete`, `close`
- **Step / task execution** - `step_start`, `step_complete`, `task_request`,
  `task_response`, `task_status_changed`, `route_decision`,
  `workflow_complete`
- **Agent traffic** - `agent_request`, `agent_response`,
  `agent_status_update`, `agent_artifact_update`, `agent_message_event`
- **In-workflow A2A-T extensions** - `negotiation_request`, `negotiation_resolved`,
  `negotiation_failed`
- **Failure** - `error`, emitted on step failure by the executor and on final failure by the runner

`authorization_request`, `authorization_resolved`, and `notification` currently remain reserved
`EventType` constants; the workflow event stream does not emit them. Independent authorization results are handled
through the `ExtensionSender` result, and subscription events through the
`NotificationSubscription` callback.

---

## 8. Interaction Sequences

```mermaid
sequenceDiagram
    participant H as Host callbacks + A2A-T client
    participant E as Workflow engine
    participant A as Remote agent
    E->>H: onTask(TaskRequest + upstream window)
    H->>H: Generate/validate final content
    H-->>E: MessageContent
    E->>A: A2A envelope + unchanged content
    opt INPUT_REQUIRED with valid Propose
        A-->>E: Task status + Negotiation-T Propose
        E->>H: onNegotiation(originalSubmission, received, history)
        H->>H: Validate proposal and generate reply
        H-->>E: Send(MessageContent) or local Stop
        E->>A: Same task/context with final reply content
    end
    A-->>E: Task result / artifacts
    E->>H: onSelfTask(selected complete upstream results)
    H-->>E: TaskResult
```

```mermaid
sequenceDiagram
    participant H as Host + A2A-T client
    participant ES as Independent ExtensionSender
    participant A as OMC
    H->>H: Generate final authorization content
    H->>ES: sendAuthorization(agent, content)
    ES->>A: One-shot authorization
    A-->>H: Independent result, never gates workflow
    H->>H: Generate final subscription content
    H->>ES: openNotification(agent, content, listener)
    ES-->>H: Registered handle
    ES->>A: Independent long-lived stream
    A-->>H: ACK via acknowledgement()
    A-->>H: listener(handle, ReceivedMessage)
    H->>ES: handle.close() on recovery/cancel/shutdown
    ES-->>H: completion() after stream exits
```

## 9. Dependencies

workflow-engine uses A2A Java `1.2.0.Final` (REST/JSON-RPC/gRPC), a matching gRPC runtime,
`net.openan.a2a-t.sdk:a2a-t-core:1.1.0`, Jackson and SLF4J. Pure engine consumers do not transitively receive A2A-T
client/server, LLM, prompt or resources. samples/hosts explicitly depend on a2a-t-client, and OMC receivers additionally
use a2a-t-server. Registry/orchestration discovery is host-owned; RegistryClient/LoadPsop are optional helpers.
Templates and slot schemas come from the pinned SDK jar, not sample resource overrides.

## 10. Design Decisions Summary

Final content is separate from protocol scheduling; hosts do not maintain A2A envelopes. Local multiple outputs and
complete remote evidence enter the selected upstream window without losing metadata or flattening arrays. Business owns
negotiation reply content; the engine owns association, deduplication and bounded waiting. Stop and Abort are distinct.
Independent authorization/notification never gate the workflow; transport runtimes share the same callback contract. Protocol logs
observe actual boundaries with mandatory redaction; see [Integration guide](INTEGRATION_GUIDE.md).
