# Changelog

## [Unreleased]

- Release streaming resources when publisher subscription setup throws synchronously.
- Align published dependency examples with 0.0.2 and refresh neutral architecture terminology.
- Update GitHub Actions to Node 24-compatible action versions.

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
