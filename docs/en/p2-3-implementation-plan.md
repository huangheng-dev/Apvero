# P2.3 Application to Cited Run Closure — Implementation Plan

Status: implementation baseline under maintainer review; no P2.3 business slice is implemented

Target stage: P2, milestone P2.3

Decision baseline: ADR-0006 (accepted)

Reasoning level used for this plan: high

Feature flag: `APVERO_KNOWLEDGE_ENABLED=false` until the full P2 acceptance change

## 1. Outcome

P2.3 closes one reproducible server-side workflow:

```text
RAG Application draft
  -> ordered opaque Knowledge version bindings
  -> release-time workspace and READY validation
  -> immutable ReleaseBundle Manifest 1.1
  -> governed Run admission
  -> exact pinned retrieval
  -> persisted ordered evidence
  -> bounded untrusted context with deterministic [K1] markers
  -> pinned chat generation
  -> structured Grounded Answer validation
  -> verified citations or typed NO_EVIDENCE
  -> retention-aware evidence inspection
```

The milestone is not complete when a binding table, a release manifest, or a retrieval call works in
isolation. It must prove that a production Run reads only an immutable ReleaseBundle, that every
citation maps to evidence retained for that Run, and that every failure stays explicit and
reproducible.

P2.3 does not make Knowledge, Studio, Releases, Playground, or Run projections live in the Console.
That bilingual product and operations gate remains P2.4. Contract-only endpoints may become
server-implemented during P2.3, but partial behavior stays disabled and cannot present mock success.

## 2. Required change declaration

| Item | P2.3 plan |
|---|---|
| Stage | P2 / P2.3, currently `in-progress`; every implementation slice remains `planned` |
| Primary modules | `application`, `release`, `runtime` |
| Supporting modules | `knowledge`, `capability-registry`; existing `identity` and `governance` behavior is consumed through approved facades |
| Allowed dependencies | `release -> application, capability-registry, knowledge`; `runtime -> application, release, capability-registry, knowledge`; `application -> none` |
| Forbidden dependencies | No `application -> knowledge`; no cross-module repository or table access; no `runtime -> governance`; no provider SDK types in public APIs |
| Public contracts | Application draft Knowledge bindings, Manifest 1.1, Grounded Answer 1.0, Citation 1.0, Run retrieval evidence |
| Migrations | Forward-only additive migrations after V11 |
| New stateful dependency | None; PostgreSQL remains the only mandatory stateful dependency |
| New deployable or module | None |
| AI abstraction | Spring AI 2.0 remains the single core Java AI abstraction |
| Product exposure | Disabled/non-live until P2.4 |
| Frontend work | None in P2.3 implementation slices |

This plan does not change a product invariant, module boundary, security policy, release semantic, or
technology baseline. ADR-0006 already authorizes the listed P2.3 boundary and contract corrections.
Any implementation that needs a different dependency, table owner, runtime fallback, framework, or
release meaning must stop and propose a new ADR.

## 3. Authority reconciliation required before implementation

### 3.1 Application bindings are opaque draft references

`application` owns mutable draft configuration but has no allowed dependency on `knowledge`.
Therefore its write path can validate only:

- authenticated Application/workspace ownership through the existing Application boundary;
- non-null UUID shape;
- at most 16 bindings;
- stable zero-based binding order;
- no duplicate `(indexVersionId, retrievalPolicyVersionId)` pair;
- optimistic concurrency on the Application draft.

It cannot claim that a referenced Knowledge version exists, belongs to the workspace, or is READY.
Those authoritative checks belong to `release`, which may call `knowledge`.

The contract-only OpenAPI response currently includes canonical Knowledge references and says that
the Application write bound “authorized READY versions.” Implementing that wording would either
create a forbidden `application -> knowledge` dependency or trust client-supplied canonical
references. Both are rejected.

The first P2.3 contract correction will:

- make the Application binding response contain IDs and order only;
- describe draft writes as opaque selections, not server-confirmed READY resources;
- keep readiness and canonical reference resolution in release validation;
- let a future UI query the Knowledge catalog separately when it needs selection metadata.

Because the affected endpoint is still `contract-only`, this correction migrates no live client.

### 3.2 Knowledge needs public exact-version resolution

Knowledge already owns immutable `knowledge_index_version` rows and Retrieval Policy versions, but
its public Java catalogs do not expose exact workspace-scoped lookup for release validation.
P2.3 adds provider-neutral public projections and queries equivalent to:

```text
KnowledgeIndexVersion getIndexVersion(workspaceId, indexVersionId)
RetrievalPolicyVersion getPolicyVersion(workspaceId, policyVersionId)
```

