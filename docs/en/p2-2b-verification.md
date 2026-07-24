# P2.2b Scoped Immutable Persistence — Verification

Status: implementation candidate; maintainer acceptance pending

Target: P2 / P2.2b

## Delivered boundary

P2.2b implements only the persistence slice approved by ADR-0006 and the maintainer-approved
P2.2 implementation plan. It does not enable embedding execution, the index build runner, atomic
publication APIs, Retrieval Lab, or the Knowledge product surface.

The forward V10 migration adds exactly the approved persistence inventory:

- `retrieval_policy_version`;
- `knowledge_index`;
- `knowledge_index_build`;
- `knowledge_index_build_revision`;
- `knowledge_index_entry`;
- `knowledge_index_version`;
- `execution_reservation_component`.

PostgreSQL 18 with pgvector remains the only mandatory stateful dependency. No module, deployable,
queue, framework, provider SDK, or public REST contract was added.

## Enforced invariants

- Every row repeats tenant and workspace scope.
- Composite foreign keys preserve Base, Source, Revision, Document, Chunk, Index, Build, Route, and
  Reservation scope.
- Build route references and copied embedding profiles must match the exact immutable Embedding
  Route.
- Build revision snapshots require an active Source, a READY ingestion result, a matching immutable
  Revision digest, and version-consistent persisted Documents and Chunks.
- Entry dimension, route identity, source lineage, vector dimension, and non-zero norm are enforced
  by database constraints.
- Version route, dimension, source count, and chunk count must exactly match the publishing Build.
- Retrieval policies, Build Revision rows, Entry rows, and Index Version rows are insert-only.
- Published Builds reject update/delete and reject later Entry insertion.
- Failed unpublished Builds remain durable and inspectable.
- Governance component identities are immutable, unique per Reservation, and transition forward
  only from reserved to dispatched to a terminal outcome.
- Existing P1 reservations are backfilled as `APPLICATION_RUN`; the P1 admission path now writes
  the same subject identity explicitly.

## Repository boundary

Knowledge and Governance each own a separate internal repository. Every operation accepts
`WorkspaceScope` as its first argument. Knowledge does not read or write Governance tables, and
neither repository exposes database records through a public module API.

## Migration and rollback

V10 is additive and forward-only. Verification covers a clean install and a real V8-to-head upgrade
through V9 and V10 containing an existing P1 execution reservation. Rollback uses the previous compatible binary and
retains V10 rows; there is no destructive down migration or automatic vector deletion.

The new tables remain unused while `APVERO_KNOWLEDGE_ENABLED=false`. This keeps current P1 and P2.1
runtime behavior unchanged.

## Verification commands

```text
gradlew :modules:knowledge:test :modules:governance:test
gradlew :apps:platform-server:test --tests "*P22b*"
gradlew test :apps:platform-server:bootJar --no-daemon
```

The P2.2b integration suite proves:

- clean migration shape, approved table count, composite foreign keys, indexes, and triggers;
- V8-to-head preservation, CHAT route backfill, and P1 reservation subject backfill;
- two-tenant/two-workspace fail-closed repository and database behavior;
- exact route/source/chunk/vector lineage persistence;
- vector dimension rejection;
- immutable published artifacts and durable failed unpublished Builds;
- component idempotency, scope isolation, forward transitions, and terminal immutability.

## Honest limitations

P2.2b stores the approved shapes but does not claim that an Index Version can yet be produced by a
live workflow. P2.2c must implement governed embedding execution. P2.2d must implement leased Build
execution and atomic publication. Until those slices pass, direct database fixtures are verification
evidence only and the Knowledge surface remains contract-only.
