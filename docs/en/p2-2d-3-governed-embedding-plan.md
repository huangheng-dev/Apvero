# P2.2d-3 Governed Embedding Orchestration — Implementation Plan

Status: implementation candidate; business coding not started

Target: P2 / P2.2d-3

Authority: ADR-0006, the accepted P2.2c Embedding baseline, the approved P2.2d durable Build
baseline, and the implemented P2.2d-2 lease/transition kernel

Reasoning level: high

## 1. Outcome

P2.2d-3 connects one already-leased `EMBEDDING` Build to the P2.2c governed batch primitive:

```text
leased Build
  -> reconstruct the deterministic batch at durable progress
  -> idempotently admit the exact Governance component
  -> inspect durable component and Entry evidence
  -> choose one approved recovery action
  -> perform at most one provider call
  -> persist one complete Entry batch behind the lease fence
  -> settle the same component
  -> persist progress and release the lease
```

The slice is complete when every crash boundary can resume without a duplicate charge, a partial
Entry batch, an invented usage value, or a stale-worker mutation.

It does not activate a scheduler, public Build operation, Retrieval Lab, publication, frontend,
Application binding, Release, Runtime, queue, or additional infrastructure.

## 2. Change declaration

| Item | Decision |
|---|---|
| Stage | P2 / P2.2d-3, `in-progress` |
| Primary module | `knowledge` |
| Supporting modules | `governance`, existing `capability-registry` API |
| Allowed dependencies | Knowledge → Identity, Capability Registry, Governance |
| REST / OpenAPI / JSON Schema | No change |
| Java API | One additive provider-neutral Governance component snapshot |
| Database migration | None; V10/V11 contain all required durable evidence |
| Stateful dependency / deployable | None |
| AI abstraction | Existing Spring AI 2.0 Embedding capability only |
| Exposure | Internal and disabled; no live product claim |

The approved P2.2d baseline explicitly authorizes the Governance snapshot seam. No invariant,
module boundary, release semantic, security policy, or technology baseline changes, so no new ADR
is required. If implementation proves that another column, table, dependency, or public contract
is necessary, coding stops and returns to architecture review.

## 3. Existing seams that must be reused

- `KnowledgeIndexBuildTransitionKernel` remains the only owner of claim renewal, progress,
  transition, retry and terminal failure mutations.
- `KnowledgeEmbeddingBatchExecutor` remains the canonical owner of source reconstruction, route
  verification, unit estimation, stable component identity, output validation and Entry mapping.
- `KnowledgeEmbeddingEntryBatchWriter` remains the only complete-batch writer.
- `EmbeddingCapability` remains the provider-neutral quote and execution boundary.
- `ExecutionGovernance` remains the only reservation/component lifecycle boundary.
- `KnowledgeEmbeddingRecoveryDecider` retains exactly the eight approved actions.

No second batching algorithm, lease implementation, Governance facade, or provider adapter is
introduced.

## 4. Required pre-coding corrections

### 4.1 Scoped component snapshot

Add an immutable `ExecutionComponentSnapshot` and a scoped lookup to `ExecutionGovernance`.
The snapshot contains only:

- reservation and component identities;
- component type;
- exact Route ID/reference;
- estimated units/cost and currency;
- actual units/cost and usage quality when terminal;
- state: `RESERVED`, `DISPATCHED`, `SUCCEEDED`, `FAILED`, or
  `RECONCILIATION_REQUIRED`;
- optional provider request identity and stable failure code.

Lookup requires the caller's Workspace ID, reservation ID and deterministic component identity.
All predicates include tenant/workspace scope resolved through Identity. Missing and cross-scope
records use the same empty/not-found result. No provider body, endpoint, Secret, source text or
vector is exposed.

### 4.2 Transaction participation

The P2 component overloads currently use `REQUIRES_NEW`. That prevents a Knowledge transaction
from holding and validating the Build lease fence while Governance commits a component mutation.

Change only the component lifecycle overloads used by P2 to standard `REQUIRED` participation:

- admit component reservation;
- mark/enrich dispatch;
- settle component;
- require reconciliation;
- read component snapshot.

Called alone, each still opens one transaction. Called by the Knowledge lease-fenced coordinator,
it joins that short transaction. The P1 single-CHAT methods retain compatibility. Regression tests
must prove equal idempotency, conflict behavior and P1 semantics.

### 4.3 Lease-fenced Entry persistence

