# P2.2d-1 Build API and Canonical Source Snapshot Verification

Status: implementation checkpoint candidate; P2.2d remains in progress

## Delivered scope

P2.2d-1 exposes the five already-approved Knowledge Index Build operations:

- list builds for one scoped Knowledge Index;
- create a queued Build from an exact semantic version, Embedding Route and Source Revision set;
- read one persisted Build;
- retry a retryable failed Build;
- cancel an unleased queued or retry-wait Build.

Creation locks the active Index, resolves a provider-neutral published Embedding Route snapshot,
selects only active and completely processed Source Revisions, orders the source set
deterministically, and persists the Build, pinned Build Revisions and audit event in one
transaction. Repeating the same Index version with the same Route and revision set returns the
existing Build. Reusing that version with different pinned input fails with a stable conflict.

The API does not start execution or publish an Index Version. Those workflows remain assigned to
P2.2d-2 through P2.2d-4.

## V11 database guard

`V11__p2_2d_build_state_and_publication_guards.sql` is a forward-only guard migration. It adds no
table and no stateful dependency. It:

- permits Entry insertion only while the matching unpublished Build is in
  `EMBEDDING/EMBEDDING`;
- serializes Entry insertion against Build transitions;
- enforces the approved Build transition matrix, exact optimistic-lock increments and monotonic
  progress;
- makes READY and CANCELLED Builds immutable;
- accepts Index Version insertion only from a complete, matching `VALIDATING` Build.

Clean migration through V11 and an in-place V10-to-V11 upgrade are tested. The upgrade test also
asserts that the migration adds no table.

## Security and failure behavior

All reads and writes resolve Identity's scoped Workspace before accessing Knowledge data. Missing
or cross-workspace IDs fail closed as not found. Existing HTTP authorization permits read-scoped
credentials to list and get Builds while denying Build mutations.

Route secrets remain behind Governance references. No provider credential or provider SDK type is
returned by Knowledge. Every accepted create, retry and cancel command appends a bounded audit
event in the same transaction. A forced audit failure proves that both Build and Build Revision
rows roll back.

## Verification coverage

The checkpoint includes:

- Java compilation and Knowledge unit tests;
- deterministic digest tests across locale and time-zone changes;
- OpenAPI/controller conformance for exactly the five live Build operations;
- PostgreSQL clean and upgrade migration checks;
- database transition, publication, immutability and concurrent Entry regression tests;
- HTTP success, conflict, permission, tenant-isolation, idempotency, retry, cancel and audit
  rollback paths;
- Spring Modulith/ArchUnit and the repository-wide verification suite before acceptance.

## Rollout and rollback

V11 has no destructive down migration. Before P2.2d creates a READY Index Version, rollback is:

1. disable Knowledge with `APVERO_KNOWLEDGE_ENABLED=false`;
2. stop accepting new Build commands;
3. deploy the previous V10-compatible application binary;
4. retain V11, Build rows, Build Revision rows and audit evidence for diagnosis and forward
   recovery.

Do not drop V11 functions or triggers and do not delete durable Build rows. Once a later P2.2d
checkpoint publishes a READY Index Version, its verification document must define the compatible
binary floor before rollout.

## Remaining P2.2d work

P2.2d-2 must implement leasing and the transition kernel, P2.2d-3 the governed Embedding
orchestration, P2.2d-4 validation and atomic publication, and P2.2d-5 operations plus final
bilingual evidence. No product page or Worker operation becomes live in this checkpoint.
