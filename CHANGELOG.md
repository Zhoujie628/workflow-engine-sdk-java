# Changelog

## Unreleased — business callback boundary

- Use published A2A-T 1.1.0 from Maven Central; remove SDK source checkout/install from CI; move content generation, validation, templates and SDK initialization to the host.
- onTask returns final MessageContent; onNegotiation returns Send/Stop. Remove engine profiles and content handlers.
- Preserve complete ReceivedMessage and metadata layers alongside deterministic convenience outputs; keep local nested multi-output values.
- Deduplicate negotiation rounds, separate resource budgets from protocol rounds, and suppress late sends after timeout/cancellation.
- Separate authorization/notification lifecycle from workflow outcomes; never synthesize a subscription ACK.
- Observe serialized HTTP/JSON-RPC, real gRPC metadata/protobuf and dev vendor SDK traffic with mandatory redaction and bounded SSE.
- Add local missing-port SpringSpnDemo negotiation tests for direct and dev Order; real LLM/platform/OMC validation remains separate.
- Refresh bilingual callback, architecture and integration contracts. No older SDK compatibility layer.

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres
to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

> Entries below older release headings describe those releases and are not current API contracts.

### Added

- Client TLS options for custom CA, mTLS client certificate/private key, encrypted PKCS#8 keys, and CRL checking
- Configurable bounded executors for client sends and Spring Boot server request handling
- Configurable Notification-T acknowledgement timeout and protocol-log body controls
- Pinned, unreleased A2A-T SDK revision with real offline SDK end-to-end coverage
- Explicit `NotificationSubscription` lifecycle/heartbeat handle and stable SDK-event mapper
- Structured Authorization-T and Notification-T generation, server validation, and whitelist/recovery sample callbacks
- WAIMO Task-T parsing plus two-city parallel diagnosis and exact-once merged workbench result

### Changed

- TLS configuration is now per client; it no longer changes JVM-wide hostname-verification properties
- Credential login uses the same TLS policy as agent traffic and encrypted credentials now fail closed
- AgentCards that declare security requirements now fail closed when neither matching credentials nor an `AuthProvider` is configured
- `securitySchemes` no longer implies non-empty `securityRequirements`; an `AuthProvider` can be the sole authentication source
- Notification-T distinguishes acknowledgement timeout from real stream failure and keeps its lifecycle independent from
  workflow request transports
- Workflow execution validates graph targets and cycles up front and waits only for activated predecessors at joins
- Protocol headers are redacted by default; sensitive values require an explicit diagnostic opt-in
- Agent clients and transport executors are reused and closed through their owning runtime
- Task-T, Authorization-T, and Notification-T use independent transport/runtime/context instances
- Negotiation-T uses the latest stateless content APIs and canonical `negotiationContext`; removed SDK state-machine APIs are not used
- SDK `.env` paths remain instance-local instead of being copied into JVM-wide system properties
- Extension subscription and task-control capabilities are explicit interface contracts instead of
  default methods that fail at runtime

### Fixed

- Task success is derived from the A2A task state instead of non-empty response text alone
- Conflicting authentication headers from `AuthProvider` and credential configuration now fail fast
- Extension URI matching now uses exact URI path segments instead of substring matching
- Notification callback implementations can no longer silently discard the supplied callback
- Header construction is split into isolated auth, credential, and extension contributors with conflict detection
- Protocol generation/validation failures now fail closed instead of sending raw text under an A2A-T URI
- Authorization-T rejection stops the independent authorization operation; add/modify/delete/query operations follow the SDK template contract
- Notification-T failed/canceled/rejected acknowledgements fail the subscription and callback failures cannot prevent cleanup
- Workflow joins merge all predecessor outputs deterministically and execute exactly once after active predecessors complete

## [1.0.0] - 2026-07-28

First public release. The SDK ships a clean transport-facade architecture with single-responsibility decision interfaces
and full A2A-T extension support. Full A2A-T extension support and multi-protocol transport.

### Added

- `A2ATransport`: shared wire layer owning the A2A client runtime, auth manager, agent-card map, and streaming event
  extraction
- `ExtensionSender` / `DefaultExtensionSender`: pre-positioning facade for one-shot Authorization-T and long-lived
  Notification-T SSE subscriptions
- `ControlPoint` / `ExtensionSender` split: flow decisions (`onTask` / `onSelfTask` / `onRoute` / `onNegotiation`) on `ControlPoint`; Authorization-T / Notification-T pre-positioning on `ExtensionSender`
- `NegotiationStrategy`: pluggable clarification strategy injected into
  `DefaultControlPoint`
