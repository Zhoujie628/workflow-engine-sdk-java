# Changelog

## [Unreleased]

- Add `ExtensionSender.sendTask(agentName, content)`: a one-shot task dispatch independent of any
  workflow. The host supplies final content; the engine envelopes, authenticates, sends and returns
  the complete `SendMessageResult`. No `ControlPoint` is invoked and no negotiation is attempted --
  `INPUT_REQUIRED` is returned to the caller as-is. Each call uses a fresh context, matching
  `sendAuthorization`.
- Increase the default Notification-T acknowledgement timeout from 5 seconds to 5 minutes and expose the sample Spring
  setting as `a2a.notification-ack-timeout-seconds` /
  `A2A_NOTIFICATION_ACK_TIMEOUT_SECONDS`.

## [0.0.5] — 2026-09-09

### Breaking: per-edge conditional routing

- `onRoute` now evaluates one conditional edge at a time: `RouteRequest` describes a single edge (step, condition,
  upstream window) and the callback returns `RouteDecision.allow()` /
  `deny()` for that edge. Unconditional edges always run and bypass the callback; allowed conditional edges activate in
  parallel with them. This replaces the previous N-choose-1 candidate selection (`RouteDecision.nextStep` and
  `RouteRequest.candidates` are removed)
  and fixes mixed-edge steps, where all unconditional edges were not previously guaranteed to activate.
- Workflow validation now rejects duplicate outgoing targets on one step, null edges and blank targets.
- After all route decisions succeed, `route_decision` is emitted once per outgoing edge, including unconditional edges,
  instead of once per step. A callback failure emits the workflow error and no partial per-edge decisions.

## [0.0.4] — 2026-09-08

- Make the Spring Boot A2A server auto-configuration opt-in: server beans are registered only
  when `a2at.server.enabled=true` (previously on by default). Host applications that expose A2A
  endpoints must now set the property explicitly.
- Open `A2ASlashActionAliasController` for host-side registration: public, non-final class with
  a public constructor, so applications outside the starter package can instantiate, replace or
  proxy it (`@ConditionalOnMissingBean` replacement was previously unreachable from host code).

## [0.0.3] — 2026-09-07

- Release streaming resources when publisher subscription setup throws synchronously.
- Align published dependency examples with 0.0.2 and refresh neutral architecture terminology.
- Update GitHub Actions to Node 24-compatible action versions.
- Add optional slash-style alias endpoints for A2A actions, disabled unless
  `a2at.server.slash-action-aliases-enabled=true`.

## [0.0.2] — 2026-09-07

- Publish the main-branch baseline under `net.openan.workflow.sdk`, including the parent POM,
  workflow-engine and spring-boot-starter. This tag has the same source tree as 0.0.1.

## [0.0.1] — 2026-09-04

- Use published A2A-T 1.1.0 from Maven Central; remove SDK source checkout/install from CI; move content generation,
  validation, templates and SDK initialization to the host.
- onTask returns final MessageContent; onNegotiation returns Send/Stop. Remove engine profiles and content handlers.
- Preserve complete ReceivedMessage and metadata layers alongside deterministic convenience outputs; keep local nested
  multi-output values.
- Deduplicate negotiation rounds, separate resource budgets from protocol rounds, and suppress late sends after
  timeout/cancellation.
- Separate authorization/notification lifecycle from workflow outcomes; never synthesize a subscription ACK.
- Observe serialized HTTP/JSON-RPC, real gRPC metadata/protobuf and dev vendor SDK traffic with mandatory redaction and
  bounded SSE.
- Add missing-input negotiation and independent transport-path regression tests; live model and production endpoint
  validation remains separate.
- Refresh bilingual callback, architecture and integration contracts. No older SDK compatibility layer.

### Release hardening

- Propagate workflow cancellation and close execution-owned resources exactly once.
- Anonymize orchestration access tokens and bound registry response deadlines.
- Preserve multi-value results from arbitrary terminal workflow nodes.
- Map pre-task failures from the standard A2A error envelope while keeping post-creation failures in task state.
- Publish a compiled host integration example and require sample regression tests.
- Keep documentation host-neutral; sample class and AgentCard identifiers remain unchanged.

Published Maven artifacts use the version selected by the release tag. The default source-build
`revision=1.0.0` is a development version, not the version to use when consuming Maven Central artifacts.
