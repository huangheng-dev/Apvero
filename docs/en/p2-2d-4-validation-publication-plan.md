# P2.2d-4 Validation and Atomic Publication — Implementation Plan

Status: implementation candidate; business coding not started

Target: P2 / P2.2d-4

Authority: ADR-0006, the approved P2.2d durable Build baseline, V10/V11 persistence and guards,
and the verified P2.2d-3 governed Embedding orchestration

Reasoning level: high

## 1. Outcome

P2.2d-4 closes the internal immutable-index build path after Embedding:

```text
leased INDEXING Build
  -> reconstruct the exact frozen source/chunk/entry manifest
  -> verify cardinality, ordering, lineage, route and vector evidence
  -> persist validation evidence and move to VALIDATING
  -> reclaim VALIDATING Build
  -> recompute all publication-critical evidence
  -> atomically publish one immutable READY Index Version
  -> link Build and update Index display metadata
  -> append Governance audit in the same transaction
```

The slice is complete when a READY Version can exist only for one structurally complete,
reproducible artifact and every crash, retry, stale lease and two-publisher race has a deterministic
outcome.

It does not activate the production runner, make Index Version REST operations live, implement
Retrieval Lab, bind Applications, change ReleaseBundle, add frontend behavior, or start P2.2e.

## 2. Change declaration

| Item | Decision |
|---|---|
| Stage | P2 / P2.2d-4, `in-progress` |
| Primary module | `knowledge` |
| Supporting modules | Existing `identity`, `capability-registry`, `governance` public APIs |
| Allowed dependencies | Knowledge → Identity, Capability Registry, Governance |
| REST / OpenAPI / JSON Schema | No change |
| Database migration | None; V10/V11 already contain the rows, keys and guards |
| Stateful dependency / deployable | None |
| AI abstraction / provider call | None; validation and publication are local |
| Frontend / Python | No change |
| Exposure | Internal and disabled; no live product claim |

ADR-0006 already authorizes immutable validation and atomic publication. This plan changes no
invariant, module boundary, public contract, release semantic, security policy or technology
baseline, so no new ADR is required. If implementation requires another table, column, deployable,
stateful dependency, module dependency or public contract, coding stops and returns to architecture
review.

## 3. Existing authority that must be reused

- `KnowledgeIndexBuildTransitionKernel` remains the lease-fenced owner of Build transitions and
  terminal failure.
- `KnowledgeIndexPersistenceRepository` remains the Knowledge-owned persistence seam.
- `KnowledgePersistenceRepository` remains the Knowledge-owned read seam for immutable source
  revisions, documents and chunks.
- `EmbeddingRouteCatalog` is the provider-neutral metadata read seam used during validation;
  publication does not call the execution-oriented `EmbeddingCapability`.
- P2.2d-3 canonical order remains:
  `sourceSetOrdinal -> document.ordinal -> chunk.ordinal -> chunk.id`.
- P2.2d-3 stable Entry identity and exact float32 vector digest remain authoritative. The digest
  implementation moves to one Knowledge-internal canonical helper instead of being duplicated.
- `AuditEventCatalog` remains the only publication audit boundary. Knowledge never writes
  Governance tables.
- V11 database guards remain the final database backstop for Build transitions, late Entry writes,
  Version insertion and terminal immutability.

No second source ordering algorithm, vector encoding, lease implementation, publication table or
audit store is introduced.

## 4. Pre-coding corrections and repository seams

### 4.1 One canonical artifact validator

Add one package-internal, pure validation component that accepts a scoped Build plus repository
evidence and returns an immutable `ValidatedIndexArtifact`. It does not mutate state, call a
provider, consult logs or infer missing evidence.

The returned artifact contains only publication-critical values:

- exact Build, Index and requested Version identities;
- ordered frozen Build Revisions;
- ordered document/chunk identities and content digests;
- ordered Entry identities, input digests and recomputed vector digests;
- exact Route ID/reference and pinned profile;
- canonical source, chunk and Entry counts;
- validation and artifact digests.

Both INDEXING and VALIDATING use the same validator. The VALIDATING publication path constructs a
fresh artifact from database rows and never accepts the previous counter or digest as proof.

### 4.2 Atomic publication repository operations

Add narrow Knowledge-internal operations for:

1. locking the scoped Build and then its scoped Index;
2. persisting the final artifact digest while the Build remains leased `VALIDATING`;
3. inserting or reading the deterministic Version;
4. moving Build to `READY / COMPLETE` and linking the Version;
5. updating Index `latest_ready_version_id`, `version_count` and `metadata_version`.

The service coordinates these operations in one Spring `REQUIRED` transaction and calls the public
Governance audit facade before commit. There is no repository method that hides a cross-module
table write.

### 4.3 V11 lock-version consequence

V11 requires `Build.artifact_digest` to exist before the Version insert trigger executes. Therefore
one successful publication transaction intentionally performs two fenced Build mutations:

1. `VALIDATING -> VALIDATING`: set `artifact_digest`, increment `lock_version` once;
2. `VALIDATING -> READY / COMPLETE`: set `published_version_id`, final counts/time and increment
   `lock_version` once again.

The Version insert and both increments are in the same transaction. An insert, Index update or
audit failure rolls all of them back. Tests must assert this exact behavior so a future
“optimization” cannot break the database publication guard.

## 5. Canonical validation manifest

Validation reconstructs the selected artifact from immutable rows, never from mutable counters.
It rejects the Build unless every rule below holds.

### 5.1 Scope and frozen source set

- every row matches the same tenant, workspace, Knowledge Base, Index and Build;
- Build Revision count equals `requested_source_count`;
- `source_set_ordinal` is unique and contiguous from zero;
- each revision matches its frozen source revision, content digest, parser version and chunker
  version;
- recomputing the approved P2.2d source-set digest equals `Build.source_set_digest`;
- current Source active/tombstoned state and “latest revision” do not change the frozen snapshot.

A source may be tombstoned after Build creation without corrupting an old immutable snapshot.
Validation checks pinned evidence, not current discoverability.

### 5.2 Documents and chunks

- each selected Chunk belongs to exactly one selected Source Revision and existing Document;
- document ordinals are stable inside a revision;
- chunk ordinals and IDs reproduce the approved canonical order;
- canonical Chunk count equals `requested_chunk_count`;
- no selected Chunk is missing and no unselected Chunk is introduced;
- Chunk content digest is recomputed from the stored normalized text using the already-approved
  content-digest algorithm and must match the stored digest.

### 5.3 Entries

- exactly one Entry exists for every canonical Chunk and no extra Entry exists;
- Entry ordinals are unique, contiguous and equal to canonical Chunk ordinals;
- stable Entry ID equals the domain-separated deterministic ID derived from Build ID and Chunk ID;
- source, revision, document, chunk, Knowledge Base, Index and Build lineage is exact;
- `normalized_input_digest` equals the exact Chunk content digest;
- Entry Route ID/reference equals the Build pin;
- dimension equals both the Build pin and vector length;
- every float is finite and the vector has a non-zero norm, as required by ADR-0006;
- vector digest is recomputed from IEEE-754 float32 bits in big-endian order and equals the stored
  digest;
- batch ordinal remains non-negative and consistent with the durable P2.2d-3 batch grouping.

The pinned normalization token is validated as Route profile identity. P2.2d-4 does not invent a
new unit-length tolerance that is absent from the approved Embedding contract.

### 5.4 Route profile

The validator compares the Build pin with the provider-neutral Route snapshot:

- tenant/workspace and Route ID/reference;
- vector dimension;
- maximum input tokens;
- maximum batch size;
- normalization.

Current provider readiness or enabled state does not invalidate an already embedded artifact.
Validation reads `EmbeddingRouteCatalog`, not the availability-enforcing execution facade.
Publication performs no provider call and does not resolve “latest.” A missing or
identity-changing Route record fails closed because the pinned artifact can no longer be proven.

## 6. Digest specification

New manifest digests reuse the existing Knowledge digest encoding: domain-separated,
length-prefixed SHA-256.

```text
string:  4-byte signed non-negative big-endian byte length + UTF-8 bytes
UUID:    4-byte length (16) + two big-endian 64-bit components
integer: 4-byte length (4) + one big-endian 32-bit value
```

Enums use their exact approved tokens. Optional values use an explicit typed presence marker,
never an empty string. Timestamps, database row order, JSON serialization, locale, timezone and
process identity are excluded. Existing request and source-set digest bytes do not change.

### 6.1 Shared primitive digests

- normalized Chunk input: reuse the existing canonical Chunk content digest;
- vector: `sha256:<lowercase-hex>` over exact IEEE-754 float32 bit patterns in big-endian order;
- deterministic IDs: SHA-256 domain-separated UUID with RFC 4122 version/variant bits, reusing the
  P2.2d-3 algorithm.