The implementation remains inside Knowledge and reuses its scoped repositories. Cross-workspace
identifiers fail with the same stable not-found/denied behavior and never disclose existence.
Release never reads Knowledge tables directly.

### 3.3 Manifest 1.1 must match implemented version identities

The contract-only Manifest 1.1 schema currently applies semantic-version syntax to every pinned
reference. The implemented immutable Model Route and Prompt identities use `name@positive-integer`.
ADR-0006 explicitly requires this mismatch to be corrected before Manifest 1.1 becomes live.

The correction will use field-specific reference definitions:

- Model Route and Prompt keep their existing exact `name@positive-integer` identity;
- Knowledge Index and Retrieval Policy use exact semantic-version references;
- already established placeholder or aggregate identities remain exact and never permit `latest`;
- no second Model Route or Prompt version system is introduced.

Manifest 1.0 remains the legacy live CHAT contract. Existing immutable rows are never rewritten.
The OpenAPI release projection will explicitly support reading both 1.0 and 1.1, while new RAG
release creation accepts only a fully valid 1.1 manifest.

### 3.4 Complete JSON Schema validation

The current Release validator checks only required field names and scans for `latest`. P2.3 replaces
that shallow check for new writes with complete JSON Schema Draft 2020-12 validation, including
conditional CHAT/RAG rules, closed objects, cardinality, formats, ranges, and referenced schemas.

Before adding a validator dependency, the implementation slice must record:

- the selected maintained library and exact version through the version catalog;
- support for Draft 2020-12 and external `$id` resolution without network access;
- an allowlisted in-process schema registry;
- dependency and license scan results;
- failure normalization to stable Apvero error codes.

Remote schema fetching at validation time is forbidden.

## 4. Ownership and dependency flow

```text
Application
  owns mutable binding IDs/order
        |
        v public Application API
Release
  resolves Application draft
  -> Knowledge public exact-version queries
  -> Capability Registry exact route/prompt queries
  -> validates Manifest 1.1
  -> stores immutable manifest + digest
        |
        v public Release API
Runtime
  resolves immutable ReleaseBundle only
  -> Knowledge public retrieval
  -> Capability Registry execution facade
  -> owns Run evidence and Grounded Answer outcome
```

Truth boundaries:

- Application is the source of mutable draft selection, not Knowledge readiness.
- Knowledge is the source of immutable Index/Policy identity and retrieval results.
- Release is the source of immutable production pins and release digest.
- Runtime is the source of Run state, ordered evidence, citation validation, usage, and failure.
- Governance remains the source of admission, reservation, settlement, retention, and audit policy.
- Logs and metrics are diagnostic; they never replace typed Release, Run, retrieval, or hit records.

## 5. Application draft binding design

The Application-owned table is:

```text
application_draft_knowledge_binding
  application_id
  tenant_id
  workspace_id
  binding_order
  knowledge_index_version_id
  retrieval_policy_version_id
  created_at
  updated_at
```

Required database rules:

- composite foreign key to the owning Application scope only;
- unique `(application_id, binding_order)`;
- unique `(application_id, knowledge_index_version_id, retrieval_policy_version_id)`;
- `binding_order` between 0 and 15;
- no foreign key to Knowledge-owned tables;
- replace-all update in one transaction;
- optimistic Application version increment so concurrent draft edits cannot silently overwrite.

CHAT drafts must have zero Knowledge bindings. RAG drafts may be edited with zero bindings, but
preview/release creation fails until at least one exact pair validates. This preserves an editable
draft without weakening production release gates.

## 6. ReleaseBundle 1.1 creation and compatibility

Release creation is the validation and pinning boundary:

1. load the Application in the authenticated workspace;
2. snapshot the draft model route, prompt, runtime mode, and ordered Knowledge IDs;
3. resolve every exact Knowledge Index Version and Retrieval Policy Version through Knowledge;
4. require workspace ownership, immutable publication, READY state, supported retrieval algorithm,
   and enabled execution policy;
5. resolve existing exact model/prompt references through Capability Registry;
6. construct the Manifest 1.1 server-side from authoritative projections;
7. validate the complete schema using the offline registry;
8. calculate the canonical artifact digest;
9. insert one immutable ReleaseBundle in the same release transaction.

Client-supplied manifests cannot override server-resolved draft pins for the standard Application
release path. If raw manifest import remains supported, it is a separate explicitly authorized
operation with the same full resolution and validation gates; P2.3 does not create it implicitly.

