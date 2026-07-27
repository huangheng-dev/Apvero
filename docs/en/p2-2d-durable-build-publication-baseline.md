# P2.2d Durable Build and Atomic Publication — Implementation Baseline

Status: implementation candidate; maintainer approval required before business coding

Target: P2 / P2.2d

Authority: ADR-0006, `architecture/invariants.yaml`, `architecture/delivery-stages.yaml`,
`architecture/modules.yaml`, `architecture/dependency-rules.yaml`, and the existing contract-only
Knowledge Build operations in `contracts/openapi/platform-api.yaml`.

## 1. Outcome

P2.2d turns the P2.2c Embedding primitive into one durable internal workflow:

```text
Create Build
  -> pin exact source revisions
  -> lease one durable step
  -> embed complete deterministic batches
  -> verify the complete entry manifest
  -> validate every publication invariant
  -> atomically publish one immutable Index Version
  -> expose persisted Build state
```

Completion means that a process can crash at every durable boundary without publishing a partial
index, losing the selected source set, creating duplicate Entries or charges, or allowing a stale
worker to overwrite newer state.

P2.2d does not implement Retrieval Lab, Application binding, ReleaseBundle 1.1, grounded Runs,
frontend activation, ANN/hybrid retrieval, a queue, a new deployable or another stateful dependency.

## 2. Architecture decision check

No new ADR is required. ADR-0006 already authorizes:

- the Knowledge-owned Index Build and immutable Index Version lifecycle;
- PostgreSQL leases and additive migrations;
- governed Spring AI Embedding;
- atomic publication and bilingual Build APIs.

The implementation stays inside that decision:

| Concern | Owner | Allowed dependency |
|---|---|---|
| Build, source snapshot, Entry manifest, Version publication | Knowledge | — |
| Workspace scope and background-workspace enumeration | Identity | Knowledge → Identity |
| Route resolution, quote and Embedding execution | Capability Registry | Knowledge → Capability Registry |
| Admission, component recovery, settlement and audit | Governance | Knowledge → Governance |

Knowledge must not depend on Application, Release or Runtime and must not read another module's
tables. Provider SDK types remain inside approved adapter packages.

P2.2d needs one additive provider-neutral Governance read seam for crash recovery: a component
snapshot lookup by reservation ID and deterministic component identity. This is within ADR-0006's
approved component-ledger extension. It is not a REST endpoint and exposes no provider body or
Secret.

## 3. Public surface

The existing contract-only operations become live only after their implementation and evidence pass:

- `GET /api/v1/knowledge-indexes/{indexId}/builds`
- `POST /api/v1/knowledge-indexes/{indexId}/builds`
- `GET /api/v1/knowledge-index-builds/{buildId}`
- `POST /api/v1/knowledge-index-builds/{buildId}/retry`
- `POST /api/v1/knowledge-index-builds/{buildId}/cancel`

No path, request field or response meaning changes in P2.2d. Implementation status is removed
operation by operation only after conformance, authorization, telemetry and failure tests pass.
Index Version listing remains contract-only until P2.2e provides the read workflow.

Public Java contracts owned by Knowledge:

- `KnowledgeIndexBuildCatalog`;
- `CreateKnowledgeIndexBuildCommand`;
- immutable `KnowledgeIndexBuild` projection;
- status and step enums matching the persisted lifecycle.

Commands receive `KnowledgeCommandContext`; actor, trace and source IP are bounded before audit.
Backend failures use stable codes and clients localize messages.

## 4. Build creation and canonical identity

Build creation is one transaction:

1. require Knowledge enabled and a full authorized `WorkspaceScope`;
2. lock the scoped active Index;
3. resolve the exact EMBEDDING Route and copy its immutable reference/profile;
4. load every requested Source Revision through Knowledge repositories;
5. require the same Knowledge Base, active Source, snapshotted revision, terminal READY ingestion
   job, at least one Document and at least one Chunk;
6. reject duplicate Source or Revision identities;
7. sort by Source UUID, Revision UUID and then assign source-set ordinals;
8. compute exact source/chunk counts and the canonical source-set digest;
9. compute the canonical Build request digest;
10. insert the Build and all Build Revision rows;
11. append `knowledge.index-build.requested`;
12. commit.

The request digest uses length-prefixed UTF-8/binary fields and SHA-256 over:

- tenant, workspace, Index and Knowledge Base IDs;
- requested semantic version;
- exact Route ID, reference, dimension, input limit, batch limit and normalization;
- each ordered Source, Revision, content digest, parser version, chunker version and Chunk count.

`(knowledge_index_id, requested_version)` is the public idempotency identity. An equal repeated
request returns the existing Build. A different canonical digest for the same version returns
`APVERO_KNOWLEDGE_BUILD_VERSION_CONFLICT`. A uniqueness race is caught, re-read under scope and
resolved by the same equality rule.

No later lookup of a Source's latest Revision is allowed.

## 5. Persisted state machine

```text
QUEUED / EMBEDDING
  -> EMBEDDING / EMBEDDING
  -> INDEXING / INDEXING
  -> VALIDATING / VALIDATING
  -> READY / COMPLETE

active step -> RETRY_WAIT / same step -> matching active status
active step -> FAILED / same step
FAILED retryable --manual retry--> RETRY_WAIT / same step
QUEUED or RETRY_WAIT -> CANCELLED / retained step
ambiguous dispatch -> FAILED / EMBEDDING / reconciliation required
```

Only these transitions and idempotent same-state lease/progress updates are valid. `READY` is
immutable. Counters are monotonic, `validated_entry_count <= embedded_entry_count`, attempt count
never exceeds the configured maximum during automatic retries, and every successful mutation
increments `lock_version`.

Automatic retry uses `RETRY_WAIT` with deterministic bounded exponential backoff. `FAILED` records
a completed attempt. Manual retry is allowed only when `retryable=true`, resets the automatic
attempt window, preserves the durable step and emits an audit event. Audit history is the durable
manual-retry history; logs are not.

Cancellation is accepted only for unleased `QUEUED` or `RETRY_WAIT`. An active provider call is not
reported as cancelled.

## 6. Lease and stale-worker rules

The Build runner reuses the proven P2.1 PostgreSQL pattern but has separate bounded configuration
under `apvero.knowledge.index-build-runner.*`.

- enumerate authorized background workspaces through Identity;
- claim a small scoped set using `FOR UPDATE SKIP LOCKED`;
- persist owner, expiry, attempt and active status before work;
- perform no network call while a database transaction is open;
- execute at most one external Embedding batch per claimed task;
- require `workspace + build + lease owner + lock version + unexpired lease` for every mutation;
- clear the lease after one durable unit so other Builds remain fair;
- use a provider timeout shorter than lease duration with a documented commit margin;
- stop new claims during shutdown and boundedly drain in-flight work.

An expired worker may finish computation but cannot persist, settle or advance state. It may renew
only with a compare-and-set proving that no successor claimed the Build. Lease expiry itself is not
proof that an external call did or did not happen.

## 7. EMBEDDING step

For each claim:

1. reload the exact Build, source snapshot, Route profile and ordered missing Entries;
2. select the next deterministic batch under item and estimated-unit limits;
3. derive the P2.2c stable batch/component identity;
4. re-admit the same `KNOWLEDGE_INGESTION / EMBEDDING_INDEX` reservation;
5. read the Governance component snapshot;
6. apply the approved P2.2c recovery decision;
7. when permitted, mark dispatched before calling `EmbeddingCapability.embed`;
8. validate the complete ordered result;
9. revalidate lease ownership;
10. atomically persist the complete Entry batch;
11. settle the identical component;
12. update durable Build progress and release the lease.

Recovery actions remain exactly:

`ADMIT`, `DISPATCH`, `REPLAY`, `RECONCILE`, `SETTLE_ONLY`, `COMPLETE`,
`INTEGRITY_FAILURE`, `LEDGER_ARTIFACT_INCONSISTENCY`.

The runner never infers component state from logs. An unresolved dispatched component can replay
only when the adapter declares `SAFE_REPLAY`; otherwise the Build becomes `FAILED` with
`reconciliation_required=true`.

When all selected Chunks have one self-consistent Entry, a leased compare-and-set moves the Build
to `INDEXING`. Entry insertion after that transition is forbidden.

## 8. INDEXING and VALIDATING steps

`INDEXING` performs no provider I/O. It verifies:

- exact Build Revision membership and source-set digest;
- one Entry for every selected Chunk and no extra Entry;
- stable source/document/chunk/entry ordinals;
- exact Route reference/profile and vector dimension;
- recomputed normalized-input and IEEE-754 float32 vector digests;
- finite, non-zero vectors and exact lineage.

