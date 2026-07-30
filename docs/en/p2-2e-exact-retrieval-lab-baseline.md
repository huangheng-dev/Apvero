# P2.2e Exact Retrieval Lab Implementation Baseline

Status: design candidate; maintainer approval is required before business implementation.

## 1. Outcome

P2.2e closes one bounded workflow:

```text
publish immutable Retrieval Policy
  -> authorize exact READY Index Version and Policy Version
  -> admit and account for query Embedding
  -> execute one workspace-scoped exact cosine query
  -> apply deterministic overlap and context-budget rules
  -> disclose bounded, currently authorized evidence
  -> return MATCHES or typed NO_EVIDENCE
```

The slice proves retrieval mechanics and governance. It does not make the Knowledge product page
live, generate an answer, bind an Application draft, or create a production Run.

## 2. Authority and change classification

This baseline implements ADR-0006 and the approved P2.2 plan without changing an invariant,
module boundary, technology baseline, release semantic, security policy, or public contract.
No new ADR is required.

- Stage: P2, slice P2.2e.
- Owning module: `knowledge`.
- Allowed synchronous dependencies: `identity`, `capability-registry`, `governance`.
- Forbidden dependencies: `application`, `release`, `runtime`, and other module internals.
- Stateful dependencies: existing PostgreSQL 18 with pgvector only.
- Public contracts: existing `RetrievalPolicyVersion`,
  `CreateRetrievalPolicyVersionRequest`, `KnowledgeRetrievalTestRequest`,
  `KnowledgeRetrievalResult`, and `KnowledgeRetrievalHit`.
- Migration need: none. P2.2b already created the required immutable policy and index tables.
- Frontend and i18n keys: none in this slice because the page remains non-live until P2.4.
- Documentation: English source and matching Simplified Chinese evidence are mandatory.

Any implementation need that requires another table, ANN index, queue, deployable, framework,
provider type in a public API, or a public-contract change stops the slice and requires protected
change review before coding continues.

## 3. Existing assets and gaps

Already present:

- immutable `retrieval_policy_version` persistence and insert-only trigger;
- READY immutable Index Versions, exact pinned Embedding Route profile, and pgvector Entries;
- provider-neutral Embedding execution and Governance reservation/component contracts;
- workspace and principal scope resolution;
- OpenAPI 3.1 Retrieval Policy and Retrieval Lab contracts marked `contract-only`;
- retention flags for payload retention and sensitive-field masking.

Still required:

- a public Knowledge retrieval boundary and immutable policy catalog;
- publication validation and canonical policy digest;
- governed query Embedding orchestration;
- one exact scoped ranking repository statement;
- deterministic post-ranking policy application;
- bounded disclosure, masking, telemetry, audit, and REST adapters;
- isolation, history, retention, failure, and contract evidence.

The database constraint for `maximum_context_input_units` is intentionally wider than the public
OpenAPI range. The implementation must enforce the public `maxContextTokens` range of
`128..200000`; a permissive storage constraint is not permission to widen the API.

The current Governance catalog returns an effective default Retention Policy with version `0` when
no row exists, while the approved policy provenance contract requires a durable version of at least
`1`. P2.2e therefore needs a backward-compatible Governance public operation that atomically
materializes the existing default as version `1` when absent and otherwise returns the current
persisted version. Knowledge must not write the Governance table or manufacture a version number.

## 4. Public module boundary

The `io.apvero.platform.knowledge` package exposes provider-neutral records and interfaces
equivalent to:

```text
RetrievalPolicyVersionCatalog
  publish(workspaceId, commandContext, request) -> RetrievalPolicyVersion
  list(workspaceId) -> List<RetrievalPolicyVersion>

KnowledgeRetrieval
  retrieve(workspaceId, principal, indexVersionId, policyVersionId, query)
    -> KnowledgeRetrievalResult
```

The result contains the exact Index Version and Policy Version IDs, a SHA-256 query digest,
`MATCHES` or `NO_EVIDENCE`, ordered hits, and elapsed milliseconds. A hit contains only the
contracted immutable lineage, normalized score, content digest, optional bounded content and
authorized anchors.

