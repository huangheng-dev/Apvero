# P2.2 Immutable Index and Retrieval Lab Acceptance Candidate

Status: accepted by the maintainer on 2026-07-31; retained as the candidate evidence record.

P2.2 and P2.2f remain `in-progress`. Knowledge remains disabled by default and the Knowledge
product surface remains non-live. Maintainer approval and a green clean-host candidate CI run are
required before either status can become `completed`.

## Candidate outcome

The implementation is intended to support this bounded statement:

> In one authorized workspace, Apvero can transform deterministic immutable Chunks into a
> governed, dimension-safe immutable Knowledge Index Version, publish it atomically after durable
> validation and recovery, and inspect exact PostgreSQL cosine retrieval with bounded lineage,
> current retention disclosure and typed no-evidence behavior without cross-workspace leakage,
> mutable production references, duplicate cost settlement or unsupported scale claims.

This milestone does not bind an Application or ReleaseBundle, produce a cited answer, expose a
live Knowledge page, add approximate or hybrid retrieval, or claim semantic quality from the
deterministic local Embedding adapter. Those boundaries remain assigned to later P2 milestones.

## Implemented evidence

| Area | Candidate evidence |
|---|---|
| Architecture | Knowledge remains one modular-monolith boundary and uses only approved Identity, Capability Registry and Governance public APIs |
| Persistence | Scoped immutable Index, Build, Entry, Embedding Route and Retrieval Policy state is protected by PostgreSQL constraints, triggers and forward Flyway migrations |
| Embedding | Quote, admission, dispatch, settlement, ambiguity and reconciliation paths are durable and do not blindly replay an ambiguous provider call |
| Build | Persisted leases, bounded concurrency, validation, failure taxonomy, retry, exhaustion, cancellation, restart reconciliation and atomic publication are covered |
| Retrieval | Exact cosine SQL scopes before ranking, applies deterministic ordering and budgeting, and returns bounded `MATCHES` or typed `NO_EVIDENCE` |
| Isolation | Tenant/workspace, Index Version, Build, Entry, Policy and Retrieval paths fail closed, including real foreign identifiers |
| History | A READY immutable Version remains reproducible after later source tombstoning; new Builds exclude tombstoned sources |
| Governance | Cost is reserved and settled once; retention is applied at read time; raw query, content, URL, provider identity and secret values are excluded from unsafe telemetry |
| Contracts | Implemented Knowledge controller methods conform to the committed OpenAPI 3.1 contract; structured payloads retain stable schemas and errors |
| Operations | Health, low-cardinality metrics, safe logs, disabled-by-default rollout and PostgreSQL-only mandatory state are preserved |
| Internationalization | Matching English and Simplified Chinese plans, verification records, operating limits and this candidate record are present |

## Local full-gate evidence

Passed on 2026-07-31:

- `.\gradlew.bat test :apps:platform-server:bootJar`;
- console frozen install, strict typecheck, Vitest, locale coverage, placeholder validation and
  production build;
- Worker locked sync, 19 Python tests and Ruff;
- all nine JSON Schemas, both OpenAPI 3.1 documents and both Compose configurations;
- forbidden core-provider import scan;
- Platform Server and AI Worker container builds, non-root users, pinned Worker base digest and
  prohibited runtime-module checks;
- an isolated PostgreSQL/Platform/Worker stack with every service healthy;
- persisted ingestion retry across Platform restart, completing the same job on attempt 2;
- Index runner disabled behavior, automatic publication of two pending Builds, and crash recovery
  from a persisted `IN_FLIGHT` Build;
- isolated-stack cleanup, including its temporary containers, networks and PostgreSQL volume.

The complete Java run includes Spring Modulith, ArchUnit, unit, integration, Testcontainers,
Flyway and PostgreSQL/pgvector verification. No existing maintainer stack or volume was used.

## Measured envelope

[`p2-2f-performance-envelope.md`](p2-2f-performance-envelope.md) records the executable benchmark,
reference machine and full plans. The deliberately conservative supported complete workflow is at
most 1,000 Entries per immutable Index Version at 256 dimensions. Retrieval-only evidence covers
10,000 Entries at 256 dimensions and 5,000 Entries at 384, 768 and 1,536 dimensions. Every
declared scenario remained below the local 300 ms database/JDBC p95 target, including eight-reader
write pressure. This is a measured reference envelope, not a portable SLA.

## Clean-host candidate evidence

Draft [PR #37](https://github.com/huangheng-dev/Apvero/pull/37) contains one cumulative P2.2
candidate commit, `67127305662b51ddf3ac669ebf28c16c161d504f`. Its Git Data API publication
verified every changed Blob, the complete Tree, the Commit parent and the branch Ref. The remote
Tree `56d99ab21814367475cb62fb3b453f94136ad45c` exactly matches the locally verified `HEAD` Tree.

[CI run 30564010885](https://github.com/huangheng-dev/Apvero/actions/runs/30564010885) passed all
seven jobs:

- backend;
- console;
- worker, including `pip-audit`;
- contracts;
- Compose configuration;
- container builds and runtime security;
- `knowledge-compose`.

The clean-host `knowledge-compose` job completed the real five-source workflow, including
`https://example.com/`, unchanged resynchronization and tombstone rejection. It then proved
persisted retry across Platform restart, disabled and automatic Index Build behavior, persisted
`IN_FLIGHT` crash recovery, service-state capture and isolated-stack cleanup.

The earlier local OSV TLS interruption and public-web fetch failure are therefore classified as
local external-network limitations, not product failures. No code, timeout, architecture or test
fixture was weakened to bypass them.

## Acceptance procedure

Completed:

1. The cumulative P2.2 candidate was committed and published.
2. One P2.2 Draft PR was opened.
3. Every clean-host CI job, including dependency audit and `knowledge-compose`, passed.
4. Candidate PR, head Commit, Tree and CI identities are recorded above.

The maintainer accepted P2.2 on 2026-07-31. The final evidence map and post-acceptance state are
recorded in [`p2-2-acceptance.md`](p2-2-acceptance.md).

## Rollback

Knowledge remains opt-in. Operational rollback disables the Knowledge runners, drains bounded
work, restores the previous compatible binary and retains additive immutable rows for diagnosis
and forward recovery. Do not drop immutable tables, mutate READY Versions or clear active leases
manually. Candidate documentation and tests can be reverted independently without changing
production data.