The current Entry writer locks the Build but does not prove expected lease owner, lock version and
database-time expiry. Add a lease-fenced write path accepting the exact claimed Build and owner.
Inside the same transaction it:

1. locks the scoped Build;
2. verifies `EMBEDDING / EMBEDDING`, expected owner, expected lock version and unexpired lease
   using database time;
3. validates the complete expected batch;
4. inserts all Entries or accepts an equal complete batch;
5. rejects partial, conflicting or stale writes.

The d-3 production path may not call an unfenced writer.

The d-2 kernel also gains one non-mutating internal lease assertion backed by a repository query
that locks the scoped Build and requires the expected owner, lock version, state and
`lease_until > current_timestamp`. All d-3 durable phases use this assertion; Java wall-clock
comparison is not an authority for lease validity.

### 4.4 Recovery precedence correction

The implemented P2.2c decider currently lets `FAILED` or `RECONCILIATION_REQUIRED` plus equal
Entries fall through to `SETTLE_ONLY`. A terminal unsuccessful component cannot be overwritten by
success, so d-3 corrects this edge before orchestration:

- partial Entries always produce `INTEGRITY_FAILURE`;
- a non-terminal component with different complete Entries produces `INTEGRITY_FAILURE`;
- `SUCCEEDED` plus equal complete Entries produces `COMPLETE`;
- `FAILED` or `RECONCILIATION_REQUIRED` plus equal complete Entries produces
  `LEDGER_ARTIFACT_INCONSISTENCY`;
- other terminal missing/different evidence produces `LEDGER_ARTIFACT_INCONSISTENCY`.

The action vocabulary is unchanged. An exhaustive truth-table test prevents ordering in the
decider from silently changing this precedence again.

## 5. Deterministic batch reconstruction

The durable cursor is `Build.embeddedEntryCount`, not “the first currently missing Entry.”
This distinction recovers the window where Entries committed but settlement/progress did not.

For each claimed Build:

1. reload the exact Build, source revisions and canonical ordered Chunks;
2. start at `embeddedEntryCount`;
3. choose consecutive Chunks under both pinned item and estimated-unit limits;
4. fail before admission if the first remaining Chunk is individually oversized;
5. use the first selected Entry ordinal as `batchOrdinal`;
6. derive the existing P2.2c identity from Build, batch ordinal, Route and ordered
   `(Chunk ID, content digest)` manifest;
7. reject a hole, out-of-order Entry or an Entry beyond the durable cursor that does not belong to
   this exact reconstructed batch.

The same Build state always reconstructs the same batch independent of locale, timezone, process,
worker, database row order or retry count.

When `embeddedEntryCount == requestedChunkCount`, no component is admitted and no provider is
called. The leased Build advances to `INDEXING` through the existing d-2 kernel.

## 6. Stable reservation identity

Each batch uses:

- subject: `KNOWLEDGE_INGESTION`, subject ID = Build ID;
- component: `EMBEDDING_INDEX`;
- component idempotency: existing `knowledge-embedding:<sha256>`;
- actor: fixed bounded internal actor `apvero-index-build-runner`;
- trace: deterministic bounded identity derived from Build ID and batch ordinal;
- exact pinned Route, quote, units, cost and currency.

Actor and trace are deterministic because Governance compares them during idempotent re-admission.
No random request identity may make a retry conflict with its own reservation.

## 7. One-claim orchestration

Introduce one package-internal `KnowledgeIndexBuildEmbeddingOrchestrator`. It processes one already
claimed Build and returns one typed bounded outcome. It is not a polling loop or scheduler.

### 7.1 Short durable phases

```text
PREP  read-only deterministic reconstruction and quote; no Build row lock
TX-A  lease fence + plan/evidence revalidation + idempotent admission + snapshot
TX-B  lease fence + RESERVED -> DISPATCHED
I/O   at most one EmbeddingCapability.embed call; no database transaction
TX-C  lease fence + optional provider-identity enrichment
TX-D  lease fence + complete Entry batch persistence
TX-E  lease fence + component settlement
TX-F  d-2 progress update and lease release
```

Recovery-only paths omit unnecessary phases. Every Governance mutation in TX-A/B/C/E joins the
same short transaction as the locked Knowledge lease check through public module services. No
module reads another module's tables.

PREP may read a large immutable source snapshot, so it must not hold a Build row lock. TX-A locks
the Build and cheaply revalidates the expected Build version/state, durable cursor, exact batch
identity and Entry evidence before admission. Immutable source revisions make the prepared content
stable; any changed mutable evidence invalidates the plan and is recomputed.

