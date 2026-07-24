# P2.2 Immutable Index and Retrieval Lab — Implementation Plan

Status: maintainer-approved design; section 3 corrections approved on 2026-07-24; no business implementation started

Target stage: P2, milestone P2.2

Decision baseline: ADR-0006 (accepted)

Reasoning level used for this plan: high

Feature flag: `APVERO_KNOWLEDGE_ENABLED=false` until full P2 acceptance

## 1. Outcome

P2.2 will establish one honest, restart-safe indexing and retrieval workflow:

```text
authorized workspace
  -> exact READY Source Revision set
  -> immutable Embedding Route version
  -> governed Index Build
  -> persisted Embedding batches and pgvector entries
  -> completeness, dimension, lineage and digest validation
  -> atomic immutable Index Version publication
  -> exact scoped Retrieval Lab query
  -> ranked score, content digest and source lineage
  -> MATCHES or typed NO_EVIDENCE
```

P2.2 does not bind Knowledge to an Application, write ReleaseBundle 1.1, execute a grounded Run,
or make the Knowledge product surface live. Those remain P2.3 and P2.4. All partial surfaces stay
disabled, hidden, or explicitly contract-only/demo.

The milestone is not accepted merely because vectors can be inserted and queried. It must prove
immutable publication, restart safety, budget admission, fail-closed workspace isolation,
reproducible ranking, and inspectable failure behavior.

## 2. Required change declaration

| Item | P2.2 plan |
|---|---|
| Stage | P2 / P2.2, currently `planned` |
| Primary module | `knowledge` |
| Supporting modules | `capability-registry`, `governance`, and `identity` through existing public APIs |
| Allowed dependencies | `knowledge -> identity, capability-registry, governance` only |
| Forbidden dependencies | No Knowledge dependency on Application, Release, Runtime, or provider SDK types |
| Public contracts | Embedding Model Route, Knowledge Index/Build/Version, Retrieval Policy Version, Retrieval Lab |
| Migrations | Forward-only additive migrations after V8 |
| New stateful dependency | None; PostgreSQL 18 plus pgvector remains the only mandatory stateful dependency |
| New deployable | None |
| AI abstraction | Spring AI 2.0 remains the only Java AI abstraction |
| Product exposure | Disabled/non-live until P2.4 |
| Frontend work | None in P2.2 implementation slices |

Expected dependency use:

```text
knowledge
  -> identity public Workspace scope
  -> capability-registry public Embedding execution facade
  -> governance public admission, reservation, settlement, retention and audit APIs

capability-registry
  -> identity
  -> governance
  -> Spring AI adapters internally

governance
  -> identity
```

No slice may add Kafka, Redis, MinIO, Milvus, Elasticsearch, an ANN index, hybrid retrieval,
another AI framework, a generic plugin runner, or another deployable.

## 3. Approved protected corrections required by implementation

Repository and official-capability review found four conflicts that must not be silently encoded.
The maintainer approved the ADR-0006 clarification and matching contract correction on 2026-07-24.
Every implementation slice must follow the corrected authority.

### 3.1 Embedding Route reference semantics

Current Java and database Model Routes use an immutable monotonic integer version and the canonical
reference `name@N`. The OpenAPI `EmbeddingModelRoute` also exposes an integer `version`, but
`KnowledgeIndexVersion.embeddingRouteVersion` requires `name@semver`. Both cannot be true.

Approved correction:

- Model and Embedding Route references remain `name@positive-integer`;
- Knowledge Index Version and Retrieval Policy Version references remain semantic versions;
- an Index Version pins the exact Route ID and canonical `name@N` reference;
- no new parallel semantic-version field is invented for the same Route.

This preserves the implemented P1 route lineage instead of creating a second version system.

### 3.2 pgvector dimension ceiling

The current contract permits an Embedding dimension up to 65,535. pgvector's `vector` storage type
supports at most 16,000 dimensions. P2.2 uses `vector`, not a lossy or binary substitute.

Approved correction:

- change every P2 `vectorDimension`/Embedding dimension maximum from 65,535 to 16,000;
- reject zero-norm vectors, non-finite values, a returned dimension mismatch, and a route dimension
  outside the supported storage envelope;
- do not imply that the ANN indexing ceiling is the storage ceiling. P2.2 creates no ANN index.

### 3.3 Tombstone behavior in published indices

ADR-0006 says a source tombstone affects future builds only and old indices/releases remain
reproducible. A later query bullet also asks for a non-tombstoned predicate on every retrieval.
Applying that predicate would mutate the observable contents of an already published Index Version.

Approved clarification:

- source status is checked when the exact build source set is created;
- a tombstoned source cannot enter a new build;
- retrieval from an already published Index Version does not filter by current source tombstone;
- current authorization and current retention/masking policy still apply at read time;
- legal erasure is a separate destructive governance workflow that must explicitly report broken
  reproducibility.

### 3.4 Reproducible Retrieval Policy algorithm identity

ADR-0006 requires deterministic tie-breaking, overlap handling, context budgeting, and a
retention/masking reference. The current OpenAPI policy does not identify the algorithm or token
estimator and omits retention-policy provenance. A code upgrade could therefore change the result
of an immutable policy without changing its ID.

Approved additive fields on the published policy projection:

- `retrievalAlgorithmVersion`;
- `tokenEstimatorVersion`;
- `retentionPolicyVersionAtPublish`;
- `policyDigest`.

The platform assigns supported algorithm identities; clients do not submit arbitrary executable
algorithm names. Current retention/masking rules can only reduce disclosure. An older policy can
never override a newer, stricter retention decision.

## 4. Architecture and truth boundaries

```text
REST / internal caller
        |
        v
Knowledge public API
  |         |          |
  |         |          +--> Governance public API
  |         |                 reservation + component ledger
  |         |
  |         +--> Capability Registry public Embedding facade
  |                    |
  |                    +--> deterministic Spring AI EmbeddingModel
  |                    +--> opt-in Spring AI provider adapter
  |
  +--> Knowledge repositories
           |
           +--> PostgreSQL tables
           +--> pgvector exact cosine operator
```

Truth ownership:

- Knowledge Build rows own workflow state, source-set identity, progress, validation and publication.
- Capability Registry owns route shape, readiness, secret resolution boundary and provider adapter
  selection.
- Governance owns admission, budget/rate decisions, reservation components and settlement.
- Spring AI responses are external results, not workflow truth.
- logs and metrics are diagnostic; they never replace Build, Version, Entry, Reservation or Audit
  records.

The Python worker is not involved in P2.2. It remains limited to the already accepted parser/chunker
contract.

## 5. Capability Registry extension

### 5.1 Route shape

The existing `model_route` table becomes a discriminated immutable route:

```text
route_capability = CHAT | EMBEDDING
```

Existing rows are backfilled as `CHAT`. The route-shape constraint requires:

- CHAT: `max_output_tokens` is present and `temperature` follows existing rules;
- EMBEDDING: immutable `dimension`, `maximum_input_tokens`, `maximum_batch_size`, and
  `normalization` are present; chat-only fields are null;
- the referenced Model Definition declares the matching capability;
- published route rows remain immutable.

The route profile is copied into every Build. A later deprecation or metadata edit cannot change an
existing Build or Index Version. Deprecation blocks new Builds but does not rewrite or silently
reroute a published Index Version. A pinned historical route may execute only while its exact
provider/model/Secret configuration remains available; otherwise retrieval fails with a stable
unavailable outcome and never falls back to another embedding space.

### 5.2 Public provider-neutral Java boundary

The public API will use JDK/Spring-neutral records equivalent to:

```text
EmbeddingRouteSnapshot resolveEmbeddingRoute(workspaceId, routeId)

EmbeddingExecutionResult embed(
  workspaceId,
  exactRouteReference,
  executionIdentity,
  orderedInputs[chunkId, contentDigest, boundedText]
)
```

The result contains ordered float vectors, exact model/route identity, actual input units when
available, provider request identity when safe, latency, and provider-neutral cost metadata.