The boundary must not expose Spring AI, pgvector, jOOQ, provider SDK, database record, Secret
Reference, raw provider response, filesystem path, object-store key, or unrestricted source URL
types.

## 5. Immutable Retrieval Policy publication

### 5.1 Caller-controlled fields

- slug and semantic version;
- `topK` in `1..100`;
- `maxContextTokens` in `128..200000`;
- `minimumScore` in `[0,1]`, accepted only when finite;
- overlap handling: `KEEP` or `COLLAPSE_ADJACENT`.

### 5.2 Platform-controlled fields

- `retrievalAlgorithmVersion = exact-cosine@1.0.0`;
- `tokenEstimatorVersion` equal to the already frozen deterministic estimator;
- current workspace Retention Policy version as publication provenance;
- `emptyEvidenceBehavior = NO_EVIDENCE`;
- canonical policy digest, creator and UTC creation time.

The canonical digest uses SHA-256 over length-prefixed UTF-8 field values in a fixed order. Numeric
values use locale-independent canonical decimal text. It includes every behavior-affecting and
platform-assigned field, but not generated ID, actor or creation time.

Publication semantics:

- equal workspace, slug, version and digest returns the existing version;
- reused slug/version with different content returns a stable conflict;
- equal digest under another slug/version returns a stable duplicate-policy conflict, matching the
  existing uniqueness rule;
- reads and writes are always tenant/workspace scoped;
- published rows are never updated or deleted through ordinary APIs.

Policy publication emits one administrative audit event with identities and digest, never content
or secrets.

Before digest construction, Knowledge obtains the durable current Retention Policy through the
Governance public API. Concurrent first publication must converge on the same default version `1`;
it must not create duplicate rows, return version `0`, or let Knowledge access Governance storage.

## 6. Retrieval execution sequence

One request uses this fail-closed order:

1. reject the request when Knowledge is disabled;
2. resolve authenticated principal and workspace scope;
3. trim only boundary whitespace, reject blank or more than 20,000 Unicode code points, and keep
   the remaining query bytes unchanged for embedding;
4. compute `sha256:` over normalized UTF-8 query bytes; never log the raw query;
5. load the exact READY Index Version and exact Policy Version in the same workspace;
6. resolve the Index Version's pinned READY Embedding Route and verify its immutable profile and
   vector dimension;
7. quote estimated units through `capability-registry`;
8. create a `KNOWLEDGE_QUERY / EMBEDDING_QUERY` Governance reservation and component before
   dispatch;
9. perform one bounded query-Embedding call without an open database transaction;
10. validate exactly one vector, exact dimension, finite elements and non-zero norm;
11. settle actual or estimated usage once using a request-scoped idempotency identity;
12. execute the exact scoped ranking statement;
13. apply overlap and context-budget rules in deterministic rank order;
14. read the current effective Retention Policy through Governance and apply payload suppression
    and masking rules at response projection time;
15. return `MATCHES` when at least one hit remains, otherwise successful `NO_EVIDENCE`;
16. record safe telemetry and finalize the trace.

Admission denial happens before provider dispatch. A provider failure settles the component as
failed when its outcome is known. An ambiguous paid outcome is not blindly retried and returns the
existing stable reconciliation-required failure family.

Retrieval Lab requests are synchronous and are not persisted as a new Knowledge query table.
Governance records are the durable billing evidence; the response and trace carry retrieval
evidence for this laboratory operation.

## 7. Exact ranking kernel

The repository performs one SQL statement joining the requested READY Index Version through its
Build to Entries and immutable Chunk lineage. These predicates are inside that statement before
ranking and limiting:

- tenant ID;
- workspace ID;
- exact Index Version ID;
- `READY` Version status;
- Build and Entry composite scope;
- query vector dimension equal to the published Version dimension;
- cosine similarity at or above the immutable policy threshold.

Ordering is:

```text
cosine distance ascending
  -> immutable chunk UUID ascending
```

The database distance is the ordering source. The exposed score is
`clamp(1 - cosine_distance, 0, 1)`. Java must not re-sort floating-point scores, globally retrieve
candidates, or filter workspace scope after ranking.

The SQL limit is exactly `topK`. P2.2e adds no hidden oversampling or backfill. Current Source
tombstone status is deliberately absent: an old published Index Version remains retrievable.
Current authorization and disclosure policy still apply at read time.