The provider timeout must remain below `leaseDuration - commitMargin`. A lease renewal may occur
before dispatch only through the d-2 compare-and-set kernel. There is no renewal after an
ambiguous call merely to let an old worker commit.

### 7.2 Provider result handling

After the provider returns:

1. validate execution identity, route, output count/order, item/content digests, dimension, finite
   values and normalization;
2. revalidate the lease;
3. enrich the already-dispatched component with provider request identity when present;
4. persist the complete Entry batch;
5. settle the identical component;
6. record monotonic progress and release the lease.

If lease validation fails at any point after I/O, the old worker performs no further durable
mutation. The next owner recovers from the component and Entry ledger.

## 8. Recovery decision matrix

The pure decider remains authoritative:

| Component evidence | Entry evidence | Replay policy | Action | Provider calls |
|---|---|---|---|---:|
| absent before idempotent admission | none | any | `ADMIT` | 0 |
| `RESERVED` | none | any | `DISPATCH` | up to 1 after dispatch |
| `DISPATCHED` | none | `SAFE_REPLAY` | `REPLAY` | up to 1 |
| `DISPATCHED` | none | `RECONCILIATION_REQUIRED` | `RECONCILE` | 0 |
| non-terminal | complete and equal | any | `SETTLE_ONLY` | 0 |
| `SUCCEEDED` | complete and equal | any | `COMPLETE` | 0 |
| any | partial | any | `INTEGRITY_FAILURE` | 0 |
| non-terminal | complete but different | any | `INTEGRITY_FAILURE` | 0 |
| `FAILED` or `RECONCILIATION_REQUIRED` | complete and equal | any | `LEDGER_ARTIFACT_INCONSISTENCY` | 0 |
| terminal | missing or complete but different | any | `LEDGER_ARTIFACT_INCONSISTENCY` | 0 |

`FAILED` and `RECONCILIATION_REQUIRED` are terminal ledger states and cannot be overwritten by a
new success.

### 8.1 Honest settlement-only accounting

There is intentionally no new provider-result table. If Entries committed but actual usage was
lost before settlement, recovery uses the component's frozen estimated units/cost and settles with
`ESTIMATED` quality. It never labels that value `ACTUAL`.

On the normal path, available actual usage/cost is settled with its truthful quality. Provider
request identity is enriched before Entry persistence when available, improving diagnosis without
making it a correctness dependency.

This is the only correct no-migration recovery: inventing actual usage is forbidden, repeating an
unsafe paid call is worse, and leaving an equal durable artifact permanently unsettled breaks the
workflow.

## 9. Action behavior

- `ADMIT`: create or reuse the exact reservation; admission denial occurs before provider I/O.
- `DISPATCH`: durably mark dispatch, then perform one call.
- `REPLAY`: durably confirm the same dispatched identity, then perform one call only for
  `SAFE_REPLAY`.
- `RECONCILE`: mark the component reconciliation-required and fail the Build with
  `reconciliationRequired=true`; no retry.
- `SETTLE_ONLY`: settle from retained terminal result if available in the current attempt,
  otherwise from the frozen estimate with `ESTIMATED`; then update progress.
- `COMPLETE`: perform no Governance/provider mutation; update progress from equal Entries.
- `INTEGRITY_FAILURE`: fail the leased Build with a stable validation/integrity code.
- `LEDGER_ARTIFACT_INCONSISTENCY`: fail closed with a distinct stable code and retain all evidence.

Transient failures before durable dispatch may use the d-2 retry policy. A timeout or transport
failure after `DISPATCHED` follows replay policy, not a generic automatic retry.

## 10. Failure mapping

Errors map once to `KnowledgeIndexBuildFailure`:

- invalid source/route/digest/output/Entry evidence → `VALIDATION`, non-retryable;
- authorization, scope, Secret or policy denial → `SECURITY`, non-retryable;
- pre-dispatch temporary database/readiness failure → `TRANSIENT`, retryable;
- deterministic unsupported/oversized/provider rejection → `PERMANENT`, non-retryable unless the
  approved normalized error explicitly says pre-dispatch transient;
- unknown internal invariant break → `INTERNAL`, non-retryable;
- unsafe unresolved dispatch → `AMBIGUOUS`, reconciliation required.

Stable codes and bounded metadata only are stored. Provider bodies, URLs, source content, vectors,
Secrets and lease owner values are not placed in errors, logs, metrics or audit payloads.