It computes a canonical validation manifest/digest, persists validated counts, and advances to
`VALIDATING` with a lease/version compare-and-set.

`VALIDATING` reloads and recomputes all publication-critical evidence. It does not trust counters or
the previous digest alone.

The artifact digest is length-prefixed SHA-256 over the exact Route/profile, ordered source snapshot,
ordered Chunk identities/content digests, ordered Entry identities/vector digests and canonical
counts. JSON ordering, default charset, locale and timezone cannot affect it.

## 9. Atomic publication transaction

Publication is one short transaction:

1. lock the scoped Build and Index in stable order;
2. require `VALIDATING`, the expected unexpired lease owner and lock version;
3. rerun cardinality, membership, lineage, digest, Route and vector-shape checks;
4. persist the final validation and artifact digests;
5. insert one deterministic immutable Index Version;
6. set Build to `READY / COMPLETE`, link the Version, finalize counts/time and clear the lease;
7. update Index `latest_ready_version_id`, version count and metadata version;
8. append `knowledge.index-version.published` through Governance's public audit interface;
9. commit.

Any failure, including audit failure, rolls back Version, Build and Index changes. The deterministic
Version ID is derived from Build identity; an equal replay returns the already published Version
only when every field and digest is equal. Any mismatch is
`APVERO_KNOWLEDGE_PUBLICATION_CONFLICT`.

Retrieval cannot address a Build and no partial artifact becomes a READY Version.

## 10. V11 database hardening

P2.2d requires one forward Flyway migration. It adds no table and no new stateful dependency.

V11 must:

- permit Entry insertion only while the scoped Build is `EMBEDDING / EMBEDDING` and unpublished;
- serialize Entry insertion against Build step transition so a late insert cannot commit after
  `INDEXING` begins;
- enforce the approved Build transition matrix and terminal immutability;
- enforce monotonic progress, attempt and lock-version behavior where SQL constraints can do so;
- require Version insertion from a scoped `VALIDATING` unpublished Build;
- reject a second or mismatched publication;
- preserve V10 data and include forward mitigation/rollback documentation.

Clean install and V10-to-V11 upgrade tests must prove the guards. The previous binary can ignore the
strengthened database guards, but rollback after a READY Version exists must remain at a
P2-compatible binary as defined by ADR-0006.

## 11. Crash and concurrency matrix

| Boundary | Durable evidence | Required recovery |
|---|---|---|
| Before Build commit | none | client safely retries create |
| Build committed, no claim | `QUEUED` | claim normally |
| Claim committed, no admission | active lease | expire/reclaim, then `ADMIT` |
| Reservation committed, no dispatch | component `RESERVED` | `DISPATCH` |
| Dispatch committed, no durable result | component `DISPATCHED` | safe replay or reconciliation |
| Entries committed, no settlement | complete-equal Entry batch | `SETTLE_ONLY` |
| Settlement committed, no progress update | succeeded component + complete Entries | advance without another call |
| Partial or conflicting Entry batch | inconsistent artifact | fail integrity; never fill around it |
| INDEXING digest committed, no transition | persisted digest | recompute and idempotently advance |
| Version insert transaction aborts | no Version and Build not READY | retry validation/publication |
| Publication commits, response lost | READY Build + equal Version | return existing Version |
| Lease expires while old worker returns | successor/expired lease | stale worker cannot mutate |
| Two publishers race | Build/Index locks + unique Version | one commits; equal loser reads existing |

Tests must inject a crash or transaction rollback at every row rather than only testing final states.

## 12. Security, errors and telemetry

All REST operations require the existing authenticated workspace header and current read/write/admin
policy. Cross-workspace IDs return the same scoped not-found family. No Base URL, Secret, provider
body, vector, source text, internal lease owner or cross-scope existence appears in normal responses.

Stable errors cover disabled, invalid request, scoped not found, version conflict, ineligible source,
Route/profile/readiness, illegal transition, lease/concurrent modification, retry/cancel conflict,
admission denial, provider failure/ambiguity, Entry integrity, publication validation and audit
failure.

Audit covers Build request, manual retry/cancel, terminal failure/reconciliation and publication.
Per-batch progress is typed state and metrics, not administrative audit spam.