The private P2.2d-3 vector and stable-ID helpers move to one Knowledge-internal canonical utility;
all old tests remain compatibility fixtures.

### 6.2 Validation digest

Domain: `apvero-knowledge-index-validation-v1`.

The validation manifest commits to:

- Build/Index/Knowledge Base identity and requested version;
- source-set digest and pinned Route/profile;
- ordered Build Revision identities and source evidence;
- ordered document/chunk identities, ordinals and content digests;
- ordered Entry identities, ordinals, lineage, input and recomputed vector digests;
- canonical source/chunk/Entry counts.

The INDEXING step persists this digest and `validated_entry_count`.

### 6.3 Artifact digest

Domain: `apvero-knowledge-index-artifact-v1`.

The immutable artifact digest commits to the exact Route/profile, ordered frozen source snapshot,
ordered Chunk identities/content digests, ordered Entry identities/vector digests and canonical
counts. It excludes operational state such as lease owner, attempts, validation time and audit ID.

VALIDATING recomputes both digests. The new validation digest must equal the one persisted by
INDEXING; any mismatch is an integrity failure. The artifact digest is then frozen on Build and
Version.

### 6.4 Deterministic Version identity and reference

- Version ID uses the existing SHA-256 deterministic UUID algorithm with domain
  `apvero:knowledge-index-version:<build-id>`;
- Version string is the Build's exact `requested_version`;
- reference is the canonical semantic reference `<index-slug>@<requested-version>` required by the
  accepted contract, never a provider resource or `latest`;
- `published_at` comes from PostgreSQL `transaction_timestamp()`, not the Java clock.

An equal replay compares every persisted Version field and digest. Identity equality alone is
insufficient.

## 7. INDEXING one-claim flow

Add one package-internal INDEXING orchestrator that processes one already claimed Build and returns
a bounded typed outcome. It is not a scheduler.

```text
PREP  reconstruct and validate the entire immutable artifact; no provider I/O
TX-A  lock and require the exact active INDEXING lease/version
      recheck the Build identity and persisted evidence
      persist validated count/digest
      move to VALIDATING and release the lease
```

Entry insertion is already forbidden after EMBEDDING, and revisions/documents/chunks are immutable,
so PREP may perform the O(entries) scan without holding a row lock. TX-A still uses database time,
expected owner, expected `lock_version`, exact state and step. A stale worker cannot persist the
digest.

Validation failure uses the existing d-2 failure kernel with a stable bounded category. Structural
or digest corruption is non-retryable. Transient database availability may enter the existing
retry policy. No failure path invents or deletes Entry evidence.

## 8. VALIDATING and atomic publication

Add one package-internal publication coordinator for one already claimed VALIDATING Build.

### 8.1 Transaction order

One Spring `REQUIRED` transaction performs:

1. lock scoped Build `FOR UPDATE`;
2. lock its scoped Index `FOR UPDATE` in that stable order;
3. if Build is already READY, enter the equal-replay check in section 8.3;
4. otherwise require `VALIDATING / VALIDATING`, expected lease owner, expected lock version and
   `lease_until > transaction_timestamp()`;
5. require Index still `ACTIVE`;
6. require Index `version_count` and current pointer to match its existing scoped READY Versions;
7. reload and fully validate all publication-critical rows inside the transaction;
8. require recomputed validation digest equals the persisted INDEXING digest;
9. set final `artifact_digest` and increment Build lock version;
10. insert the deterministic immutable READY Version;
11. set Build `READY / COMPLETE`, link Version, set canonical counts and database completion time,
    clear lease/error/retry state and increment lock version again;
12. update Index pointer/count/metadata version using the locked Index row;
13. append `knowledge.index-version.published` through `AuditEventCatalog`;
14. commit.

Full validation inside the transaction is intentionally O(entries). Correct publication takes
priority over lock duration in P2. The supported corpus and transaction envelope are measured and
documented in P2.2d-5; the implementation must not claim arbitrary scale.

### 8.2 Audit

The publication audit uses:

- actor: `apvero-index-build-runner`;
- action: `knowledge.index-version.published`;
- resource type: `knowledge-index-version`;
- resource ID: deterministic Version ID;
- outcome: `SUCCEEDED`;
- source IP: absent;
- trace: deterministic bounded trace derived from Build ID.

