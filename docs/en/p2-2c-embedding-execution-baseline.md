# P2.2c Governed Embedding Execution — Implementation Baseline

Status: proposed implementation baseline; no P2.2c business code has been written

Target: P2 / P2.2c

Authority: ADR-0006, the maintainer-approved P2.2 plan, and implemented P2.2a/P2.2b contracts

Reasoning level: high

## 1. Outcome and boundary

P2.2c delivers one reusable governed Embedding batch primitive:

```text
exact Workspace and immutable Embedding Route
  -> deterministic ordered inputs
  -> provider-neutral cost quote and replay policy
  -> idempotent Governance reservation/component
  -> durable DISPATCHED transition
  -> one Spring AI EmbeddingModel call
  -> strict output validation
  -> atomic Knowledge Entry batch persistence
  -> idempotent component and parent settlement
  -> safe replay, settlement-only recovery, or explicit reconciliation
```

This is the execution seam used by P2.2d. P2.2c adds no scheduled Build runner, Build REST API,
Build-state progression, publication, retrieval, Application binding, frontend work, or live
product claim.

Completion requires that one persisted batch identity cannot produce an unsafe duplicate provider
dispatch, duplicate Entry, duplicate Governance component, or duplicate Apvero ledger charge.

## 2. Change declaration

| Item | Decision |
|---|---|
| Stage | P2 / P2.2, `in-progress` |
| Modules | `capability-registry`, `governance`, `knowledge` |
| Dependencies | Existing approved graph only |
| REST / JSON Schema | No change |
| Java module API | Additive provider-neutral quote/replay contracts |
| Migration | None; V9 and V10 are sufficient |
| Stateful dependency / deployable | None |
| AI abstraction | Spring AI 2.0 only |
| Python worker | Unchanged and uninvolved |
| Exposure | Internal; Knowledge and pages remain disabled/non-live |
| Rollback | Previous compatible binary; retain V9/V10 rows |

This slice changes no invariant, dependency rule, REST contract, release semantic, security policy,
or technology baseline. The additive Java API realizes behavior already authorized by ADR-0006.

## 3. Pre-coding corrections

P2.2a froze ordered execution inputs/outputs but did not expose two facts required by the approved
recovery protocol.

### 3.1 Pre-call cost quote

Governance needs estimated units, cost and currency before billable dispatch. Capability Registry
adds a provider-neutral quote instead of letting Knowledge read Model tables:

```text
quote(workspaceId, routeId, estimatedInputUnits)
  -> exact route snapshot
  -> estimated cost micros
  -> currency
  -> replay policy
```

Cost uses the immutable Route Model input price with overflow-safe round-up:

```text
ceil(estimatedInputUnits * inputCostMicrosPerMillion / 1_000_000)
```

P2.2c retains the implemented USD-only baseline; it does not invent multi-currency support.

### 3.2 Replay policy

Adapter replay safety must be explicit and provider-neutral:

```text
SAFE_REPLAY
RECONCILIATION_REQUIRED
```

The default is `RECONCILIATION_REQUIRED`. Only
`apvero-deterministic-embedding@1.0.0` is `SAFE_REPLAY` in P2.2c. The generic
OpenAI-compatible adapter never claims provider idempotency and does not blind-retry an ambiguous
paid call.

### 3.3 Provider request identity timing

A provider request ID usually exists only after the response. Governance must therefore:

1. mark `DISPATCHED` with a null provider identity before I/O;
2. idempotently enrich the dispatched row after a validated response;
3. settle only after Knowledge Entries are durable;
4. never delay the initial dispatch transition until after the call.

V10 already permits `DISPATCHED -> DISPATCHED`, so this needs no migration.

## 4. Truth and transaction boundaries

| Concern | Owner |
|---|---|
| Route/profile/readiness/adapter/quote | Capability Registry |
| Secret resolution | Governance Secret API, consumed inside Capability Registry |
| Reservation/component/settlement | Governance |
| Batch membership and Entries | Knowledge |
| Build leases/lifecycle | P2.2d |
| External response | Untrusted until validated and durably represented |