## 8. Deterministic post-ranking rules

### 8.1 Overlap

`KEEP` retains the SQL order.

`COLLAPSE_ADJACENT` compares a candidate only with already accepted hits from the same immutable
Document. Two chunks overlap when their stored character ranges intersect. When range metadata is
not available, they are not guessed to overlap. The earlier ranked hit wins; equal-distance order
is already resolved by chunk UUID.

No discarded hit is replaced from outside the SQL `topK`.

### 8.2 Context budget

The pinned estimator computes units from the exact content eligible for disclosure. Accepted hits
are considered in rank order:

- a hit is included only when its full bounded content fits the remaining budget;
- content is never silently truncated to manufacture a fit;
- an oversized hit is skipped and later hits remain eligible, without changing their relative
  order;
- metadata-only hits consume zero content units but remain subject to `topK`;
- response rank is reassigned consecutively after filtering.

The algorithm above is part of `exact-cosine@1.0.0`. Changing it requires a new algorithm version,
not a silent code edit.

### 8.3 Empty evidence

Threshold filtering, overlap collapse, context limits, or current disclosure policy may leave no
hits. That is a successful `NO_EVIDENCE` response with an empty list, never an exception and never
permission for an ungrounded fallback.

## 9. Disclosure and retention

The response may expose only:

- public lineage IDs;
- rank, normalized score and content digest;
- bounded content of at most 20,000 characters per hit when current policy permits payloads;
- bounded source title/type and page, heading, paragraph, and line anchors.

When `retainPayloads=false`, content is `null`; lineage and digest remain. When
`maskSensitiveFields=true`, the Governance disclosure decision is applied before both response
projection and any observation export. The current platform has no approved unstructured-text
masker, so P2.2e fails closed by suppressing content while that flag is enabled. It must not invent
a second masking vocabulary inside Knowledge or imply that a few regular expressions constitute
enterprise DLP. A future reusable masker requires its own reviewed behavior and tests.

The response never exposes snapshot bytes, internal table keys, local/object paths, Secret
References, provider messages, provider request IDs, raw source URLs, or cross-workspace existence
hints. Unauthorized or differently scoped resources use the existing indistinguishable scoped
not-found behavior.

## 10. Errors, audit and telemetry

Stable error cases include disabled capability, invalid request, scoped resource not found, Index
Version not READY, invalid or unavailable Route, admission denial, query too large, provider
failure, ambiguous outcome, invalid vector, dimension mismatch, and settlement conflict. Backend
messages remain codes plus safe arguments; clients own localization.

Administrative audit is emitted for policy publication. High-volume retrieval tests do not produce
administrative audit spam; they produce typed trace/operation evidence and Governance ledger
records.

Low-cardinality metrics:

- retrieval request count and outcome;
- end-to-end and provider latency;
- ranked and returned hit counts;
- coarse score buckets;
- `NO_EVIDENCE` count;
- admission, provider, validation and settlement failure families.

Tenant/workspace IDs, query or content, Route/Index/Policy IDs, URLs and provider request IDs are
forbidden metric labels. Raw queries and returned content are forbidden in normal logs.

## 11. Implementation checkpoints

### P2.2e-1 — Policy publication

- public policy records, catalog and REST adapter;
- strict validation, platform-assigned fields and canonical digest;
- backward-compatible Governance operation that materializes/returns a durable Retention Policy
  version without cross-module database access;
- scoped idempotency/conflict behavior, audit and repository tests.

### P2.2e-2 — Exact retrieval kernel

- public retrieval result/hit boundary;
- one scoped pgvector cosine statement and bounded projection;
- deterministic score, tie, threshold and tombstone-history behavior;
- Testcontainers isolation and query-plan evidence.

### P2.2e-3 — Governed query execution

- pinned Route resolution, quotation, reservation, dispatch and settlement;
- vector validation, failure normalization and no blind retry;
- local deterministic adapter and protocol-stub tests.

### P2.2e-4 — Policy application and disclosure

- overlap collapse, estimator budget and typed `NO_EVIDENCE`;
- current retention, masking-or-suppression, safe locator policy and REST conformance;
- unit/property tests for English, Simplified Chinese, mixed Unicode and boundary inputs.