`AuditEventCatalog.append` joins the publication transaction with normal `REQUIRED` propagation.
An audit exception aborts Version, Build and Index mutations. High-volume validation progress is
not written to the administrative audit ledger.

### 8.3 Equal replay and two publishers

If a response is lost after commit or a second publisher waited on the Build lock:

1. reload the READY Build, linked Version and Index;
2. recompute or use the caller's freshly computed complete artifact;
3. compare every Version identity, scope, Build/Index link, requested version, reference,
   Route/profile, counts, status and artifact digest;
4. require Build link/digests/counts to be self-consistent;
5. require Index count to equal its scoped Version rows and its current pointer to reference a
   scoped READY Version; a newer valid publication may legitimately be the current pointer;
6. return the existing Version without another mutation or audit event.

Any difference is `APVERO_KNOWLEDGE_PUBLICATION_CONFLICT`. A second Version, duplicate audit event,
counter increment or pointer rewrite is forbidden.

The Index “latest” pointer follows serialized successful publication commit order. It is display
metadata only and must never resolve a Runtime or Release; those always use exact Version identity.

## 9. Failure, retry and crash matrix

| Boundary or evidence | Required outcome |
|---|---|
| Missing/extra/duplicate revision, Chunk or Entry | non-retryable validation failure; no Version |
| Wrong scope or lineage | fail closed with the scoped integrity family |
| Route/profile/dimension/input/vector mismatch | non-retryable integrity failure |
| Non-finite or zero-norm vector | non-retryable vector integrity failure |
| Validation digest changes between steps | fail; do not overwrite prior evidence |
| Lease expires before mutation | stale worker performs no durable mutation |
| Crash before INDEXING transition commit | Build remains INDEXING; recompute safely |
| INDEXING transition commits, response lost | Build is VALIDATING; next claim continues |
| Crash before publication transaction | Build remains VALIDATING |
| Failure after artifact update but before commit | artifact/lock increment rolls back |
| Version insert or Index update fails | entire publication rolls back |
| Governance audit fails | entire publication rolls back |
| Publication commits, response lost | equal replay returns linked Version |
| Two publishers race | one commits; equal loser returns existing; mismatch conflicts |
| Index archived before publication | fail closed; do not publish into archived Index |

Tests inject transaction rollback at each publication statement, not only before and after the
service method.

## 10. Stable errors and bounded outcomes

Reuse existing scoped not-found, illegal transition, stale lease and concurrent-modification
families. Add only narrow internal stable codes where the current vocabulary cannot distinguish:

- artifact membership/cardinality integrity;
- ordinal/lineage integrity;
- input/vector digest integrity;
- Route/profile integrity;
- validation digest drift;
- archived Index publication;
- `APVERO_KNOWLEDGE_PUBLICATION_CONFLICT`;
- audit-backed publication rollback.

Exceptions and typed outcomes contain bounded categories only. They never include source text,
vectors, URLs, provider bodies, Secret references, tenant/workspace/Build/Chunk IDs or raw database
errors in telemetry labels.

## 11. Security and tenant isolation

- every repository predicate includes tenant and workspace scope;
- Build and Index locks are both scoped before any existence is revealed;
- all revision, document, chunk, Entry and Version rows must match the scoped aggregate;
- cross-workspace identifiers use the same not-found/integrity behavior as absent records;
- publication uses no provider credential, Base URL, network call or mutable provider resource;
- vectors and source content are validated locally but never logged or placed in audit metadata;
- authorization is unchanged because no new public operation is exposed;
- immutable READY Version and terminal Build rows remain database guarded.

## 12. Verification plan

### 12.1 Unit and property tests

- canonical ordering remains identical under shuffled repository rows;
- length-prefix encoding distinguishes ambiguous field concatenations;
- digests are stable under locale, timezone and default-charset variants;
- vector digest fixtures prove `-0.0`, NaN rejection, infinities, float32 bits and big-endian order;
- deterministic ID/reference fixtures remain stable across JVM runs;
- every membership, lineage, ordinal, route, count and digest corruption fails;
- INDEXING never trusts stored counters alone.

### 12.2 PostgreSQL/Testcontainers tests