No transaction crosses external I/O:

```text
TX-A: quote + admit + RESERVED component
TX-B: RESERVED -> DISPATCHED
Spring AI/provider call without a database transaction
TX-C: validate and atomically insert the complete Entry batch
TX-D: enrich provider identity and settle component/parent
```

If TX-C commits and TX-D fails, recovery settles only. If TX-B commits and TX-C does not, replay
depends on the replay policy resolved from the exact pinned immutable Route.

## 5. Deterministic Spring AI adapter

Identity: `apvero-deterministic-embedding@1.0.0`

- dimension `256`;
- L2 normalization;
- in-process, credential-free and `SAFE_REPLAY`;
- `apvero-utf8-byte-v1` units marked `ESTIMATED`;
- zero USD cost;
- development/CI orchestration proof, never a semantic-quality claim.

Frozen algorithm:

1. preserve the exact Java String without trimming, Unicode normalization or line-ending changes;
2. encode UTF-8;
3. for block ordinals `0..7`, SHA-256 the UTF-8 bytes of
   `apvero-deterministic-embedding@1.0.0`, NUL, big-endian 32-bit block ordinal, NUL and the exact
   input bytes;
4. concatenate eight digests into 256 signed-byte-derived components;
5. map each signed byte `b` to the non-zero double `(b + 0.5d) / 128d`;
6. accumulate norm in ordinal order using `StrictMath`, reject non-finite/zero norm, normalize in
   ordinal order and convert once to IEEE-754 float32;
7. preserve request order and consult no locale, timezone, randomness, filesystem or network.

Golden tests freeze all 256 float bit patterns, vector SHA-256, L2 tolerance and ordering for
English, Simplified Chinese, mixed language, combining characters, emoji, CRLF and long input.
Behavior changes require a new adapter version.

The adapter implements Spring AI `EmbeddingModel` and Apvero calls `call(EmbeddingRequest)`.
`dimensions()` is never used for discovery because Spring AI's default may invoke the model.

## 6. OpenAI-compatible adapter

The adapter is internal to Capability Registry under `adapters.springai` and manually constructs
Spring AI `OpenAiEmbeddingModel` from the immutable Route:

- exact Base URL, Secret Reference, Model key, dimension and timeout;
- `maxRetries(0)` so retries cannot bypass Governance;
- ordered batch inputs and response-index validation;
- no auto-configured global provider bean;
- no plaintext key in application configuration.

The Secret is resolved immediately before construction. The owned `ResolvedSecret` character
buffer is closed and zeroed after model construction; Spring AI currently requires a String API
key, so the unavoidable short-lived immutable copy cannot be zeroed in place. That copy is never
cached, returned, logged, tagged or persisted and is scoped to one call.

A local HTTP stub proves path, authorization, model, dimension, ordered inputs, timeout,
response-index mapping, usage extraction and safe error normalization. CI needs no paid credential.
Custom provider-idempotency configuration is deferred until it has a separate tested contract.

## 7. Capability Registry behavior

The live `EmbeddingCapability` service:

1. resolves Route, Model and Provider with tenant/workspace predicates;
2. requires exact `name@N`, `EMBEDDING`, `PUBLISHED`, enabled Model/Provider, matching Model
   capability and valid immutable profile;
3. requires an available Secret for real providers and no Secret for deterministic local;
4. quotes cost before Governance admission;
5. selects an internal adapter without exposing provider types;
6. rejects unsupported/disabled execution with stable codes;
7. invokes the adapter outside a transaction;
8. revalidates route/execution identity, count, order, item/digest mapping, dimension, finite values
   and non-zero norm at the facade;
9. preserves `ACTUAL`, `ESTIMATED` and `UNAVAILABLE` usage quality honestly;
10. calculates cost from actual units when available, otherwise from the versioned estimate.

Readiness performs no billable probe. Real execution remains explicit opt-in.

## 8. Governance behavior

`ExecutionGovernance.admit(ExecutionReservationRequest)` becomes live for approved combinations
while the P1 single-CHAT API remains compatible.