Metrics cover queue wait, step duration/outcome, attempts, batch items/units, embedded/validated
counts, retries, stale lease rejection, recovery action, publication validation and outcome.
Labels are bounded enums only; tenant, workspace, Build, Route, Chunk, request, URL and content
identities are forbidden.

Health reports the feature flag, runner accepting state, in-flight count, oldest eligible Build age
and reconciliation count without probing a paid provider.

## 13. Verification gate

1. Spring Modulith and ArchUnit preserve all module and provider-adapter boundaries.
2. OpenAPI conformance proves only the five accepted Build operations are live.
3. V11 clean migration and V10 upgrade tests pass.
4. Two-workspace API, repository, claim, retry, cancel, Entry and publication tests fail closed.
5. Canonical request/source/artifact digests pass locale, timezone and ordering variants.
6. Equal create and publication retries are idempotent; conflicting reuse fails.
7. Lease expiry, stale worker, duplicate claim and two-publisher races are deterministic.
8. Every crash-matrix row passes with persisted Testcontainers evidence.
9. Admission denial occurs before provider invocation; ambiguous dispatch never blind-retries.
10. Partial, extra, missing, wrong-lineage, wrong-dimension and wrong-digest Entries cannot publish.
11. Audit failure rolls back command/publication mutations.
12. Metrics and errors contain no high-cardinality or sensitive values.
13. P1 CHAT, P2.1 ingestion and P2.2c component/Entry behavior remain green.
14. Java, `bootJar`, contracts, Compose, containers and security/dependency checks pass.
15. Matching English and Simplified Chinese evidence is delivered in P2.2d-5.

No frontend or Python change is required. TypeScript/Playwright and worker tests still run in
cumulative CI, but P2.2d does not make a page or worker operation live.

## 14. Five implementation checkpoints

1. **P2.2d-1 — Build API and canonical source snapshot**
   - V11 guards, public Knowledge Build contracts, create/list/get/retry/cancel, scope and audit.
2. **P2.2d-2 — lease and transition kernel**
   - claim/reclaim, compare-and-set transitions, backoff, cancellation and stale-worker tests.
3. **P2.2d-3 — governed Embedding orchestration**
   - Governance component snapshot seam, one-batch execution, recovery matrix integration and
     durable progress.
4. **P2.2d-4 — validation and atomic publication**
   - complete manifest, canonical artifact digest, immutable Version transaction and race tests.
5. **P2.2d-5 — operations and bilingual verification**
   - metrics, health, safe errors, cumulative regression, Compose evidence and matching EN/zh-CN
     acceptance documents.

Each checkpoint is a coherent verified implementation commit. Planning and implementation remain
on separate branches and pull requests. None activates P2.2e.

## 15. Rollout, rollback and self-critique

- `APVERO_KNOWLEDGE_ENABLED=false` remains the outer fail-closed default;
- the Build runner has a separate disable switch and stops new claims before bounded drain;
- failed, cancelled and reconciliation-required Builds remain inspectable;
- no automatic cleanup rewrites immutable source, Entry, Version, Governance or audit evidence;
- rollback before any READY Version may use the previous compatible binary and retain V11;
- after a READY Version exists, ADR-0006's P2-compatible rollback floor applies.

Known limitations:

1. One external provider call cannot be exactly-once with a local transaction; reconciliation is an
   honest terminal outcome.
2. One external batch per claim favors fairness and recovery simplicity over peak throughput.
3. PostgreSQL polling is intentionally bounded and not a claim of queue-scale throughput.
4. Audit events preserve manual retry history, but P2.2d adds no dedicated attempt-history table.
5. Computing the full artifact digest costs O(entries); publication correctness takes priority and
   the supported corpus envelope is measured later.
6. `latest_ready_version_id` remains display metadata and must never become runtime resolution.
7. A READY Version proves structural reproducibility, not semantic retrieval quality; P2.2e owns
   Retrieval Lab evidence.

## 16. Approval gate

Maintainer approval authorizes only P2.2d-1 through P2.2d-5 as defined above. It does not authorize
P2.2e Retrieval Lab, P2.3 Application/Release/Run work, frontend activation, a new table/deployable,
Kafka/Redis/MinIO/Milvus, ANN/hybrid retrieval, another AI framework, cross-module SQL or a mutable
published index.