Rules:

1. No Spring AI or provider SDK class crosses the public module API.
2. Input order and output index mapping are validated; missing, duplicate, or reordered outputs fail.
3. The returned dimension must equal the pinned route dimension for every item.
4. Every vector value must be finite and every vector must have a non-zero norm for cosine ranking.
5. `EmbeddingModel.dimensions()` is not called as discovery during a billable Build because the
   Spring AI default may invoke the provider. The declared profile is validated against real output.
6. Provider content and unrestricted error bodies are never logged or persisted.

### 5.3 Adapters

P2.2 implements:

- `apvero-deterministic-embedding@1.0.0`, a deterministic offline Spring AI `EmbeddingModel` used
  only for Quick Start, CI and workflow verification;
- an OpenAI-compatible Spring AI Embedding adapter behind an explicit route and Secret Reference;
- a local HTTP stub test for the real-adapter protocol without paid credentials.

The deterministic adapter must be stable across JVM process, locale, timezone and machine
restarts. Its dimension, normalization, hashing/canonicalization and algorithm version are frozen
by golden vectors. It is visibly described as non-production semantic quality.

Provider-specific features such as dimension overrides are accepted only inside an adapter and only
when they match the immutable route profile. They do not leak into Knowledge.

## 6. Governance component extension

P2.2 uses the narrow extension already authorized by ADR-0006. It does not redesign budgets.

Execution subjects:

```text
APPLICATION_RUN
KNOWLEDGE_INGESTION
KNOWLEDGE_QUERY
```

Reservation components used in P2.2:

```text
EMBEDDING_INDEX
EMBEDDING_QUERY
```

The existing P1 API remains source compatible through an adapter method. New reservations record:

- subject type and opaque subject ID;
- exact route ID/reference;
- component type and deterministic idempotency identity;
- estimated/actual units and cost, currency;
- admission, dispatch and settlement state;
- safe provider request identity where available;
- timestamps and stable failure/reconciliation code.

Application ID becomes optional only for non-Application subjects. Existing rows are backfilled as
`APPLICATION_RUN`; existing Runtime behavior and budget matching remain unchanged.

Workspace policies apply to every P2.2 call. Model Route policies match the exact component Route.
Application policies are evaluated only for `APPLICATION_RUN` and are skipped, without a null
comparison, for Knowledge subjects.

Reservation and component writes remain owned by Governance. Knowledge never reads or writes those
tables.

### Ambiguous external calls

An external provider call cannot be made exactly once by a local database transaction. The plan
therefore rejects a false exactly-once claim:

1. persist reservation/component before dispatch;
2. mark the component dispatched before the HTTP call;
3. use the same deterministic provider idempotency identity when the adapter/provider supports it;
4. persist validated entries before final settlement;
5. if entries exist and settlement did not finish, resume settlement without another provider call;
6. if dispatch occurred but no response was durably recorded, automatically retry only when the
   adapter declares provider-side idempotency;
7. otherwise stop with `APVERO_EMBEDDING_OUTCOME_AMBIGUOUS`, mark reconciliation required, and do
   not risk a second paid call.

The Governance ledger itself is idempotent: an identical repeated settlement is a no-op; a
conflicting settlement fails closed. Stale dispatched components are not silently settled at zero.

## 7. Persistence design

P2.2 uses the six already approved Knowledge tables and one Governance component table. It does not
add an unapproved Knowledge batch table.

### `retrieval_policy_version`

Insert-only immutable policy containing full scope, slug, semantic version, algorithm and estimator
versions, `top_k`, maximum context budget, minimum score, overlap behavior, `NO_EVIDENCE`, retention
provenance, canonical policy digest and creation evidence.

Unique identities:

- `(workspace_id, slug, version)`;
- `(workspace_id, policy_digest)`;
- full composite scope key.

### `knowledge_index`

Stable index identity tied to one Knowledge Base. It contains slug, name, status, optimistic
metadata version, version count, optional latest READY Version ID, and timestamps.