Compatibility matrix:

| Stored manifest | Application mode | Runtime behavior |
|---|---|---|
| 1.0 | CHAT | Preserve historical P1 execution |
| 1.0 with placeholder Knowledge strings | CHAT | Ignore placeholders exactly as historical CHAT behavior |
| 1.1 | CHAT | Require zero Knowledge bindings |
| 1.1 | RAG | Require one to 16 validated exact Knowledge bindings |
| unknown schema | any | Fail with stable unsupported-manifest error |

After the first 1.1 RAG release exists, a P1-only binary is below the rollback floor.

## 7. Runtime evidence persistence

Runtime owns two new tables plus a stable failure-code extension:

```text
ai_run_retrieval
  id
  run_id + tenant_id + workspace_id
  sequence
  index_version_id + index_version_reference
  retrieval_policy_version_id + retrieval_policy_version_reference
  query_digest
  status
  hit_count
  latency_ms
  retention_decision_version
  created_at

ai_run_retrieval_hit
  id
  retrieval_id + run_id + tenant_id + workspace_id
  marker
  rank
  score
  source_id
  source_revision_id
  document_id
  chunk_id
  content_digest
  retained_content
  source_title
  source_type
  page / heading / paragraph / line_start / line_end
  citation_validated
  created_at

ai_run.failure_code
  stable nullable machine-readable code for failed P2 execution
```

Required constraints:

- every evidence row is transitively scoped to its Run;
- unique `(run_id, sequence)`;
- unique `(run_id, marker)` and deterministic marker order;
- unique `(retrieval_id, rank)`;
- score, rank, anchor, digest, status, and count checks;
- immutable identity/digest/order fields after insert;
- retained content may be null or masked according to current retention policy;
- no stored filesystem path, object-store path, raw secret, or durable authorization URL.

The public read projection generates an authorization-checked locator at read time. A locator is not
part of the release digest or evidence identity.

## 8. Grounded Run state machine

```text
create Run and Trace identity
  -> admit execution and reserve query embedding
  -> resolve immutable release
  -> for each ordered binding:
       retrieve exact index/policy
       persist retrieval result and hits
  -> if no acceptable evidence across all bindings:
       persist typed NO_EVIDENCE
       release/settle reservations
       complete without chat generation
  -> reserve chat generation
  -> construct bounded untrusted context
  -> invoke exact pinned chat route
  -> parse structured answer
  -> validate every citation marker
  -> persist Grounded Answer, validated citations, usage and cost
  -> complete
```

Rules:

1. Runtime never reads the current Application draft after resolving the ReleaseBundle.
2. Bindings run in manifest order. Evidence receives deterministic global markers `[K1]`, `[K2]`,
   and so on after policy filtering and context budgeting.
3. Retrieved content is delimited as untrusted data. It cannot override the system prompt, select
   capabilities, invoke tools, or alter policy.
4. `NO_EVIDENCE` is a successful typed grounded outcome with zero citations and no chat-generation
   call. It is never ordinary CHAT fallback.
5. Knowledge-disabled execution fails with `KNOWLEDGE_DISABLED`.
6. Unknown manifest versions, unavailable exact pins, malformed structured output, fabricated
   markers, provider failures, and ambiguous paid calls remain distinct stable outcomes.
7. Reservation and settlement semantics from P1 apply to query embedding and chat generation.
   Runtime uses the approved Capability facade and never writes Governance tables.

## 9. Citation validation

A Grounded Answer succeeds only when:

- it validates against Grounded Answer Schema 1.0;
- `GROUNDED` contains a non-empty answer and at least one unique citation;
- every citation marker exists in the current Run evidence set;
- marker identity, source lineage, content digest, rank, score, and anchors are copied from retained
  evidence rather than trusted from model output;
- no cited marker was removed by policy or context budgeting;
- `NO_EVIDENCE` contains zero citations.

The model may return markers, but it does not author citation metadata. Runtime derives the public
Citation objects from verified evidence. Unknown or malformed markers fail the Run with a stable
`CITATION_VALIDATION_FAILED` family code; Apvero never strips a fabricated citation and reports
success.

## 10. Source resync, tombstone, retention, and historical releases

- Resynchronizing a source creates new revisions and affects only future Index Builds.
- Tombstoning a source prevents it from entering future Builds.
- An old Release continues to retrieve its exact old immutable Index Version.
- Current authorization always applies when executing or inspecting an old Run.
- Current stricter retention/masking policy may suppress stored excerpts or locators, but immutable
  digest, rank, source revision, document, and chunk identity remain where policy permits.
