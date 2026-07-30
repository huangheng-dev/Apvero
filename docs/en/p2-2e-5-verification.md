# P2.2e-5 Exact Retrieval Lab Verification Candidate

Status: accepted by the maintainer on 2026-07-30. P2.2 remains `in-progress`; P2.2f acceptance
hardening is now active.

## Accepted scope

P2.2e closes exactly this laboratory workflow:

```text
publish immutable Retrieval Policy
  -> authorize exact READY Index Version and Policy Version
  -> quote and admit query Embedding
  -> dispatch and settle once
  -> execute workspace-scoped exact cosine ranking
  -> apply deterministic overlap and context budget
  -> apply current retention disclosure
  -> return bounded MATCHES or typed NO_EVIDENCE
```

It does not generate an answer, bind an Application draft, write production Run evidence, enable
the Knowledge product page, add hybrid or approximate search, or make an unmeasured scale claim.

## Authority and architecture

- Stage: P2 / P2.2 / P2.2e.
- Owner: Knowledge.
- Allowed dependencies used: Identity, Capability Registry and Governance public APIs only.
- Forbidden module internals and provider SDK types do not enter the Knowledge boundary.
- PostgreSQL 18 with pgvector remains the only mandatory stateful dependency.
- No table, migration, queue, deployable, framework, release semantic or public schema shape was
  added.
- `architecture/modules.yaml` now records the approved Retrieval Service as implemented within the
  existing Knowledge boundary.
- Retrieval Policy and Retrieval Lab OpenAPI operations no longer carry stale `contract-only`
  markers. Their request and response schemas are unchanged.
- The product page remains non-live and requires no frontend locale keys in this slice.

No protected change was required and no new ADR was needed.

## Verification matrix

### Architecture

Spring Modulith verifies the modular monolith and approved Knowledge dependencies. Repository
architecture tests continue to prevent persistence implementations from crossing module
boundaries. Forbidden provider libraries remain outside the core.

### Immutable policy

Publication tests prove:

- exact public ranges and platform-assigned algorithm identities;
- canonical digest and durable Retention Policy provenance;
- idempotent same-version replay;
- conflict on reused version with changed behavior;
- duplicate digest conflict under another identity;
- concurrent first publication convergence;
- insert-only persistence and one safe administrative audit;
- transaction rollback when the audit write fails.

### Exact SQL

The ranking repository performs one statement with tenant, workspace, READY Version, Build,
Entry, dimension, threshold and exact `topK` predicates before ranking and limiting. PostgreSQL
distance then Chunk UUID defines order. Representative `EXPLAIN ANALYZE` evidence shows bounded
index access and a `Limit`; it is not presented as an arbitrary-scale claim.

### Isolation

Both same-tenant/different-workspace and different-tenant kernel tests return the same scoped
not-found outcome. The complete REST path additionally proves that an administrator cannot use a
real foreign Version ID through another Workspace header: it returns
`APVERO_KNOWLEDGE_INDEX_VERSION_NOT_FOUND` and creates no `KNOWLEDGE_QUERY` reservation in the
foreign workspace.

### Historical retrieval

New Builds already exclude tombstoned Sources. Exact retrieval deliberately does not join current
Source status. Kernel and complete REST tests prove that an already published READY Index Version
continues to return the same immutable Chunk after its Source is tombstoned.

### Governance and failure semantics

Tests prove quote-before-admission, denial before dispatch, one reservation/component, one
Provider call, success settlement, known failure settlement, ambiguous outcome reconciliation,
no blind replay after dispatch, and safe settlement conflict behavior. Reusing a settled trace
does not create another reservation or charge.

### Determinism

Evidence covers database tie-breaking, threshold and exact `topK`, overlap within one immutable
Document, touching ranges, different Documents, full-content budgeting, oversized-hit skipping,
later-hit eligibility, consecutive final ranks, English, Simplified Chinese, mixed UTF-8 and the
20,000 Unicode code-point boundary.

### Retention and disclosure

