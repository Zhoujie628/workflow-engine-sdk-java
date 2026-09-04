# Changelog

## [Unreleased] — 1.0.0 release candidate

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
- Add local missing-port SpringSpnDemo negotiation tests for direct and dev Order; real LLM/platform/OMC validation
  remains separate.
- Refresh bilingual callback, architecture and integration contracts. No older SDK compatibility layer.

### Release hardening

- Propagate workflow cancellation and close execution-owned resources exactly once.
- Anonymize orchestration access tokens and bound registry response deadlines.
- Preserve multi-value results from arbitrary terminal workflow nodes.
- Map pre-task failures from the standard A2A error envelope while keeping post-creation failures in task state.
- Publish a compiled host integration example and require sample regression tests.
- Keep documentation host-neutral; sample class and AgentCard identifiers remain unchanged.

This repository prepares the initial 1.0.0 SDK release. Earlier internal development notes are not published API
contracts or evidence of a Maven release. Release publication/tagging is a separate, explicitly authorized operation.


### Eastcom adapter (dev only)

- Support Order forwarding, Agent-to-NE routing and separate platform/OMC authentication.
- Require both direct and Order simulator regression with the licensed vendor jar.