Admission atomically:

1. requires full Workspace scope and takes the existing advisory lock;
2. evaluates Workspace and exact Route limits;
3. skips Application policies for non-Application subjects without null comparison;
4. finds an equal subject/component idempotency identity or inserts one reservation plus components;
5. returns the same admission for an equal repeat;
6. rejects a conflicting repeat with a stable code.

Transitions:

- equal `RESERVED -> DISPATCHED` repeats are no-ops;
- provider identity enrichment cannot replace a different non-null identity;
- terminal settlement is compare-and-set and equal repeats are no-ops;
- conflicting settlement fails closed;
- parent actual cost is the overflow-safe sum of terminal components;
- parent succeeds only when all components succeed and fails when any fails;
- reconciliation remains explicit and does not masquerade as zero-cost success/failure.

The stale reconciler may fail/release never-dispatched reservations. It must not settle dispatched
work at zero; unsafe stale dispatch becomes `RECONCILIATION_REQUIRED`.

## 9. Knowledge batch primitive

Add internal `KnowledgeEmbeddingBatchExecutor`; it is neither a scheduler nor public catalog. Its
input is full `WorkspaceScope`, exact persisted Build, deterministic batch ordinal and exact ordered
Chunk identities.

Before dispatch it:

1. reloads Build Route ID/reference/profile;
2. requires the Build to be in the persisted `EMBEDDING` step without advancing it;
3. loads only selected Build revisions and missing Entries in the same scope;
4. orders source-set ordinal, document ordinal, chunk ordinal and Chunk UUID;
5. recomputes each content digest;
6. estimates units with `apvero-utf8-byte-v1`;
7. rejects one oversized Chunk and stops before aggregate-unit/item-count limits;
8. derives idempotency from Build ID, batch ordinal, Route and canonical ordered
   `(Chunk ID, content digest)` manifest.

After validation, one Knowledge transaction inserts the complete batch with deterministic entry and
batch ordinals, exact lineage, normalized-input digest, float32 vector digest and Route reference.
Equal existing Entries are accepted; differing vector/digest/ordinal/lineage/Route is a conflict.

P2.2c tests call this primitive with persisted fixtures. P2.2d later claims Builds, selects the next
batch and advances Build state.

## 10. Crash and retry matrix

| Durable point | Recovery |
|---|---|
| Before reservation | Recompute and admit normally |
| Component `RESERVED` | Reuse admission; dispatch once |
| `DISPATCHED`, no Entries, `SAFE_REPLAY` | Replay with the same identity |
| `DISPATCHED`, no Entries, unsafe | Require reconciliation; no second call |
| Equal complete Entries, non-terminal component | Settle only |
| Partial Entry batch | Integrity failure; do not fill around it |
| Component succeeded and equal Entries exist | Return completed idempotently |
| Terminal component but Entries differ/missing | Ledger/artifact inconsistency |

This is not “exactly once.” It is at-least-once local execution, idempotent durable effects and
explicit ambiguity for unsafe external dispatch.

## 11. Implementation map

### Capability Registry

- add Spring AI model/OpenAI dependencies only to `modules/capability-registry`;
- add provider-neutral quote/replay records in the public module package;
- implement resolution/quote/facade validation in `.internal`;
- implement adapters in `.internal.adapters.springai`;
- add golden-vector resources and HTTP-stub tests;
- forbid Spring AI/provider imports in public APIs.

### Governance

- activate component overloads in `DefaultGovernanceCatalog`;
- extend scoped persistence with find-by-idempotency, compare-and-set dispatch, identity enrichment,
  settlement and reconciliation;
- preserve P1 behavior and add regression tests;
- keep Knowledge orchestration out of Governance.

### Knowledge

- add scoped deterministic batch selection and atomic Entry-batch persistence;
- add internal executor and normalized error mapping;
- add no Controller, runner, Build catalog or publication logic.

### Platform

- add Testcontainers crash-boundary and two-workspace suites;
- reuse explicit real-provider enablement;
- keep Compose dependencies unchanged;
- produce matching English/Chinese verification evidence during implementation.