The current Governance Retention Policy is applied at read time. Payload-disabled and
masking-required states suppress content before budgeting and observation. The response contains
only contracted bounded lineage, score, digest and safe anchors. Tests reject or omit character
offsets, raw URL/path, object key, secret, vector and Provider identity fields. Raw queries are
reduced to SHA-256 for persisted identities and are absent from normal logs and metric labels.

### Stable errors and empty evidence

Disabled capability, malformed identity/query, scoped not-found, invalid pinned artifacts,
admission denial, Provider failure, ambiguity, vector validation, replay and settlement conflicts
use stable failure families. Empty ranking or post-ranking filtering returns successful typed
`NO_EVIDENCE`; it never authorizes an ungrounded fallback.

### Telemetry

P2.2e-5 adds Retrieval-specific Micrometer evidence:

- `apvero.knowledge.retrieval.request`;
- `apvero.knowledge.retrieval.latency`;
- `apvero.knowledge.retrieval.provider.latency`;
- `apvero.knowledge.retrieval.hits`;
- `apvero.knowledge.retrieval.score`.

Tags are restricted to bounded outcome, failure family, hit kind and coarse score bucket.
Repeated request identities do not increase metric cardinality. Tenant, Workspace, Route, Index,
Policy, query, content, URL and Provider request identity are never labels. Governance remains the
durable billing evidence; high-volume retrieval does not create administrative audit spam.

### Contracts and security

Controller reflection and OpenAPI parsing prove every implemented Knowledge method/path. Retrieval
Policy and Retrieval Lab requests use their committed OpenAPI 3.1 shapes. Retrieval execution is a
POST and requires `write` or `admin`; a `read` API key is denied before business execution.
Backend responses use stable codes for client localization.

### Deployment and rollback

Default and Knowledge-profile Compose configurations remain valid. Knowledge remains disabled by
default. PostgreSQL/pgvector is the only mandatory stateful dependency. Rollback uses the previous
compatible binary, retains immutable Policy/Index/Governance rows, requires no migration reversal,
and returns the Retrieval Lab endpoint to unavailable when Knowledge is disabled.

## Verification executed

Passed locally:

- complete Knowledge module tests, including repository architecture and Retrieval telemetry;
- Spring Modulith verification;
- Retrieval Policy, Retrieval Lab and OpenAPI controller conformance;
- platform-server bootable JAR;
- P2.2e-1 real PostgreSQL policy publication, concurrency, audit, isolation and rollback suite;
- P2.2e-2 real PostgreSQL/pgvector ranking, scope, history and query-plan suite;
- P2.2e-3/e-4/e-5 real REST, authentication, Governance, deterministic Embedding, retention,
  cross-workspace, tombstone history and telemetry suite;
- OpenAPI 3.1 validation;
- default and Knowledge-profile Compose configuration validation;
- source diff and forbidden-dependency checks.

The three Testcontainers suites were intentionally run as separate Gradle invocations. This gives
the same isolated database proof without relying on the local Docker Desktop capacity to keep many
Spring database contexts alive in one JVM. GitHub CI remains the authoritative clean-host
milestone run when the complete P2.2 candidate is published.

## Known limits retained

1. Exact cosine retrieval is deterministic, not hybrid retrieval.
2. SQL uses exact `topK`; there is no hidden backfill after policy filtering.
3. The deterministic local Embedding adapter proves orchestration, not semantic quality.
4. Sensitive unstructured content is suppressed because no approved shared masker exists.
5. Retrieval Lab is synchronous and does not persist a separate query row.
6. Corpus size and concurrency support envelopes belong to P2.2f.
7. Application binding, immutable ReleaseBundle pins and cited answers belong to P2.3.

## Exit statement

P2.2e is ready for maintainer acceptance when the maintainer confirms this evidence:

> In one authorized workspace, Apvero can publish an immutable Retrieval Policy and use it with an
> exact READY Index Version to perform a governed, deterministic, PostgreSQL-scoped cosine query
> that returns bounded currently authorized evidence or typed NO_EVIDENCE, without cross-workspace
> leakage, raw-query retention, hidden ranking behavior, duplicate cost settlement or unsupported
> quality claims.

The maintainer accepted this evidence on 2026-07-30. P2.2e is complete, P2.2 remains
`in-progress`, and P2.2f acceptance hardening is active.
