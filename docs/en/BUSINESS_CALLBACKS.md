# Business callback integration contract

Initial-release API; A2A-T SDK `1.1.0`, A2A Java `1.2.0.Final`. The engine owns DAG scheduling, standard A2A envelopes,
auth, transport and task waiting. The host owns content, schemas, templates, semantic validation and any LLM calls.
Direct OMC and dev's Eastcom forwarding use the same business contracts.

## 1. Callbacks

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

This example deliberately sends ordinary text, not Task-T. Replace the local projection and route/stop policies with
real business behavior. onTask must not send a second network request itself. Import `control.ControlPoint` and
`model.*` from `dev.openan.workflow.engine`, plus `java.util.List/Map` and `java.util.concurrent.CompletableFuture`.

## 2. TaskRequest and dependency input

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

## 3. Final message and complete response

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

## 4. Host-owned A2A-T calls

Pure engine consumers only need a2a-t-core; hosts generating content explicitly depend on a2a-t-client. Create/configure
A2ATClient in the host, not in WorkflowEngineClientConfig or ExecutePsop.

```java
// sdk, data, schema and template selection are owned by host code.
MetadataContent generated = sdk.generateTaskPromptFromDataWithSchema(
                data, schema, StandardTemplates.PRIVATE_LINE_COMPLAINT.uri());
MessageContent outgoing = A2atMessages.from(generated, List.of(new TextPart("Diagnose this line")));
```

Natural-language equivalent: `sdk.generateTaskPromptFromText(text, StandardTemplates.PRIVATE_LINE_COMPLAINT.uri())`,
then use the same A2atMessages.from conversion.

Natural-language generation/validation and all other A2A-T content APIs are called directly on the host's SDK.
A2atMessages.from copies generated metadata and activates exactly its extension; supplied parts stay unchanged. It does
not generate, validate or parse business content. Use StandardTemplates, not invented template URIs.
WorkbenchControlPoint and examples.negotiation.NegotiationStrategy provide runnable SPN implementations.
A2ATInitialization is a sample-host workaround for the pinned SDK's cached JarFile lifecycle, not an engine dependency.

## 5. Negotiation

NegotiationRequest (task, originalSubmission, received, previousExchanges, remainingWait):
task is the original TaskRequest; originalSubmission is the exact initial content; received is the complete current
response; previousExchanges contains only this session's completed Exchange (received, reply); remainingWait is the
remaining local interaction deadline. There are no engine-defined business proposal schemas.

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

Host policy may call validateProposePromptAndDataFilling and typed fromData or natural-language SDK generation. It must
not invent missing customer facts. The sample supplies only requested fields from the current city's input. Map SDK
business exceptions to BusinessFailure (code, safeMessage, safeDetails) in host code. The engine does not recognize
SDK-specific content exception classes.

## 6. Routing, concurrency and failures

RouteRequest (executionId, stepName, workflowInput, currentResults, candidates). Candidates are RouteOption (nextStep,
condition); return RouteDecision.builder ().nextStep (allowed).build (). Unconditional edges fan out without calling
onRoute; conditional selection must be an allowed destination.

Callbacks may run concurrently; do not hold a shared mutable current task/city. Each workflow task activation
(preparation and dispatch combined) is bounded by the client timeout (default sendTimeoutSeconds=600). Routing has a
callback timeout; dispatch/negotiation additionally has its own total wait deadline. Cancellation/timeout prevents late
sends; it does not automatically stop external business/LLM work. Hosts own cancellation of their resources. Synchronous
callback entry points must return promptly; place blocking operations in an asynchronous executor.
Missing/null/exceptional callbacks fail; uncertain sends are not blindly retried.

## 7. Independent extensions

ExtensionSender.sendAuthorization (agentName, finalContent) returns CompletableFuture<SendMessageResult>.
ExtensionSender.openNotification (agentName, finalContent, (handle, received) -> ...) returns the handle immediately.
Use host-generated Authorization-T or Notification-T content with the matching activated extension. The handle is
registered before I/O; early callbacks can call handle.close () without capturing an unassigned variable.
acknowledgement () reports actual ACK or failure; timeout is not a synthesized success. close () requests shutdown;
completion () completes after the stream actually exits. Retain it until recovery, explicit cancellation or host
shutdown, not just workflow completion.

Task, authorization and notification use separate transports/runtimes/contexts. Neither extension success nor failure is
a prerequisite for workflow execution. Whitelist success only controls the OMC's optional automatic recovery behavior.

## 8. Verification and logs

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

Actual startup tests use public simulator credentials and do not shadow SDK schemas/templates. Run main/dev tests
sequentially; fixed local E2E ports must be free. After a local workflow, the demo may observe the first recovery
notification for up to ten seconds; absence of a recovery event does not change workflow success.
See [Integration guide](INTEGRATION_GUIDE.md) for logging and authentication configuration.

## 9. Business-side A2A-T 1.1.0 reference

Content generation, validation, filling and domain decisions belong to the sample host/OMC code, not the engine. Each
role calls the APIs it needs; do not invoke every generation alternative for the same message.