## 12. Security, errors and telemetry

Stable errors cover Route/profile/readiness, adapter/Secret/endpoint, input/digest/limits,
provider timeout/rejection, output mapping/vector validation, budget/rate denial, idempotency and
settlement conflicts, ambiguity, partial Entry batch and scope/lineage mismatch.

Errors expose stable `APVERO_*` codes and safe metadata only. Provider bodies, source text, vectors,
Secrets, Base URLs and cross-workspace existence are never exposed.

Metrics cover calls/latency/outcome, item/unit counts, dimension, algorithm, usage quality,
admission/dispatch/settlement, replay, settlement-only recovery and reconciliation. Only bounded
low-cardinality tags are allowed. Tenant/workspace/Build/Route/Chunk IDs, request IDs, content and
URLs are forbidden labels. Spring AI observations supplement typed records; content export stays
disabled.

## 13. Verification gate

1. Modulith/ArchUnit preserve dependencies and forbid provider types in public packages.
2. Full-vector golden tests pass across process, locale and timezone variants.
3. Estimator and deterministic batching limit tests pass.
4. HTTP stub proves real-adapter protocol, zero hidden retries and normalized failures.
5. Readiness, exact version, provider, Secret and profile failures close safely.
6. Two-workspace tests prevent cross-scope quote/reservation/component/Chunk/Entry access.
7. Admission denial occurs before adapter invocation.
8. Crash tests cover every row in section 10.
9. Equal duplicate admission/dispatch/Entry/settlement is idempotent; conflicts fail.
10. P1 CHAT execution and budgets remain unchanged.
11. Java tests, `bootJar`, OpenAPI, Compose, containers and security/dependency scans pass.
12. English and Simplified Chinese evidence match.

No TypeScript or Playwright work is required because no frontend changes or live page are allowed.

## 14. Implementation checkpoints

1. **P2.2c-1 — deterministic adapter and quote/replay API**
2. **P2.2c-2 — live Governance component lifecycle**
3. **P2.2c-3 — OpenAI-compatible adapter and protocol stub**
4. **P2.2c-4 — Knowledge batch primitive and crash matrix**
5. **P2.2c-5 — full verification and bilingual evidence**

Each checkpoint is a coherent verified commit candidate, not necessarily a separate PR. None
activates P2.2d.

## 15. Rollback and self-critique

- Knowledge and real providers remain disabled by default;
- there is no P2.2c migration;
- rollback deploys the previous binary and retains all V9/V10 evidence;
- stop new calls and boundedly drain before rollback;
- never delete or rewrite dispatched/terminal components.

Known limitations:

1. Deterministic vectors prove orchestration, not semantics.
2. UTF-8 units are conservative and are not provider Tokens.
3. Generic OpenAI compatibility cannot honestly promise idempotency.
4. Entries-before-settlement creates a recoverable settlement-only window; the reverse order creates
   a worse charged-without-artifact window.
5. Direct primitive tests are not an end-user Build workflow; P2.2d owns that workflow.
6. Spring AI inside Capability Registry increases adapter weight but preserves Knowledge neutrality.
7. USD-only cost remains an explicit limitation.
8. Unavailable provider usage settles with the versioned estimate marked `ESTIMATED`, never an
   invented actual value.
9. Spring AI's String API-key boundary prevents guaranteed in-place erasure of the final temporary
   copy. P2.2c minimizes its lifetime and reach rather than making a false zeroization claim.

## 16. Approval gate

Business coding starts only after maintainer approval. Approval authorizes checkpoints 1–5 only.
It does not authorize P2.2d Build runner/publication, P2.2e Retrieval Lab, frontend activation, a
new migration/table/deployable, another AI framework, ANN/hybrid retrieval, or a live Knowledge
claim.

Primary implementation references:

- Spring AI 2.0 Embedding Model API:
  <https://docs.spring.io/spring-ai/reference/api/embeddings.html>
- Spring AI 2.0 OpenAI Embeddings:
  <https://docs.spring.io/spring-ai/reference/api/embeddings/openai-embeddings.html>