`latest_ready_version_id` is display metadata only. Retrieval and future Release binding always use
an exact Version ID/reference.

### `knowledge_index_build`

Durable mutable workflow row containing:

- full scope, Index ID and requested semantic version;
- exact Route ID/reference and copied Embedding profile;
- canonical request digest and exact source/chunk counts;
- status/current step, attempt/maximum attempts, retryability and next-attempt time;
- lease owner/until and optimistic lock version;
- cancellation request;
- embedded/validated entry counts and last durable chunk ordinal;
- validation/artifact digest and published Version ID;
- stable safe error/reconciliation metadata and timestamps.

`(index_id, version)` is the public create idempotency identity. Repeating an equal canonical request
returns the existing Build. Reusing that version with a different Route or source set returns a
stable conflict.

### `knowledge_index_build_revision`

Immutable ordered snapshot of the exact Source Revision set. It repeats scope, Build, Source,
Revision, content digest, parser/chunker versions and source-set ordinal.

Creation verifies in one transaction that:

- Index, Base, Source and Revision are in the same workspace and base;
- every Revision has a READY ingestion result and at least one Chunk;
- every selected Source is active at build creation;
- IDs are unique and sorted into a canonical order.

No later “latest revision” lookup is permitted.

### `knowledge_index_entry`

Build-scoped Entry containing full scope, Build, Chunk/Document/Revision/Source lineage, deterministic
entry ordinal, `embedding vector`, repeated vector dimension, vector digest, normalized-input digest,
batch ordinal, exact Route reference and creation time.

The column uses unbounded `vector`, because different Builds may have different dimensions. Row
checks enforce `vector_dims(embedding) = vector_dimension` and `vector_norm(embedding) > 0`; a
composite foreign key makes the Entry dimension equal the pinned Build dimension. pgvector rejects
non-finite elements.

There is no HNSW or IVFFlat index in P2.2. Ordinary B-tree indexes support scope/version joins and
deterministic lineage access.

### `knowledge_index_version`

Insert-only immutable published Version containing full scope, Index/Build identities, semantic
version/reference, exact Route ID/reference, dimension, source/chunk counts, artifact digest,
status `READY`, and publication time.

### `execution_reservation_component`

Governance-owned append/transition ledger described in section 6, with unique
`(reservation_id, idempotency_identity)` and exact route/component data.

## 8. Database immutability and atomic publication

Build source rows and Entry rows are insert-only from creation. New Entries can be added only while
their Build is unpublished.
After publication:

- `knowledge_index_version` rejects update/delete;
- its Build rejects update/delete;
- all Build Revision and Entry rows reject update/delete;
- an Entry insert into a published Build is rejected;
- ordinary delete of a published artifact is rejected.

Publication occurs in one short transaction:

1. lock the Build and stable Index identity;
2. require Build status `VALIDATING` and an unexpired owned lease;
3. verify exact source-set cardinality and digest;
4. verify every selected Chunk has exactly one Entry;
5. verify no unselected Chunk/Revision entered the Build;
6. verify Route/reference, dimension, finite non-zero vectors, lineage and all entry digests;
7. compute the canonical artifact digest;
8. insert exactly one immutable Index Version;
9. set Build `READY`, link the published Version and clear the lease;
10. update Index display metadata;
11. append the publication audit event;
12. commit.

Any failure rolls back the whole publication. Retrieval cannot address a Build ID, and it resolves
only a READY Index Version.

The artifact digest is SHA-256 over canonical length-prefixed binary fields in stable ordinal order,
including source-revision identity/digest, parser/chunker version, Chunk identity/content digest,
exact Route reference/profile, vector dimension, and a SHA-256 digest of the returned IEEE-754
float32 bytes. It does not depend on JSON object ordering or locale-sensitive number formatting.

## 9. Index Build state machine and execution

```text
QUEUED
  -> EMBEDDING
  -> INDEXING
  -> VALIDATING
  -> READY

active step -> RETRY_WAIT -> same durable step
active step -> FAILED
QUEUED or RETRY_WAIT -> CANCELLED
ambiguous paid dispatch -> FAILED / reconciliation required
```