| Business path              | SDK generation and receiving validation                                                                                                                                 | Consumed result                                                              |
|----------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------|
| WAIMO → integrator          | generateTaskPromptFromDataWithSchema → validateTaskPromptAndDataFilling                                                                                                 | WorkbenchTaskInputParser retains filled.data for workflow selection          |
| onTask → either OMC        | generateTaskPromptFromDataWithSchema → validateTaskPromptAndDataFilling                                                                                                 | SpnTaskInput built from filled.data, not raw protocol text                   |
| Either OMC → onNegotiation | generateNegotiationProposePromptFromData → validateProposePromptAndDataFilling                                                                                          | Requested items answered only from the current city's authoritative input    |
| Host Accept → OMC          | generateNegotiationAcceptPromptFromData → validateAcceptPromptAndDataFilling                                                                                            | Merge requested filled fields; validate domain requirements before diagnosis |
| Host Reject / Abort → OMC  | generateNegotiationRejectPromptFromData / generateNegotiationAbortPromptFromData → corresponding validateRejectPromptAndDataFilling / validateAbortPromptAndDataFilling | Validate reason, end the task without diagnosis                              |
| Independent authorization  | generateAuthPromptFromDataWithSchema → validateAuthPromptAndDataFilling                                                                                                 | Apply AuthorizationPolicy from filled.data                                   |
| Independent subscription   | generateNotificationPromptFromDataWithSchema → validateNotificationPromptAndDataFilling                                                                                 | Create NotificationPolicy and keep a separate stream open                    |

For natural-language inputs choose generateTaskPromptFromText (text, templateUri), generateAuthPromptFromText (text,
templateUri), or generateNotificationPromptFromText (text, templateUri). Negotiation also offers phase-specific FromText
and typed FromData alternatives. Structured generation may still call the SDK's configured LLM. Wrap the returned
MetadataContent with A2atMessages.from (generated, List.of (new TextPart ("business summary"))). Preserve full metadata,
including templateUri and negotiationContext; provide nonempty parts. The parts summary is not the formal extension
validation input.

The complaint example negotiates the canonical business fields 任务对象 and 任务上下文. The former identifies this
city's access port; the latter retains classification, OSS incident ID, time and details. Only unresolved fields are
requested when trusted partial data exists. If the SDK rejects the whole input, none of its partial fields are trusted.
The current proposal uses AND: all requested fields are required. Propose extraction uses items (nonempty field-name
array) and nullable relationship; Accept extraction dynamically requires the requested nonblank fields. These SPN domain
checks are sample rules, not generic engine constraints.

Replies must match A2A task/context IDs and negotiation id/round/maxRounds. Formal text comes from Negotiation-T
metadata. Validated replies are consumed once; malformed, incomplete, foreign-city or cross-negotiation replies never
run diagnosis. NegotiationStrategy.mayProvideField (task, fieldName) is the host disclosure-policy hook: false produces
SDK Reject. Missing authoritative information produces SDK Abort, not invented values. This policy is separate from
Authorization-T recovery permissions.

Do not reuse subscription-request generation/validation for recovery events: diagnosis results, authorization receipts
and recovery events are OMC business outputs. RecoveryNotification checks the SPN result fields in formal Notification-T
artifact metadata before closing the subscription: terminal state, task/incident IDs, port, plan and execution outcome.
Both successful and failed finished recoveries are terminal, without treating failure as success. A name, summary, ACK
or not-started plan alone never closes the stream. Invalid events remain observable by the callback. City1 uses the
actual validated port and OSS incident ID. A healthy City2 need not emit a recovery result. Authorization/subscription
outcomes remain independent of workflow execution.

EmbeddedA2AServerTest covers normal/Accept filled data, invalid formal text, missing fields, cross-negotiation replies,
Reject and Abort. NegotiationStrategyTest covers city isolation and host Abort/Reject policy. RecoveryNotificationTest
covers complete success/failure and malformed/incomplete events. SpringSpnDemoE2ETest and dev's
SpringSpnDemoOrderE2ETest cover full input, City1 missing input and both cities missing input; SpnCrossCityE2ETest
checks the recovery lifecycle. Tests use actual SDK pipelines with a strict fixture-only offline LLM provider, not a
live-model semantic evaluation or real-OMC certification.

### Authorization format and negotiation keys (SDK 1.1.0)

ADD uses consecutive numbered lines, labeled values and full-width commas; multiple rules use newlines, not semicolons.
Example:
`1. 业务场景是业务投诉诊断，处置类型是业务抢通，操作名称是隧道调优，有效期是2026-06-01~2030-06-18`. Validity is an
inclusive ISO-date range or `永久生效`. Parse every SDK-validated rule before replacing the whitelist; malformed rules
leave it unchanged. Authorization uses exact scenario/disposal/action matches, never fuzzy matching.

The sample stores one in-memory whitelist with a fixed demonstration ID. DELETE accepts only
`1. 策略标识是<active-policy-UUID>`. QUERY requires an omitted or empty policy list and returns all active rules.
MODIFY, filtered queries and batch/conditional deletion require an integrating policy store; unsupported conditions are
rejected, never silently ignored. These are sample business limits, not limitations of the A2A-T SDK.

After SDK Propose semantic validation, the sample preserves literal numbered item names from the current Chinese typed
information template. LLM paraphrases of descriptions must not rename an item or broaden disclosure. Replies use only
the current city's authoritative input; unknown/missing data causes Abort and a host disclosure denial causes Reject.
This handling belongs to sample business code, not the workflow engine.

Notification requests use the complete failure-reason field name `业务抢通方案执行失败原因`, required when execution
fails. SDK rejection logs include its code, slot errors and extracted parameter keys; validation is not bypassed.
Authorization and subscription failures still do not block the workflow.