### P2.2e-5 — Slice verification candidate

- architecture, Java, Testcontainers, OpenAPI, security, telemetry and Compose checks;
- matching English/Chinese verification evidence;
- stage evidence prepared for maintainer acceptance without marking P2.2 complete.

Each checkpoint is a coherent verified commit. Business implementation uses a `feature/` branch
created only after this baseline is approved; this planning branch remains separate.

## 12. Verification matrix

| Area | Required proof |
|---|---|
| Architecture | Modulith and ArchUnit preserve only the three approved Knowledge dependencies |
| Policy | immutable publication, canonical digest, durable retention provenance, idempotent replay, conflict and range boundaries |
| SQL | scope predicates precede ranking/limit; exact order, threshold, dimension and `topK` |
| Isolation | two tenants and two workspaces cannot infer or retrieve each other's artifacts |
| History | tombstoned Source excluded from new Builds but retained in old READY Version retrieval |
| Governance | denial before dispatch; one reservation/component; success, failure and ambiguity settlement |
| Determinism | tie, overlap, budget, Unicode normalization and consecutive final ranks |
| Retention | query digest only; payload suppression; masking; no raw URL/path/secret/provider leakage |
| Errors | every failure path returns an existing stable safe code; `NO_EVIDENCE` is success |
| Telemetry | low-cardinality labels, safe logs, metrics and trace boundaries |
| Contracts | OpenAPI 3.1 validation and controller conformance without schema drift |
| Deployment | PostgreSQL remains the sole mandatory stateful dependency; disabled-by-default Compose is healthy |

P2.2f owns the measured corpus/concurrency support envelope. P2.2e records representative query
plans and guards against obvious full-platform scans, but must not publish an unmeasured scale
claim.

## 13. Rollout and rollback

- no migration or destructive data change;
- `APVERO_KNOWLEDGE_ENABLED=false` remains the default;
- disabling Knowledge rejects new Retrieval Lab calls;
- published policies and Index Versions remain inspectable and immutable;
- rollback uses the previous compatible binary and retains existing rows;
- Retrieval Lab remains a non-production laboratory endpoint until later acceptance enables its
  product surface.

## 14. Self-critique and rejected shortcuts

1. Exact vector retrieval is reproducible but weak for exact identifiers and large corpora. P2.2e
   must not claim hybrid retrieval, semantic excellence, or arbitrary scale.
2. Applying a context budget after SQL `topK` can return fewer hits. Hidden oversampling would make
   behavior less obvious and is rejected for algorithm version 1.
3. Skipping an oversized hit can admit a later smaller hit. This uses more of the explicit budget
   while preserving relative order, and is frozen in the algorithm identity.
4. The existing Retention Policy provides coarse flags, not a mature DLP engine. Until an approved
   shared masker is available, suppressing content is safer than claiming masking.
5. A synchronous query has no dedicated durable query row. Adding one only for observability would
   violate the approved table inventory; Governance and trace evidence are sufficient here.
6. Database constraints are broader than the public context-budget contract. Implementation must
   validate the narrower public range; contract drift is not accepted as convenience.
7. A deterministic offline Embedding adapter proves orchestration, not relevance. Quality claims
   require later evaluation against real datasets.
8. Historical reproducibility and permanent erasure can conflict. Tombstone alone preserves old
   artifacts; explicit erasure must later report broken reproducibility rather than silently alter
   results.
9. `NO_EVIDENCE` is deliberately conservative. Returning a fluent answer without retained evidence
   would break Apvero's closed-loop and release invariants.

## 15. Exit statement

P2.2e is eligible for maintainer acceptance only when:

> In one authorized workspace, Apvero can publish an immutable Retrieval Policy and use it with an
> exact READY Index Version to perform a governed, deterministic, PostgreSQL-scoped cosine query
> that returns bounded currently authorized evidence or typed NO_EVIDENCE, without cross-workspace
> leakage, raw-query retention, hidden ranking behavior, duplicate cost settlement, or unsupported
> quality claims.

Acceptance completes only P2.2e. P2.2 remains in progress until P2.2f acceptance hardening passes.