Step meaning:

- `EMBEDDING`: select deterministic missing Entry batches, reserve, dispatch, validate and persist;
- `INDEXING`: verify the complete Entry set and compute canonical entry/source manifests;
- `VALIDATING`: run all publication gates and atomically publish;
- `READY`: immutable Version exists; this is not inferred from Entry count alone.

The runner reuses the P2.1 PostgreSQL lease pattern:

1. claim a small batch with `FOR UPDATE SKIP LOCKED`;
2. commit lease/attempt state before provider I/O;
3. operate only on persisted inputs;
4. perform bounded I/O without an open database transaction;
5. validate output fully;
6. commit idempotent results and the next step;
7. clear or renew the lease with ownership/version checks.

Batch membership is derived deterministically from ordered missing Chunk ordinals and the pinned
Route limits. No mutable in-memory queue is a source of truth. Entry uniqueness prevents duplicate
vectors; the Governance component identity prevents duplicate ledger charges.

Cancellation is accepted only in `QUEUED` or `RETRY_WAIT`. An active provider call is not falsely
reported as cancelled. Graceful shutdown stops new claims and allows bounded drain; leases recover
abandoned work after expiry.

## 10. Batching and token/unit rules

`maximumInputTokens` is treated as the maximum estimated aggregate input units per provider request;
`maximumBatchSize` is the maximum item count. Every individual Chunk must also fit the aggregate
limit by itself.

The initial deterministic estimator is versioned and conservative across English and Chinese. It
does not claim exact provider billing. Actual provider usage metadata, when returned, is the
settlement source; otherwise the versioned estimate and an `ESTIMATED` quality flag are recorded.

Batch construction is stable:

```text
ORDER BY source-set ordinal, document ordinal, chunk ordinal, chunk ID
```

It stops before either item-count or input-unit limit. Oversized input fails the Build with a stable
non-retryable error rather than silently truncating source text.

The exact estimator algorithm and the deterministic adapter dimension are frozen only after a
bilingual corpus benchmark in P2.2a. The decision record must include English, Simplified Chinese,
mixed-language, long-token, empty/whitespace, and adversarial Unicode cases.

## 11. Exact Retrieval Lab

### 11.1 Query embedding

Retrieval resolves the exact READY Index Version, then uses its pinned Embedding Route version for
the query. It creates a `KNOWLEDGE_QUERY / EMBEDDING_QUERY` reservation before a real billable call.
The query vector must match the Index dimension and have a non-zero norm.

The raw query is write-only. By default the durable evidence is the SHA-256 query digest, route,
usage/cost, outcome and latency. Raw query persistence is not added in P2.2.

### 11.2 One scoped ranking statement

The repository performs one SQL statement equivalent to:

```sql
SELECT ...
FROM knowledge_index_version version
JOIN knowledge_index_build build ON ...
JOIN knowledge_index_entry entry ON ...
JOIN knowledge_chunk chunk ON ...
JOIN knowledge_document document ON ...
JOIN knowledge_source_revision revision ON ...
JOIN knowledge_source source ON ...
WHERE version.tenant_id = :tenant_id
  AND version.workspace_id = :workspace_id
  AND version.id = :index_version_id
  AND version.status = 'READY'
  AND vector_dims(:query_vector::vector) = version.vector_dimension
  AND 1 - (entry.embedding <=> :query_vector::vector) >= :minimum_score
ORDER BY entry.embedding <=> :query_vector::vector ASC, entry.chunk_id ASC
LIMIT :top_k
```

Scope and READY Version filters are in the ranking statement before ordering and limiting. The
repository never queries candidate vectors globally and filters them in Java.

Current source tombstone is deliberately absent after the clarification in section 3.3. The
published source set is immutable. Current authorization and disclosure policy are still enforced.

Cosine similarity is exposed as a bounded value in `[0, 1]`; database distance remains the ordering
source. Equal distances use ascending immutable Chunk UUID as the deterministic tie-break.

### 11.3 Policy application

After the ranked SQL result:

- `KEEP` preserves every accepted hit;
- `COLLAPSE_ADJACENT` deterministically keeps the best-ranked member of overlapping adjacent chunks
  from the same Document;
- the frozen estimator applies the maximum context budget in rank order;
- no later hit is promoted ahead of an earlier accepted hit;
- an empty final set returns `NO_EVIDENCE`, never ungrounded fallback.

`topK` is a maximum, not a promise to backfill after overlap or context filtering.

### 11.4 Exposure

Each hit returns rank, normalized score, immutable Source/Revision/Document/Chunk identities,
content digest, bounded content if permitted, source type/title, and available page/heading/
paragraph/line anchors.

It never returns snapshot bytes, internal table keys not in contract, filesystem/object paths,
Secret References, provider responses, raw web URLs, or cross-workspace existence hints.

## 12. Security, errors, audit and telemetry

Read operations require existing read scope; index/policy/build mutations and retry/cancel require
write or admin under the current P1 policy. P2.2 does not claim resource-level ABAC.

Stable public error families include:

- Knowledge disabled;
- scoped Index, Build, Version, Policy, Route or Revision not found;
- Route not EMBEDDING, not READY, deprecated for new Build, or profile invalid;
- source tombstoned, Revision not READY, source-set/base mismatch;
- build version conflict, illegal transition, lease conflict, not retryable/cancellable;
- embedding input/batch limit, provider unavailable, invalid output/index/dimension/non-finite value;
- budget/rate denial, settlement conflict, ambiguous provider outcome;
- incomplete Entry set, lineage/digest/source-set validation failure;
- Index Version not READY, query too large, dimension mismatch;
- `NO_EVIDENCE` as a successful typed result, not an exception.

Backend errors expose codes and safe structured data; clients localize messages. No new
user-visible hard-coded text is authorized.

Administrative audit covers Index/Policy creation, Build request, manual retry/cancel, terminal
failure/reconciliation, and Version publication. Per-batch progress is typed state/telemetry, not
audit spam.

Metrics include:

- Build queue wait, step duration, attempts and outcomes;
- requested/embedded/validated Chunk and source counts;
- batch item/input units, provider latency, settlement outcome;
- dimension and algorithm version;
- retrieval latency, candidate/hit counts, score distribution and `NO_EVIDENCE`;
- publication validation outcome.

Metric labels are low-cardinality only. Tenant, workspace, query, content, Route/Build/Version IDs,
URLs and provider request IDs are forbidden labels.

Spring AI observations supplement Apvero records. They do not replace the Governance ledger or
Build state, and sensitive prompt/content observation export remains disabled.

## 13. Performance envelope

Exact pgvector search gives deterministic full recall but cannot support arbitrary corpus size.
P2.2 acceptance must publish a measured envelope, not a marketing number.

The benchmark matrix must include:

- dimensions used by the deterministic adapter plus representative 384, 768 and 1,536 dimensions;
- at least small, medium and acceptance-limit corpus sizes;
- English, Simplified Chinese and mixed-language queries;
- cold and warm database state;
- no-hit, low-threshold and high-`topK` cases;
- concurrent Retrieval Lab requests and simultaneous Build writes;
- storage size, build throughput, p50/p95/p99 latency and query plan.

The supported maximum corpus and concurrency are selected from measured resource budgets on the
documented reference machine. If exact search misses the accepted latency envelope, P2.2 reduces
the declared envelope. It does not silently add ANN.

## 14. Implementation slices

Slices merge in order. None independently makes P2 or the Knowledge page live.

### P2.2a — Protected correction and capability/governance shell

- preserve the maintainer approval record for section 3;
- amend ADR-0006 and English/Chinese contract baselines;
- correct OpenAPI route reference, dimension and policy provenance fields;
- add Embedding Route shape and provider-neutral public APIs;
- add backward-compatible Governance subject/component APIs;
- create corpus benchmark and deterministic adapter decision record;
- extend Modulith/ArchUnit checks before business execution is enabled.

### P2.2b — Scoped immutable persistence