- complete artifact advances INDEXING to VALIDATING with a fenced lease;
- stale/expired lease cannot write validation evidence;
- publication persists Version, Build, Index and audit atomically;
- V11 causes exactly two Build lock-version increments inside one successful publication;
- failure injection after each mutation rolls the complete transaction back;
- Version rows and READY Builds remain immutable;
- late Entry insertion cannot race publication;
- equal replay returns one Version; conflicting replay fails;
- two-publisher and two-Index publication races follow stable lock order;
- archived Index, cross-workspace row and composite-key mismatches fail closed;
- clean V11 install and V10-to-V11 upgrade remain green with no new migration.

### 12.3 Architecture and cumulative regression

- Spring Modulith and ArchUnit preserve Knowledge's allowed dependencies;
- no Knowledge class imports Governance internals or provider SDK types;
- P1 CHAT, P2.1 ingestion, P2.2a/b/c and P2.2d-1/2/3 suites remain green;
- OpenAPI still exposes only the accepted live Build operations; Version list remains
  `contract-only`;
- Java unit/integration tests, formatting, `bootJar`, Compose health and security/dependency checks
  pass;
- frontend and Python remain unchanged but their cumulative checks stay green.

P2.2d-4 adds focused implementation tests. Matching bilingual final verification evidence belongs
to P2.2d-5.

## 13. Implementation checkpoints

1. **d4.1 — Canonical manifest and digest primitives**
   - centralize stable ID/vector digest compatibility;
   - implement complete artifact reconstruction and pure corruption tests.
2. **d4.2 — INDEXING validation transition**
   - add one-claim INDEXING coordinator;
   - persist validation count/digest through the existing lease fence.
3. **d4.3 — Atomic publication transaction**
   - add locked repository operations, deterministic Version and Governance audit;
   - prove all-or-nothing Build/Version/Index/audit behavior.
4. **d4.4 — Recovery, concurrency and cumulative gate**
   - prove response-loss replay, two-publisher race, archived Index, scope isolation and every
     publication rollback boundary;
   - run cumulative architecture and regression verification.

These are coherent implementation checkpoints inside one P2.2d-4 implementation branch/PR, not
separate product stages and not permission to activate P2.2d-5.

## 14. Rollout and rollback

- `APVERO_KNOWLEDGE_ENABLED=false` remains the outer fail-closed default;
- the separate Build runner remains disabled, so no automatic claim starts;
- before the first READY Version, rollback may use the previous compatible binary while retaining
  V10/V11 rows;
- after a READY Version exists, the ADR-0006 P2-compatible rollback floor applies;
- rollback never deletes or rewrites Build Revisions, Entries, Versions, Index pointers or audit
  evidence;
- disabling Knowledge must make pinned future RAG execution fail explicitly, never fall back to
  ungrounded chat.

## 15. Self-critique and rejected shortcuts

1. Full validation inside the publication transaction increases lock duration. Moving all checks
   outside would make the atomic proof weaker; P2 accepts bounded O(entries) cost and measures the
   envelope in d-5.
2. PostgreSQL guards cannot independently recompute every Java digest. The implementation therefore
   needs both application validation and relational transition/immutability guards.
3. Two lock-version increments in one transaction are less visually simple than one. V11's
   Version-insert guard makes them necessary; hiding the first update or weakening the trigger is
   rejected.
4. `latest_ready_version_id` is convenient but dangerous if treated as runtime resolution. It stays
   display metadata and exact Version pins remain mandatory.
5. READY proves structural completeness and reproducibility, not retrieval relevance. No semantic
   quality claim is made before P2.2e.
6. Current Route readiness and current Source status are deliberately not publication inputs.
   Requiring either would make frozen artifacts depend on mutable external state.
7. A digest is integrity evidence, not authorization or encryption. Scope checks, policy and
   immutable rows remain mandatory.
8. No new attempt-history, manifest-blob or publication-outbox table is added. Existing typed Build,
   Version, Entry and audit evidence is sufficient for this bounded stage; adding storage without a
   demonstrated gap would weaken the self-hosted baseline.

Rejected shortcuts include trusting counts, publishing partial Entries, resolving `latest`,
rewriting an equal Version, auditing after commit, performing provider I/O during validation,
cross-module SQL, making the Version API live, and adding infrastructure to imitate a larger
platform.

## 16. Approval gate

Maintainer approval of this plan authorizes only the P2.2d-4 implementation described above. It
does not authorize P2.2d-5 runner activation/operations, P2.2e Retrieval Lab, frontend activation,
Application/Release/Run changes, a new migration/table/dependency/deployable, or public contract
changes.