- Legal erasure is not disguised as tombstoning. If an approved destructive erasure makes historical
  evidence unavailable, the system records the reproducibility break explicitly.

Tests must compare an old Release and a new Release after source resync/tombstone and prove that each
continues to use its own pinned index.

## 11. Stable errors, security, audit, and telemetry

Minimum stable error families:

```text
APPLICATION_KNOWLEDGE_BINDING_INVALID
KNOWLEDGE_INDEX_VERSION_NOT_FOUND
RETRIEVAL_POLICY_VERSION_NOT_FOUND
RELEASE_KNOWLEDGE_BINDING_INVALID
RELEASE_MANIFEST_UNSUPPORTED
RELEASE_MANIFEST_INVALID
KNOWLEDGE_DISABLED
RUNTIME_RETRIEVAL_FAILED
GROUNDED_OUTPUT_INVALID
CITATION_VALIDATION_FAILED
EXTERNAL_OUTCOME_RECONCILIATION_REQUIRED
```

Backend responses expose stable codes; clients localize messages. No provider error, prompt,
retained source content, query text, secret, or raw locator is placed in a normal error message.

Typed telemetry covers release pin validation, retrieval count/latency/hits/empty evidence, bounded
context size, grounded success, no evidence, citation failure, provider failure, and settlement.
Administrative mutations and policy decisions remain auditable. High-volume hit events stay typed
Runtime evidence rather than flooding the administrative audit ledger.

## 12. Migration and transaction plan

Planned forward migrations:

- V12: Application draft Knowledge binding table, scope constraints, ordering, uniqueness, and
  immutability-compatible replacement protocol.
- V13: Runtime retrieval/evidence tables, `ai_run.failure_code`, scope constraints, evidence
  immutability guards, and inspection indexes.

Migration tests upgrade from the P1/P2.2 baseline and from a clean database. No destructive down
migration is provided. Rollback mitigation is:

- before any RAG release exists, disable Knowledge and run a P1-compatible binary;
- after a RAG release exists, retain the additive data and use only a P2-compatible binary;
- disabling Knowledge produces `KNOWLEDGE_DISABLED`, never CHAT fallback;
- failed or partial Runs remain inspectable and are not deleted during rollback.

## 13. Internal implementation slices

These slices are checkpoints, not independently releasable product claims.

### P2.3a — Contract reconciliation and opaque Application bindings

- correct contract-only binding semantics and Manifest 1.1 field-specific references;
- add Knowledge exact-version public projections/queries;
- add V12 and Application binding aggregate/API;
- verify no `application -> knowledge` dependency;
- keep every P2.3 endpoint disabled/non-live.

### P2.3b — Immutable Manifest 1.1 release pinning

- add Release's approved Knowledge dependency;
- add offline complete JSON Schema validation;
- construct server-authoritative Manifest 1.1;
- validate exact READY Index/Policy pins and ordered bindings;
- preserve Manifest 1.0 read and CHAT execution compatibility.

### P2.3c — Scoped Run retrieval evidence ledger

- add V13 and Runtime evidence repositories;
- persist retrieval result and ordered hit identity transactionally;
- apply retention/masking before content persistence;
- implement workspace-scoped evidence/citation read models without live Console exposure.

### P2.3d — Grounded runtime orchestration

- resolve only the immutable ReleaseBundle;
- execute exact ordered retrieval with P1 governance semantics;
- implement bounded untrusted context and deterministic markers;
- implement typed `NO_EVIDENCE` without a generation call;
- preserve ambiguous external-call reconciliation behavior.

### P2.3e — Structured answer and citation validation

- parse Grounded Answer 1.0;
- derive Citation 1.0 from evidence;
- reject malformed/unknown/fabricated markers;
- generate authorized locators at read time;
- record stable failure, audit, usage, cost, and telemetry outcomes.

### P2.3f — Closure and compatibility hardening

- run Manifest 1.0/1.1 compatibility suites;
- prove old/new release behavior after resync and tombstone;
- run cross-workspace, retention, injection, failure, restart, and rollback-floor tests;
- run offline deterministic end-to-end and opt-in adapter tests;
- prepare bilingual P2.3 verification evidence for maintainer acceptance.

## 14. Verification matrix