- `SELF_LOOP` step type for local task handling without an A2A-T message
- `ANY_SUCCESS` step policy with early cancellation of remaining subtasks
- Parallel DAG step dispatch and context assembly (`ContextBuilder`)
- `EventType` constants covering runner lifecycle, step/task execution, agent traffic, and A2A-T extension events
- `ExecutePsop.Builder`: fluent builder with event stream, lifecycle bracket, and `onFinish` persistence hook
- `A2ATExtension` enum encapsulating all extension URIs (no hardcoded strings)
- `DefaultExtensionSender` prompt-generation dispatch (Task-T via the A2A-T SDK; Authorization-T / Notification-T /
  Negotiation-T reserved for SDK support)
- [Design Document](docs/en/DESIGN.md) architecture document

### Changed

- `DefaultWorkflowEngineClient` is now a facade over `A2ATransport`, owning only the workflow send path (Task-T prompt
  generation, Negotiation-T auto-loop, event callback, ControlPoint/ExtensionCallback wiring)
- Pre-positioning sends moved from `WorkflowEngineClient` to `ExtensionSender`
- `ExtensionRegistry` auto-registers only Task-T and Negotiation-T (in-workflow handlers); Authorization-T /
  Notification-T are pre-positioning operations with request and subscription lifecycles respectively

## [0.3.0] - 2026-07-25

### Added

- `EnvFileLoader`: loads `.env` file entries into system properties, bridging A2A-T SDK's internal `.env` loading with
  `CredentialCrypto` and other components that read from `System.getenv()` / `System.getProperty()`
- `ProtocolLogger`: dedicated `PROTOCOL` SLF4J logger for full protocol-level request/response dumps (headers + body),
  enabling verification against real network captures
- `ExtensionInterceptor`: metadata-aware `A2A-Extensions` header injection; only advertises extensions actually present
  in the current message metadata
- `AuthProvider` interface: custom authentication for non-standard auth mechanisms (SSO, API keys, custom headers)
- `sendExtensionMessage()` on `WorkflowEngineClient`: one-shot extension messages for Authorization-T pre-positioning
  and Notification-T subscription
- `ExecutePsop.Builder`: fluent builder API replacing the 14-argument static
  `execute()` method
- AgentCard Jackson module for deserializing AgentCard JSON with security scheme normalization
- HTTPS/TLS support with configurable verification and CA trust store
- AES-GCM credential encryption (`enc:iv:ciphertext` format) with
  `A2AT_CRED_KEY` environment variable
- SSE timeout configurable via `sendTimeoutSeconds` (default 600s)
- Complete Chinese and English documentation:
    - [Integration Guide](docs/en/INTEGRATION_GUIDE.md) / [中文](docs/zh/INTEGRATION_GUIDE.md)
    - [API Reference](docs/en/API_REFERENCE.md) / [中文](docs/zh/API_REFERENCE.md)
    - [Developer Guide](docs/en/DEVELOPER_GUIDE.md)
    - [Contributing Guide](CONTRIBUTING.md)

### Changed

- `A2A-T extension content placed in artifact `metadata` (not `parts.text`)
  per protocol specification in `调用过程.md`
- Negotiation-T metadata URI aligned with AgentCard declaration (uppercase, no `/v1` suffix)
- Negotiation text passed to SDK's `startNegotiation` uses short request text, not full task input
- AgentCard is now strongly typed (`org.a2aproject.sdk.spec.AgentCard`)
  throughout the codebase; `List<?>` and `Map` replaced with typed equivalents
- Default SSL verify changed from `false` to `true` for production safety
- Default send timeout increased to 600 seconds (10 minutes)
- `DEVELOPER_GUIDE.md` moved from project root to `docs/`

### Fixed

- Authentication 401 failures caused by `A2AT_CRED_KEY` not being available to `System.getenv()` when `.env` file is
  used (loaded by A2A-T SDK internally but not propagated to OS environment)
- All four extensions no longer injected into every request's
  `A2A-Extensions` header; only active extensions are advertised
- `chunked transfer encoding, state: READING_LENGTH` errors after terminal SSE events downgraded to DEBUG level
  (expected behavior)
- SDK `receiveNegotiation` "Unsupported negotiation type: FULFILLMENT"
  warning downgraded to DEBUG (known SDK limitation, fallback works)

### Removed

- 14-argument static `ExecutePsop.execute()` method (replaced by Builder)
- `AgentCardMapper` (replaced by Jackson + `AgentCardJacksonModule`)
- Redundant blank lines in method bodies
- `AuthorizationTHandler` and `NotificationTHandler` (these are now pre-positioning operations done via
  `sendExtensionMessage`, not part of the workflow extension handler chain)
