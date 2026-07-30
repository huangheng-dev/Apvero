# P2.2 Immutable Index and Retrieval Lab Acceptance Candidate

Status: candidate assembled on 2026-07-31; not yet accepted.

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

## External gates still open

The local environment could not complete two network-dependent checks:

1. `pip-audit` reached neither PyPI nor OSV reliably. The final OSV attempt ended with
   `SSL: UNEXPECTED_EOF_WHILE_READING`; it produced no vulnerability result and is not recorded as
   a pass.
2. The five-source Compose workflow completed text, Markdown, PDF and DOCX, but the required real
   `https://example.com/` capture failed after three bounded retries. The host showed the same
   external connectivity failure. The product correctly persisted typed
   `APVERO_KNOWLEDGE_WEB_FETCH_TIMEOUT` / `APVERO_KNOWLEDGE_WEB_FETCH_FAILED` outcomes.

These failures did not justify a code, timeout, architecture or test-fixture change. The clean-host
candidate CI run must pass both checks before maintainer acceptance.

## Acceptance procedure

1. Commit and publish the cumulative P2.2 candidate branch.
2. Open one P2.2 candidate pull request.
3. Require all clean-host CI jobs, including dependency audit and `knowledge-compose`, to pass.
4. Record candidate PR, head commit and CI run identities.
5. Ask the maintainer to accept P2.2 explicitly.
6. Only after approval, mark P2.2f and P2.2 `completed` and create the final acceptance record.

## Rollback

Knowledge remains opt-in. Operational rollback disables the Knowledge runners, drains bounded
work, restores the previous compatible binary and retains additive immutable rows for diagnosis
and forward recovery. Do not drop immutable tables, mutate READY Versions or clear active leases
manually. Candidate documentation and tests can be reverted independently without changing
production data.
