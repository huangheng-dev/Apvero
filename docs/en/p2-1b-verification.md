# P2.1b Scoped Immutable Persistence Verification

Status: Locally verified implementation checkpoint
Date: 2026-07-22
Stage: P2 / milestone P2.1b

## Delivered boundary

P2.1b adds the forward-only `V8__p2_1_durable_knowledge_ingestion.sql` migration and an internal jOOQ persistence boundary for:

- `knowledge_base`;
- `knowledge_source`;
- `knowledge_source_revision`;
- `knowledge_document`;
- `knowledge_chunk`;
- `knowledge_ingestion_job`.

Every table repeats `tenant_id` and `workspace_id`. Composite foreign keys preserve scope across Base, Source, Revision, Document, Chunk, and Job lineage. Repository operations require `WorkspaceScope` as their first argument, compare inserted row scope before writing, and include both tenant and workspace predicates when reading.

Knowledge remains disabled by default. This checkpoint adds no controller, live product data, source command, parser endpoint, job runner, embedding, index, retrieval, Application binding, Release, or grounded Run.

## Database enforcement

- Base slugs are unique inside one workspace and mutable rows have positive optimistic versions.
- Source types, states, web-locator shape, latest-revision shape, and tombstone metadata are checked.
- Source snapshot digests use canonical `sha256:<64 lowercase hex>` identities and byte length must match accepted snapshot bytes.
- Source revision number and digest are unique per Source.
- Source Revision, Document, and Chunk rows reject ordinary `UPDATE` and `DELETE` through PostgreSQL triggers.
- Document and Chunk ordinals are unique within their immutable parent.
- Chunk offsets use a checked half-open interval and one-based optional anchors.
- Job status/step combinations, attempts, retry time, leases, error shape, completion timestamps, and idempotency identity are checked.
- Job-to-Revision foreign keys include the owning Source as well as tenant/workspace scope.

## Verification evidence

The following checks passed locally against PostgreSQL 18 with pgvector:

- clean Flyway migration from an empty database through V8;
- explicit migration to V7 followed by one in-place migration to V8;
- all six tables, scoped foreign keys, claim index, and immutable triggers exist;
- all six persistence row types round-trip through the internal jOOQ repository;
- reads of identifiers from a second tenant/workspace return no row;
- scope-mismatched inserts fail before SQL and cross-scope foreign-key attacks fail in PostgreSQL;
- invalid digest, offset, job state, and duplicate Source digest inputs fail closed;
- Source Revision, Document, and Chunk update/delete attempts fail;
- snapshot byte arrays are defensively copied;
- every repository operation is guarded by an architecture test requiring `WorkspaceScope` first;
- the complete Gradle `test` task passes, including Spring Modulith, ArchUnit, P1 governance, P2.1a, and P2.1b tests.

## Rollback and mitigation

V8 is additive and has no destructive down migration. Before any P2 API or RAG Release is live, rollback uses the previous P2.1a/P1-compatible binary with `APVERO_KNOWLEDGE_ENABLED=false`; that binary ignores the six new tables. Persisted Knowledge rows remain untouched for diagnosis and a later forward recovery. Operators must not drop the tables or immutable triggers as an application rollback step.

If V8 migration itself fails, Flyway and PostgreSQL transactional DDL leave V8 unapplied and the previous binary remains the recovery target. Any future migration that changes these tables must be forward-only and preserve already stored immutable lineage.

## Not delivered

P2.1b does not implement P2.1c source commands, P2.1d safe web capture, P2.1e production worker processing, or P2.1f durable execution and operations. P2 and P2.1 remain `in-progress`; all P2 REST contracts remain `contract-only`.
