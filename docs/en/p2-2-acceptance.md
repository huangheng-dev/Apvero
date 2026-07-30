# P2.2 Immutable Index and Retrieval Lab Acceptance

Status: accepted by the maintainer on 2026-07-31.

Target: P2 milestone P2.2. P2 remains `in-progress`; P2.3 is the next milestone.

## Accepted outcome

The P2.2 acceptance statement is satisfied within its measured boundary:

> In one authorized workspace, Apvero can transform deterministic immutable Chunks into a
> governed, dimension-safe immutable Knowledge Index Version, publish it atomically after durable
> validation and recovery, and inspect exact PostgreSQL cosine retrieval with bounded lineage,
> current retention disclosure and typed no-evidence behavior without cross-workspace leakage,
> mutable production references, duplicate cost settlement or unsupported scale claims.

P2.2 does not bind an Application or ReleaseBundle, generate a cited answer, expose a live
Knowledge product page, add approximate or hybrid retrieval, or claim semantic quality from the
deterministic local Embedding adapter. Application-to-cited-Run closure belongs to P2.3.

## Evidence map

| Gate | Accepted evidence |
|---|---|
| Architecture | Spring Modulith and ArchUnit preserve the Knowledge boundary and its approved Identity, Capability Registry and Governance public dependencies |
| Migration | V9–V11 clean-install and upgrade coverage proves scoped Embedding, immutable Index persistence, durable Build state, constraints, triggers and forward mitigation |
| Immutability | Routes, Policies, Builds, Entries and READY Index Versions are versioned, digest-protected and fail closed against mutation or partial publication |
| Embedding | Quote, admission, dispatch, success/failure settlement, ambiguity and reconciliation paths are durable and prevent blind replay or duplicate cost |
| Build | Persisted leases, bounded concurrency, deterministic replay, validation, retry, exhaustion, cancellation, restart reconciliation and atomic publication are covered |
| Retrieval | Exact cosine SQL scopes before ranking, uses deterministic tie-breaking and context budgeting, and returns bounded `MATCHES` or typed `NO_EVIDENCE` |
| Isolation | Tenant/workspace, Version, Build, Entry, Policy and full REST retrieval tests fail closed, including real foreign identifiers |
| Historical reproducibility | READY Versions continue to retrieve their immutable history after later source tombstoning; new Builds exclude tombstoned sources |
| Governance and retention | Cost is reserved and settled once; current retention disclosure is applied before output and telemetry; sensitive data is not exported unsafely |
| Contracts and security | OpenAPI 3.1 conformance, stable errors, write/admin authorization, secret exclusion and provider-type isolation are verified |
| Operations | Health, low-cardinality metrics, safe logs, persisted recovery, isolated Compose cleanup and disabled-by-default rollout are retained |
| Internationalization | Matching English and Simplified Chinese plans, verification, performance boundary, candidate evidence and this acceptance record are present |
| Performance | Executable local evidence defines a conservative complete-workflow limit of 1,000 Entries at 256 dimensions and a measured exact-retrieval matrix below 300 ms p95 on the reference machine |
| End to end | Clean-host Compose proves all five sources, resync, tombstone rejection, restart retry, automatic Build publication and persisted `IN_FLIGHT` recovery |

Detailed evidence:

- [`p2-2a-verification.md`](p2-2a-verification.md)
- [`p2-2b-verification.md`](p2-2b-verification.md)
- [`p2-2c-verification.md`](p2-2c-verification.md)
- [`p2-2d-5-verification.md`](p2-2d-5-verification.md)
- [`p2-2e-5-verification.md`](p2-2e-5-verification.md)
- [`p2-2f-performance-envelope.md`](p2-2f-performance-envelope.md)
- [`p2-2-acceptance-candidate.md`](p2-2-acceptance-candidate.md)

## Git and CI evidence

- Candidate: [PR #37](https://github.com/huangheng-dev/Apvero/pull/37).
- Cumulative implementation commit:
  `67127305662b51ddf3ac669ebf28c16c161d504f`.
- Accepted candidate head with CI evidence:
  `1e3ba62f22ce7f55f3d80aa1ae96c674ecf1d1b8`.
- Verified source Tree:
  `79cb316645ac4879088dc46f4c38951039f9c06d`.
- Initial candidate CI:
  [run 30564010885](https://github.com/huangheng-dev/Apvero/actions/runs/30564010885).
- Accepted candidate-head CI:
  [run 30564605515](https://github.com/huangheng-dev/Apvero/actions/runs/30564605515).

Both clean-host runs passed backend, console, worker including dependency audit, contracts,
Compose configuration, container builds/security and `knowledge-compose`.

## State after acceptance

- `architecture/delivery-stages.yaml` records P2.2a–P2.2f and P2.2 as `completed`.
- P2 remains `in-progress`; P2.3 is still `planned`.
- The Knowledge module remains `in-progress` and the product page remains non-live.
- `APVERO_KNOWLEDGE_ENABLED=false` remains the default.
- PostgreSQL with pgvector remains the only mandatory stateful dependency.
- No Application binding, ReleaseBundle knowledge pin or cited production Run becomes live through
  this transition.
- No invariant, module boundary, public schema shape, release semantic, security policy,
  internationalization policy, stateful dependency or technology baseline changed in the
  acceptance update.

## Rollback and follow-up

Operational rollback remains fail closed: disable and drain Knowledge runners, restore the
previous compatible binary and retain additive immutable rows for diagnosis and forward recovery.
Do not mutate READY Versions, drop immutable tables or clear active leases manually.

P2.3 must now close the next coherent workflow:

`Application draft -> immutable knowledge binding -> ReleaseBundle pin -> governed retrieval -> cited Run evidence`

The existing `pnpm/action-setup@v4` Node runtime deprecation annotation remains separate
maintenance work. It did not weaken or fail the accepted P2.2 evidence.