- add forward Knowledge and Governance migrations;
- implement all composite scope keys, shape checks, uniqueness and immutability triggers;
- implement scoped repositories without cross-module table access;
- verify clean migration and V8-to-head upgrade;
- verify published artifacts reject mutation and unpublished failed Builds remain inspectable.

### P2.2c — Governed Embedding execution

- implement deterministic Spring AI Embedding adapter and golden vectors;
- implement opt-in OpenAI-compatible adapter plus local protocol stub;
- implement route readiness, batching, output/dimension/finite validation;
- implement admission, dispatch, settlement and ambiguous-outcome behavior;
- verify no duplicate Entry or ledger charge under crash/retry scenarios.

### P2.2d — Durable Build and atomic publication

- implement Build create/list/get/retry/cancel APIs;
- implement exact source-set snapshot and canonical request idempotency;
- implement leased Build runner and every durable transition;
- implement manifest/digest validation and one-transaction publication;
- add audit, metrics, health and restart recovery evidence.

### P2.2e — Exact Retrieval Lab

- implement immutable Retrieval Policy publication;
- implement query admission/Embedding and exact scoped cosine SQL;
- implement deterministic tie, threshold, overlap, context budget and `NO_EVIDENCE`;
- expose bounded lineage/content projections;
- verify cross-workspace, tombstone-history, masking and query-retention behavior.

### P2.2f — Acceptance hardening

- run corpus/performance envelope and document supported limits;
- run real-adapter local-stub verification and optional secret-free local provider smoke;
- run migration, architecture, contract, security, Compose and container verification;
- produce matching English/Chinese slice evidence and P2.2 acceptance candidate;
- keep P2 `in-progress`, Knowledge disabled, and P2.3 unstarted until maintainer acceptance.

## 15. Verification matrix

| Area | Minimum proof |
|---|---|
| Architecture | Spring Modulith and ArchUnit allowed/forbidden dependency tests; no provider type in public APIs |
| Contracts | OpenAPI 3.1 validation, conformance and approved correction evidence |
| Migration | Clean install, V8 upgrade, checks, composite scope FKs, triggers and forward mitigation |
| Isolation | Every Build/Version/Policy/retrieval path tested across two tenants/workspaces |
| Route | CHAT backfill, EMBEDDING shape, immutable profile, readiness and Secret failure |
| Embedding | Golden deterministic vectors, order, dimension, finite values, batch/input limits |
| Governance | admission denial, component identity, settle/release, duplicate settle, stale/ambiguous call |
| Jobs | crash before/after dispatch, Entry commit and settlement; lease expiry, retry, cancel, restart |
| Publication | missing/extra/duplicate Entry, wrong lineage/dimension/digest, atomic rollback, mutation rejection |
| Retrieval | exact order, deterministic tie, threshold, topK, overlap, context budget and NO_EVIDENCE |
| History | source tombstone excluded from new Build but preserved in old published Version retrieval |
| Retention | query digest only, bounded/masked content, no raw URL/path/secret/provider error |
| Telemetry | low-cardinality metrics, safe logs, administrative audit and Spring AI observation boundary |
| Performance | documented exact-search corpus/concurrency envelope with query plans and p50/p95/p99 |
| Deployment | PostgreSQL-only mandatory state, no public Worker change, Compose healthy and restart-safe |
| Internationalization | matching English/Chinese plans, evidence and future client keys |

Applicable Java, Testcontainers, OpenAPI, Flyway, security, dependency, container and Compose checks
must pass. TypeScript and Playwright are not required unless a frontend file is changed; P2.2 is not
authorized to make product pages live.

## 16. Rollout and rollback

- migrations are additive and forward-only;
- existing P1/P2.1 binaries ignore the new tables and CHAT behavior remains unchanged;
- `APVERO_KNOWLEDGE_ENABLED=false` remains the default;
- P2.2 can be enabled only in explicit development/verification environments;
- disabling stops new Build claims and Retrieval Lab calls after bounded drain;
- already published Index Versions and failed Builds remain inspectable;
- rollback uses the previous compatible binary and retains all new rows;
- no destructive down migration or automatic vector cleanup is provided;
- the P2-compatible Release rollback floor does not begin until P2.3 creates the first valid RAG
  Release.

