# P2.2e-1 Retrieval Policy Publication Verification

Status: implementation candidate; maintainer acceptance and GitHub CI are pending.

## Scope

P2.2e-1 implements the first Exact Retrieval Lab checkpoint:

```text
durable Retention Policy provenance
  -> immutable Retrieval Policy publication
  -> deterministic policy digest
  -> idempotent replay or typed conflict
  -> scoped list API
  -> digest-bearing administrative audit
```

It does not implement query Embedding, vector ranking, context projection, the Retrieval Lab query
endpoint, frontend pages, or a production RAG Run.

## Architecture result

- P2 remains `in-progress`; P2.2e is now `in-progress`.
- Knowledge remains disabled by default.
- Knowledge continues to depend only on Identity, Capability Registry and Governance.
- PostgreSQL remains the only mandatory stateful dependency.
- No module, table, migration, deployable, queue, framework, public REST schema or page was added.
- The existing `contract-only` Retrieval Policy routes and schemas are implemented without drift.

## Implemented behavior

### Durable Retention Policy provenance

`RetentionPolicyCatalog.getOrCreate` atomically materializes the existing effective defaults as
version `1` when a workspace has no row. Concurrent first callers converge on one row. Knowledge
uses only this Governance public boundary and never accesses the Governance table.

An existing persisted policy is returned unchanged. A replay of an already published Retrieval
Policy retains its original provenance even after the workspace Retention Policy advances.

### Immutable policy publication

The public Knowledge boundary exposes immutable publication and workspace-scoped listing.
Publication validates the exact OpenAPI ranges:

- slug and semantic version;
- `topK` from 1 through 100;
- context budget from 128 through 200,000;
- score from 0 through 1, normalized deterministically to the stored six-decimal precision;
- `KEEP` or `COLLAPSE_ADJACENT`.

Apvero assigns:

- `exact-cosine@1.0.0`;
- public estimator identity `apvero-utf8-byte@1.0.0`;
- current durable Retention Policy version;
- `NO_EVIDENCE`;
- canonical SHA-256 digest, creator and UTC time.

The public estimator identity is verified against the already approved internal implementation
identity `apvero-utf8-byte-v1`; the frozen estimator was not renamed or changed.

### Idempotency and concurrency

- equal slug/version and equal caller behavior returns the existing immutable policy;
- a later Retention Policy update does not break replay of an old policy;
- reused slug/version with different behavior returns
  `APVERO_KNOWLEDGE_RETRIEVAL_POLICY_VERSION_CONFLICT`;
- equal digest under another identity returns
  `APVERO_KNOWLEDGE_RETRIEVAL_POLICY_DUPLICATE`;
- concurrent equal first publications converge on one policy, one Retention Policy row and one
  explicit publication audit;
- a stored digest mismatch fails closed as
  `APVERO_KNOWLEDGE_RETRIEVAL_POLICY_INTEGRITY_INVALID`.

The repository uses `INSERT ... ON CONFLICT DO NOTHING`, followed by scoped identity resolution.
It does not recover from a uniqueness error inside an aborted PostgreSQL transaction.

### Audit and rollback

Governance adds a narrow `appendWithDigest` operation. It accepts only a
`sha256:[a-f0-9]{64}` digest and writes that single safe detail; it does not expose an unrestricted
details map to business modules.

Policy insertion, default Retention Policy materialization and publication audit join one
transaction. A forced audit failure rolls back both new rows.

## Security and isolation evidence

- GET requires read or admin scope; POST requires write or admin scope.
- Reader credentials cannot publish.
- every repository predicate contains tenant and workspace scope;
- listing another workspace returns no owner policies;
- actor, source IP and trace values are bounded;
- audit details contain only the policy digest;
- no query, content, provider value, secret, path or URL is introduced.

## Verification executed

Passed:

- Governance module tests;
- Knowledge module tests;
- Retrieval Policy controller tests;
- P2.2e-1 PostgreSQL/Testcontainers workflow tests;
- eight-way concurrent first-publication test;
- audit-failure transaction rollback test;
- Spring Modulith and architecture test;
- platform-server bootable JAR build;
- JSON Schema parse check;
- Redocly OpenAPI validation;
- default and Knowledge-profile Compose configuration checks;
- `git diff --check`.

The local command equivalent to the backend CI started successfully and built the JAR. Its complete
platform-server suite was stopped after an unrelated existing P1 Testcontainers class exceeded the
default PostgreSQL startup timeout under the loaded Windows Docker engine. The new P2.2e-1
container test uses a three-minute startup ceiling, kept its health check, and passed repeatedly.
GitHub CI remains the clean-environment full-suite authority for this candidate.

Redocly reported only the two pre-existing warnings that the public platform health operation and
internal worker health operation do not define a 4XX response.

## Rollback

- switch to the previous compatible binary;
- keep immutable policies, Retention Policy and audit rows;
- no down migration is required;
- disabling Knowledge rejects policy access through the existing capability gate;
- no already published row is mutated or deleted.

## Exit statement

P2.2e-1 is ready for maintainer review when:

> An authorized workspace can publish and list an immutable Retrieval Policy with durable
> Retention Policy provenance, deterministic digest, stable idempotency/conflict behavior and
> digest-bearing audit evidence, while concurrent first publication converges safely and every
> failure remains tenant-scoped and transactional.

Acceptance completes only P2.2e-1. The next checkpoint is P2.2e-2 exact retrieval kernel.