| Area | Required evidence |
|---|---|
| Boundaries | Spring Modulith and ArchUnit allowed/forbidden dependency tests |
| Application | replace-all ordering, duplicate rejection, optimistic conflict, CHAT zero-binding, workspace isolation |
| Knowledge API | exact READY lookup, policy lookup, not-found/cross-workspace fail-closed |
| Release | complete schema validation, authoritative resolution, digest stability, 1.0 compatibility, unknown schema rejection |
| Migration | clean V1–V13 and P2.2-to-V13 Testcontainers upgrades, constraints and immutability guards |
| Runtime | exact release-only resolution, multi-binding order, restart/terminal-path behavior |
| Governance | pre-call reservation, settlement/release, budget/rate rejection, ambiguous paid-call reconciliation |
| Security | prompt-injection boundary, masking, locator authorization, no raw path/secret/error leakage |
| Citations | deterministic markers, valid mapping, duplicate/unknown/malformed marker rejection |
| Compatibility | old CHAT release, 1.1 CHAT, 1.1 RAG, resync/tombstone old/new release comparison |
| Contracts | OpenAPI and JSON Schema compatibility plus offline schema-registry tests |
| i18n/docs | stable backend codes and matching English/zh-CN documents |
| Operations | metrics, typed evidence, audit, health behavior, container/Compose checks |
| End to end | upload → ingest → build → retrieve → bind → release → run → inspect verified citations |

P2.3 acceptance evidence may use API and integration tests. Full bilingual live-page and Playwright
acceptance remains P2.4.

## 15. Self-critique and rejected shortcuts

1. **Validate Knowledge during Application binding.** Rejected because it violates the approved
   dependency graph or creates an unsafe duplicated projection. Release validation is authoritative.
2. **Store canonical references supplied by the browser.** Rejected because they can be stale or
   forged. Release resolves references from owning modules.
3. **Read Knowledge tables from Release or Runtime.** Rejected as cross-module database access.
4. **Keep the shallow manifest validator.** Rejected because it cannot enforce conditional,
   cardinality, closed-object, or referenced-schema rules.
5. **Rewrite old Manifest 1.0 rows.** Rejected because ReleaseBundle is immutable.
6. **Read the latest draft at Run time.** Rejected because it destroys reproducibility.
7. **Silently fall back from RAG to CHAT.** Rejected because an ungrounded answer would appear
   grounded.
8. **Let the model author full citation metadata.** Rejected because models can fabricate lineage.
9. **Store durable signed object URLs.** Rejected because authorization and expiry change; locators
   are generated at read time.
10. **Add a citation table in addition to the approved evidence tables.** Rejected unless evidence
    proves it necessary; validated citation state and mapping fit the Runtime-owned hit ledger.
11. **Add Kafka, Redis, Milvus, or a workflow engine.** Rejected because PostgreSQL and the modular
    monolith close this workflow without a proven new boundary.
12. **Make pages live during backend closure.** Rejected because P2.4 owns honest product and
    operations exposure.

Known limitation: P2.3 proves reproducible vector-grounded execution, not universal answer quality.
Retrieval quality depends on the source corpus, parser output, embedding route, and fixed policy.
Evaluation, A/B testing, reranking, hybrid search, OCR, and broad connectors are not silently folded
into this milestone.

## 16. Acceptance gate

P2.3 can be proposed for maintainer acceptance only when:

- all six slices are implemented and verified;
- a RAG draft releases only after exact Knowledge pins validate;
- Manifest 1.0 CHAT behavior remains compatible;
- a production Run consults no mutable draft state;
- `GROUNDED` citations resolve only to that Run's retained evidence;
- `NO_EVIDENCE`, Knowledge-disabled, malformed output, invalid citation, provider failure, and
  ambiguous external outcome are distinct and tested;
- old and new releases remain reproducible after source resync/tombstone;
- migrations, module rules, contracts, security, telemetry, usage/cost, EN/zh-CN docs, Compose, and
  CI evidence pass;
- the maintainer approves the P2.3 transition.

Even after P2.3 acceptance, the feature remains non-live until P2.4 completes the product and
operations gate. P2 itself remains `in-progress`.

## 17. Primary implementation references

- `AGENTS.md`
- `architecture/invariants.yaml`
- `architecture/delivery-stages.yaml`
- `architecture/modules.yaml`
- `architecture/dependency-rules.yaml`
- `product/navigation.yaml`
- `product/pages.yaml`
- `docs/adr/0006-p2-grounded-knowledge-rag-baseline.md`
- `docs/en/p2-contract-baseline.md`
- `contracts/openapi/platform-api.yaml`
- `contracts/schemas/release-bundle-manifest.schema.json`
- `contracts/schemas/release-bundle-manifest.v1.1.schema.json`
- `contracts/schemas/grounded-answer.v1.schema.json`
- `contracts/schemas/citation.v1.schema.json`
