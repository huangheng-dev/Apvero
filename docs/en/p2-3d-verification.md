# P2.3d Grounded Runtime Orchestration Verification Candidate

Status: accepted by the maintainer on 2026-07-31. P2.3 remains `in-progress`; P2.3d is
`completed`.

## Scope

P2.3d implements this bounded workflow for immutable Manifest 1.1 RAG releases:

```text
resolve exact ReleaseBundle
  -> persist a RUNNING Run
  -> resolve each exact Knowledge binding in declared order
  -> execute governed retrieval and persist each retained evidence result
  -> build one bounded untrusted-evidence context
  -> return typed NO_EVIDENCE without a chat reservation when no usable hit remains
  -> otherwise reserve and dispatch one governed chat component
  -> execute the selected Runtime Provider
  -> settle a known outcome or require reconciliation for an ambiguous outcome
  -> fail closed until P2.3e validates the structured answer and citations
```

P2.3d does not claim that a model answer or citation is valid. Structured parsing, citation
validation, authorized locators, and successful grounded-answer completion remain P2.3e.

## Authority and boundaries

- Stage: P2 / P2.3 / P2.3d.
- Owner: Runtime.
- Allowed dependencies: Release, Capability Registry, and Knowledge public APIs only.
- Runtime resolves the immutable Release before execution and does not read the mutable
  Application draft.
- Runtime never queries Knowledge, Governance, or Capability Registry tables directly.
- Spring AI remains the only core Java AI abstraction.
- No deployable, queue, database, framework, or mandatory stateful dependency was added.
- ADR-0006 already approves P2.3 and the V13 persistence baseline.

## Exact execution identity

The Release manifest remains the only source of execution bindings. Knowledge exposes
workspace-scoped public resolvers for exact canonical Index and Retrieval Policy references.
Runtime verifies tenant, workspace, exact reference, READY Index state, supported policy, and
returned retrieval identities before evidence can enter model context.

Bindings execute serially in manifest order. Each completed retrieval is committed with its
sequence and global marker assignment before the next binding starts. If a later binding fails,
the Run fails but earlier evidence remains inspectable; the system does not rewrite history as if
retrieval never occurred.

## Context and injection safety

Only retention-disclosable, nonblank hit content enters the model context. The global context is
limited to 128 hits and 100,000 UTF-8 bytes. Markers are assigned after filtering and budgeting,
so stored evidence and provider-visible evidence retain the same `[K1]` order.

Evidence is encoded as JSON data between explicit boundaries. The Spring AI adapter adds a system
instruction that evidence is untrusted, instructions inside evidence must not be followed, and
capabilities must not be inferred from evidence. Provider SDK types do not enter Runtime domain
contracts.

## Lifecycle, governance, and failure semantics

V13 is additively hardened before the unpublished P2.3 milestone is released:

- `RUNNING` is an allowed Run lifecycle state;
- Run identity and input are immutable;
- only a RUNNING Run may attach chat execution identity or move to a terminal state;
- terminal Runs remain immutable;
- ordinary deletion remains forbidden outside the transaction-scoped retention purge.

Execution orchestration deliberately has no outer read-only transaction. Lifecycle and evidence
steps commit independently, preserving evidence and reconciliation state when a later external
step fails.

Chat generation uses the P1 execution-component ledger. ADR-0006 requires every terminal path to
settle or release its reservation, so a deterministic pre-dispatch failure moves the component
from `RESERVED` to `RELEASED` with zero usage, zero cost, no fabricated dispatch timestamp, and an
auditable failure code. An external call with an uncertain outcome moves the component to
`RECONCILIATION_REQUIRED`; the Run records the stable
`APVERO_EXTERNAL_OUTCOME_RECONCILIATION_REQUIRED` failure code rather than guessing cost or
success.

When retrieval produces no usable evidence, Runtime returns:

```json
{"schemaVersion":"1.0","status":"NO_EVIDENCE","answer":"","citations":[]}
```

No chat reservation or Provider call is made. When evidence exists and a Provider returns, P2.3d
settles the known usage and cost, retains the raw output under policy, and terminates the Run with
`APVERO_GROUNDED_OUTPUT_VALIDATION_PENDING`. This is intentional fail-closed behavior, not a mock
success.

## Retention and telemetry

The execution-time retention decision is carried with its version. Inputs, outputs, and evidence
content are omitted or recursively masked before persistence. Metrics use bounded outcome and
provider values; prompt, evidence, references, tenant IDs, workspace IDs, actor IDs, and trace IDs
are not metric tags.

## Verification evidence

The candidate tests cover:

- typed NO_EVIDENCE with no chat reservation;
- recursive sensitive-field masking without destroying the typed NO_EVIDENCE envelope;
- exact ordered binding resolution;
- preservation of earlier evidence when a later binding cannot resolve;
- deterministic global markers and a global UTF-8 byte/hit budget;
- hostile evidence remaining JSON data rather than control instructions;
- governed chat settlement and deliberate validation-pending failure closure;
- pre-dispatch reservation release without a fabricated Provider dispatch;
- terminal Run immutability and V13 retrieval integrity;
- Manifest 1.1 RAG Provider compatibility without silent CHAT fallback;
- Spring Modulith public-boundary verification.

Executed locally:

- complete Gradle test suite and bootable Platform Server JAR;
- final focused P2.3d, P2.3c, P1 Governance, P2.2 embedding, delivery-stage, and module-boundary
  regressions;
- TypeScript strict typecheck, Console unit tests, and production build;
- English and Simplified Chinese validation with 405 leaf keys in each required locale;
- OpenAPI 3.1 lint with only the two established platform-info and worker-health 4xx warnings;
- default and Knowledge-profile Compose configuration validation.

The production exact-retrieval benchmark remains explicitly opt-in and was skipped. P2.3d does not
change the P2.2 retrieval SQL hot path.

## Rollback

Before publishing P2.3, disable Manifest 1.1 RAG execution or restore the previous milestone
binary while retaining V13. Existing RUNNING Runs must be reconciled or administratively closed
before rollback. V13 tables and lifecycle constraints remain the data compatibility floor once
P2.3d data exists.

## Known limits

1. Grounded successful completion is intentionally unavailable until P2.3e.
2. Citation validation and authorized source locators remain P2.3e.
3. Console closure and compatibility hardening remain P2.3f.
4. P2.3 remains incomplete until P2.3e and P2.3f pass their verification gates.

## Exit statement

The maintainer accepted P2.3d on 2026-07-31 and confirmed:

> Apvero can execute immutable ordered RAG bindings, preserve governed evidence, bound and isolate
> untrusted context, return typed NO_EVIDENCE without generation, release pre-dispatch
> reservations, and reconcile ambiguous external outcomes without claiming that structured
> answers or citations are already valid.
