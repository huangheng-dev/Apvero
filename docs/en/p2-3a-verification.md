# P2.3a Contract Reconciliation and Opaque Application Bindings Verification Candidate

Status: accepted by the maintainer on 2026-07-31. P2.3 remains `in-progress`; P2.3b immutable
Manifest 1.1 release pinning is now active.

## Scope

P2.3a implements this bounded workflow:

```text
list exact immutable Knowledge versions
  -> select opaque index and retrieval-policy version IDs
  -> replace the ordered Application draft binding set with optimistic concurrency
  -> retain the mutable selection for authoritative Release validation in P2.3b
```

It does not publish Manifest 1.1, validate a binding as READY during draft editing, pin a
ReleaseBundle, retrieve production evidence, orchestrate RAG, or emit citations.

## Authority and architecture

- Stage: P2 / P2.3 / P2.3a.
- Owners: Application owns mutable draft selections; Knowledge owns immutable index and retrieval
  policy versions.
- Application has no dependency on Knowledge. It stores Knowledge-owned UUIDs as opaque values.
- Knowledge continues to use only its approved Identity, Capability Registry, and Governance
  dependencies.
- PostgreSQL remains the only mandatory stateful dependency.
- The work is covered by ADR-0006 and changes no invariant, deployable, framework, queue, release
  semantic, security baseline, or module boundary.
- Manifest 1.1 remains `contract-only`. Release creation continues to accept Manifest 1.0 until
  P2.3b implements authoritative resolution and immutable pinning.

## Contract reconciliation

- Model Route and Prompt references use their implemented positive-integer version format.
- Knowledge Index and Retrieval Policy references use their implemented semantic-version format.
- Other future artifacts accept exact integer or semantic versions; every format rejects
  `latest`.
- ReleaseBundle reads may project Manifest 1.0 or 1.1, while the create request remains Manifest
  1.0 only.
- Application draft binding reads return the Application identity, current optimistic version,
  ordered opaque IDs, and no fabricated canonical Knowledge references.
- Replacement requires `expectedApplicationVersion`, has a maximum of 16 bindings, and rejects
  duplicate pairs.

## Persistence, isolation, and security

Migration V12 adds only the Application-owned `application_draft_knowledge_binding` table. Its
composite foreign key binds every row to the same Application, tenant, and workspace. It
intentionally has no cross-module foreign key to Knowledge tables.

Database triggers reject bindings for non-RAG Applications and reject changing a bound RAG
Application to another runtime mode. The service additionally validates mode, null identities,
duplicates, limits, and optimistic concurrency. A stale replacement rolls back without deleting
the current set. Cross-workspace reads fail with the same scoped Application not-found behavior.

Backend failures use stable client-localizable codes:

- `APVERO_APPLICATION_KNOWLEDGE_BINDING_INVALID`;
- `APVERO_APPLICATION_KNOWLEDGE_BINDING_MODE_INVALID`;
- `APVERO_APPLICATION_DRAFT_VERSION_CONFLICT`;
- `APVERO_KNOWLEDGE_INDEX_VERSION_NOT_FOUND`;
- `APVERO_KNOWLEDGE_RETRIEVAL_POLICY_VERSION_NOT_FOUND`.

The endpoints remain behind the existing Knowledge feature flag and platform authentication and
workspace authorization chain.

## Verification

The candidate proves:

- exact workspace-scoped Knowledge Index Version listing and Retrieval Policy Version lookup;
- Application binding order preservation and defensive immutable projections;
- real V11-to-V12 Flyway upgrade without rewriting existing Application rows;
- one same-scope Application foreign key and no Knowledge foreign key;
- opaque random Knowledge IDs can be stored without crossing the module boundary;
- stale optimistic-concurrency replacement cannot partially mutate the binding set;
- database enforcement of RAG-only binding in both mutation directions;
- same-tenant cross-workspace access fails closed;
- OpenAPI operations and controller methods remain aligned;
- Manifest 1.1 format rules reject `latest` and field-incompatible version formats.

Executed locally:

- Application and Knowledge module unit tests;
- P2.3a controller, OpenAPI reconciliation, and real PostgreSQL/Testcontainers integration tests;
- complete repository test suite and Spring Modulith/ArchUnit verification;
- platform-server bootable JAR;
- OpenAPI 3.1 and JSON Schema validation;
- English and Simplified Chinese locale validation;
- Compose configuration and source-diff checks.

## Migration and rollback

Forward migration is V12. Rollback uses the previous compatible binary after disabling Knowledge
binding writes. The new table and trigger functions may remain dormant; removing them requires a
separate forward cleanup migration after confirming no retained draft selections are needed.
There is no destructive down migration.

## Known limits

1. Draft bindings are selections, not proof that the referenced Knowledge artifacts exist or are
   READY.
2. P2.3b must resolve every opaque ID within the authenticated workspace and pin canonical exact
   references into an immutable Manifest 1.1 ReleaseBundle.
3. Production retrieval evidence, grounded orchestration, structured answers, and citations remain
   P2.3c through P2.3f.
4. The product page stays non-live until the P2.4 product and operations gate.

## Exit statement

P2.3a is ready for maintainer acceptance when the maintainer confirms:

> An authorized workspace can select an ordered, bounded set of opaque exact Knowledge version
> IDs on a RAG Application draft with fail-closed scope and optimistic concurrency, while
> Application remains independent of Knowledge and production release semantics remain unchanged.

The maintainer accepted this evidence on 2026-07-31. P2.3a is complete, P2.3 remains
`in-progress`, and P2.3b is active.