## 11. Audit and telemetry boundary

P2.2d-3 records administrative audit only for terminal failure/reconciliation, using the existing
Governance audit API and the same failure transaction where required. Per-batch progress remains
typed Build/component state, not audit spam.

This slice emits typed orchestration outcomes that d-5 can bind to metrics. It does not activate a
new meter set or health contributor. Future bounded dimensions are action, outcome, usage quality,
replay policy and failure category. Tenant, workspace, Build, Route, Chunk, reservation, trace,
request and URL identities remain forbidden labels.

## 12. Verification

### 12.1 Unit and contract tests

1. Exhaust every component × Entry × replay-policy recovery row.
2. Freeze batch selection and identity across locale, timezone, row order and retries.
3. Prove the durable cursor reconstructs an already-written unsettled batch.
4. Prove deterministic actor/trace re-admission returns the same reservation.
5. Prove snapshot scope, equality and safe projection.
6. Prove P1 CHAT and existing P2.2c component lifecycle compatibility.
7. Prove normalized failure mapping and absence of sensitive fields.

### 12.2 PostgreSQL/Testcontainers crash tests

Inject a crash after each of:

1. claim before admission;
2. admission before dispatch;
3. dispatch before provider call;
4. provider call before identity enrichment;
5. identity enrichment before Entries;
6. Entries before settlement;
7. settlement before progress;
8. progress before response.

Also prove:

- admission denial invokes the provider zero times;
- one claim invokes the provider at most once;
- unsafe dispatched work never auto-replays;
- partial/conflicting Entries are never filled around;
- stale/expired workers cannot admit, dispatch, enrich, persist, settle, reconcile, fail or advance;
- a successor claim fences the previous owner;
- cross-workspace reservation, component, Build, Chunk and Entry access fails closed;
- equal retries are idempotent and conflicting retries fail;
- Governance or audit failure rolls back its lease-fenced phase;
- no database transaction is active during provider I/O.

### 12.3 Cumulative gate

Run Knowledge, Governance and Capability Registry unit/integration tests, Spring Modulith/ArchUnit,
Flyway migration tests, P1 governance regressions, P2.1 ingestion, P2.2c execution, d-1 Build API,
d-2 lease tests, Java formatting/static checks, `bootJar`, contract checks and Compose health.
Frontend and Python suites remain unchanged but cumulative CI must stay green.

## 13. Implementation checkpoints

1. **d3.1 — Governance snapshot and transaction participation**
2. **d3.2 — deterministic next-batch reconstruction and lease-fenced Entry writer**
3. **d3.3 — one-claim orchestrator and all eight recovery actions**
4. **d3.4 — crash, concurrency, security and cumulative verification**

These are coherent verification checkpoints on one later `feature/` implementation branch. They do
not authorize separate feature expansion.

## 14. Rollback

- The runner remains disabled, so merging d-3 does not start background work.
- No schema rollback is needed.
- A previous compatible binary ignores the new internal orchestrator and snapshot API.
- Existing reservations, dispatched components and Entries are retained and never rewritten.
- Stop new claims and drain bounded in-flight work before binary rollback after later activation.
- Reconciliation-required evidence is never cleared by rollback.

## 15. Self-critique

1. Exactly-once provider execution remains impossible without provider idempotency; the design
   exposes ambiguity instead of hiding it.
2. Joining Governance mutations to a Knowledge lease-fenced transaction creates deliberate
   transactional coupling, but not database ownership coupling. It is narrower and safer than
   allowing stale settlement.
3. Settlement-only recovery may downgrade usage quality from available-at-response to `ESTIMATED`
   after a crash because no result ledger exists. This is honest and avoids a new migration; if
   exact actual usage across this boundary becomes mandatory, it requires a separately reviewed
   durable-result design.
4. Reconstructing from `embeddedEntryCount` assumes progress is a contiguous prefix. V11 and the
   complete-batch writer must enforce and test that invariant.
5. One batch per claim sacrifices throughput for fairness, bounded ambiguity and simpler recovery.
6. d-3 is still an internal workflow slice, not an end-user Knowledge feature. Publication belongs
   to d-4 and operational activation/evidence belongs to d-5.

## 16. Approval gate

Maintainer approval authorizes only the four d-3 checkpoints above. It does not authorize d-4
publication, d-5 runner activation, P2.2e Retrieval Lab, public contract activation, a migration,
another table/deployable/stateful dependency, or changes to Release/Runtime/Application.
