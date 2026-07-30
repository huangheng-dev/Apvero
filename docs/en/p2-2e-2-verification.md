# P2.2e-2 Exact Retrieval Kernel Verification

Status: locally verified implementation checkpoint; milestone publication and GitHub CI are
deferred until the complete P2.2 verification candidate.

## Scope

P2.2e-2 implements the database-owned deterministic ranking boundary:

```text
scoped READY Index Version
  -> validated query vector
  -> one exact pgvector cosine statement
  -> threshold and exact topK
  -> stable distance/chunk ordering
  -> bounded immutable lineage candidates
```

It does not dispatch a query Embedding, reserve or settle cost, apply overlap/context/retention
projection, expose the Retrieval Lab endpoint, enable a frontend page, or create a production Run.
Those remain P2.2e-3 through P2.2e-5 work.

## Architecture result

- Stage P2 and slice P2.2e remain `in-progress`.
- Knowledge remains the only owning module.
- The implementation adds no dependency outside the approved Identity, Capability Registry and
  Governance boundary.
- PostgreSQL 18 with pgvector remains the only stateful dependency.
- No migration, table, index, deployable, queue, framework, REST schema or page changed.
- The public Java result and hit records implement the already approved contract-only OpenAPI
  shape without provider, jOOQ, pgvector, database, secret, path or URL types.

## Public boundary

Knowledge now declares:

- `KnowledgeRetrieval`;
- `KnowledgeRetrievalResult` with `MATCHES` and `NO_EVIDENCE`;
- `KnowledgeRetrievalHit` with rank, normalized score, immutable lineage, digest, bounded content
  and bounded anchors.

The records copy hit collections and reject invalid status/list combinations, negative latency,
invalid digests, score or rank bounds, oversized content/title/heading, and malformed anchors.
P2.2e-3 and P2.2e-4 will implement the orchestration and policy-controlled projection behind this
boundary.

## Exact ranking statement

`JooqKnowledgeIndexPersistenceRepository` executes one statement with:

- tenant ID, workspace ID and exact Index Version ID predicates;
- READY Version and READY Build predicates;
- composite Build, Entry, Chunk and Source scope joins;
- query and Entry dimensions equal to the immutable Version dimension;
- cosine threshold before limiting;
- cosine distance ascending, then immutable Chunk UUID ascending;
- SQL `LIMIT` equal to the policy `topK`;
- database-generated consecutive rank;
- exposed score clamped from `1 - cosine_distance` into `[0,1]`.

Java does not globally fetch candidates, apply workspace filtering after ranking, or re-sort
floating-point scores. There is no oversampling or hidden backfill.

The query deliberately has no current Source status predicate. A Source tombstone prevents future
Build selection but does not silently rewrite or invalidate a previously published READY Index
Version.

## Input and projection safety

Before ranking, the internal kernel:

- loads the exact READY Version inside the caller's tenant/workspace scope;
- returns the same scoped not-found result for missing, cross-workspace and cross-tenant IDs;
- enforces `topK` `1..100` and finite score threshold `0..1`;
- requires the exact published vector dimension;
- rejects null, non-finite and zero-norm vectors.

The SQL projects only public lineage IDs, content digest, bounded Chunk text, bounded Source name
and type, and page/heading/paragraph/line anchors. It does not project vectors, vector digests,
snapshot bytes, capture metadata, storage locations, raw URLs, Route data or provider data.

## Determinism and isolation evidence

The PostgreSQL/Testcontainers test proves:

- equal cosine distance is resolved by Chunk UUID even when rows were inserted in reverse order;
- threshold filtering is applied before the exact SQL limit;
- `topK` is not replaced by an application-side limit;
- score `1.0`, intermediate cosine score and the inclusive `0.0` boundary are normalized as
  expected;
- a sibling workspace in the same tenant and a different tenant cannot resolve or rank the
  Version;
- tombstoning the Source after publication does not alter historical retrieval;
- an analyzed representative plan contains the bounded `Limit` and scoped Index access path.

The plan fixture is intentionally small and is not a scale claim. P2.2f owns measured corpus and
concurrency support envelopes.

## Verification executed

Passed locally:

- Knowledge module compilation and complete module tests;
- public retrieval boundary invariant tests;
- exact kernel validation and SQL-shape tests;
- real PostgreSQL 18/pgvector ranking, isolation, history and plan tests;
- platform test compilation.

The first integration attempt correctly failed because a test fixture used a non-hexadecimal fake
digest. The fixture was corrected; no production constraint was weakened. The complete targeted
integration suite then passed.

Milestone-level architecture, OpenAPI, Compose, security and complete CI verification remains
deferred to the assembled P2.2 candidate according to the repository publication policy.

## Rollback

- revert the P2.2e-2 implementation commit or run the previous compatible binary;
- retain all immutable Index Versions and Entries;
- no migration or data reversal is required;
- Knowledge remains disabled by default;
- no endpoint or product page became live.

## Exit statement

P2.2e-2 is locally complete when:

> An exact READY Index Version can rank its immutable Entries through one tenant/workspace-scoped
> pgvector cosine statement with deterministic distance/Chunk ordering, exact threshold and topK
> behavior, bounded lineage projection, cross-scope fail-closed behavior, and preserved tombstone
> history.

The next checkpoint is P2.2e-3 governed query execution. P2.2e and P2.2 remain `in-progress`.