## 17. Self-critique and rejected shortcuts

1. A variable-dimension `vector` column is less type-specific than `vector(n)`, but it is necessary
   to avoid one global dimension. Row checks, copied dimension and composite Build linkage restore
   the invariant.
2. Exact cosine search is simple and reproducible, but its latency grows with corpus size. The
   project must publish a bounded envelope and refuse unsupported scale claims.
3. Vector-only retrieval is weak on exact codes, identifiers and some multilingual queries. P2.2
   cannot market itself as hybrid or best-in-class retrieval.
4. The offline deterministic adapter proves orchestration, not semantic relevance. Calling it a
   production model would be dishonest.
5. PostgreSQL leases are at-least-once. Database uniqueness can prevent duplicate rows and ledger
   entries, but it cannot manufacture exactly-once behavior from a non-idempotent remote provider.
6. Stopping on an ambiguous paid request is operationally inconvenient, but automatic blind retry
   risks charging the user twice. Safety wins.
7. Reusing `(index_id, version)` for create idempotency is simple, but it makes semantic version
   reuse a hard conflict. That is desirable for immutable publication.
8. Persisting vectors in the control-plane database increases table size and backup cost. It is
   accepted for the self-hosted PostgreSQL-only baseline, not claimed as the final design at all
   scales.
9. Current P1 Governance assumes an Application and one Route. The extension must remain
   compatibility-first; a broad billing redesign would exceed ADR-0006.
10. Current API scopes are coarse. P2.2 fails closed by workspace but does not pretend to provide
    document-level ABAC.
11. Retrieval Policy needs a frozen algorithm identity. Without it, “immutable policy” would be a
    database label while code upgrades silently changed behavior.
12. Current retention may hide content from an old Index Version. That reduces perfect replay, but
    disclosure safety and legal controls override returning historical plaintext; lineage and
    digests remain.
13. Keeping tombstoned content in an old published Version is necessary for ordinary reproducibility
    but conflicts with permanent erasure. Erasure must be explicit and report the broken artifact,
    not be disguised as tombstone.
14. `latest_ready_version_id` is useful UI metadata but dangerous as an execution input. All
    execution APIs must reject `latest` and require exact identities.
15. Adding a separate batch table would make recovery easier, but it is not in the approved module
    table inventory. Deterministic batch membership plus Governance components is sufficient for
    P2.2; evidence, not convenience, must justify a later table.

## 18. Acceptance gate

P2.2 may be proposed for maintainer acceptance only when this statement is true without
qualification:

> In an authorized workspace, Apvero can build a governed pgvector artifact from an exact immutable
> Source Revision set, recover safely from every persisted failure boundary, publish the complete
> artifact atomically as an immutable Index Version, and run an exact Retrieval Lab query that
> returns deterministic ranked evidence or typed NO_EVIDENCE without leaking workspace data,
> bypassing cost controls, duplicating ledger charges, or claiming unsupported retrieval quality.

Acceptance updates P2.2 evidence only. P2 remains `in-progress`; Knowledge remains disabled by
default; the next milestone is P2.3 Application-to-cited-Run closure.

## 19. Primary implementation references

- Spring AI Embedding Model API:
  <https://docs.spring.io/spring-ai/reference/api/embeddings.html>
- Spring AI Embedding observability:
  <https://docs.spring.io/spring-ai/reference/observability/index.html#_embeddingmodel>
- Spring AI batching strategy:
  <https://docs.spring.io/spring-ai/reference/api/vectordbs.html#_batching_strategy>
- pgvector exact search, dimensions and variable-dimension columns:
  <https://github.com/pgvector/pgvector>
- PostgreSQL 18 constraints:
  <https://www.postgresql.org/docs/18/ddl-constraints.html>
- PostgreSQL 18 trigger behavior:
  <https://www.postgresql.org/docs/18/trigger-definition.html>
