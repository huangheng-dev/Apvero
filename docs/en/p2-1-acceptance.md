# P2.1 Durable Ingestion Spine Acceptance

Status: accepted by the maintainer on 2026-07-24.

Target: P2 milestone P2.1. P2 remains `in-progress`.

## Accepted outcome

The P2.1 acceptance statement is satisfied without qualification:

> From an authorized workspace, Apvero can capture a supported source safely, preserve an
> immutable revision, resume its persisted processing after failure, create deterministic
> traceable chunks exactly once, and expose honest inspection, retry, resynchronization, and
> tombstone behavior without adding infrastructure or making incomplete RAG claims.

P2.1 delivers the durable ingestion spine only. It does not deliver Embedding, an immutable
Knowledge Index Version, Retrieval Lab, Application binding, Release binding, cited Runs, or a
live Knowledge product surface. Those remain P2.2–P2.4.

## Evidence map

| Gate | Accepted evidence |
|---|---|
| Architecture | Spring Modulith and ArchUnit verify the `knowledge` boundary and approved dependencies |
| Migration | V8 clean install, V7 upgrade, scoped foreign keys, constraints, indexes, immutable triggers, and forward-only mitigation |
| Isolation | Repository and REST command/query coverage fails closed across tenants and workspaces |
| Immutability | Source Revision, Document, and Chunk mutation protection plus deterministic replay/non-determinism rejection |
| Jobs | Persisted leases, exclusive claim, every durable-step crash, expiry recovery, retry, exhaustion, cancellation, graceful shutdown, and restart recovery |
| Sources | Text, Markdown, PDF, DOCX, and captured public HTML success and bounded failure paths |
| Source security | Media detection, MIME spoofing, executable/macro/encryption/malformed/archive/size limits and pinned-address SSRF controls |
| Contracts | Committed OpenAPI 3.1 validation for Worker payloads/responses and Platform P2.1 method/path conformance |
| Operations | Atomic audit mutations, low-cardinality metrics, safe errors, log-redaction tests, health, and disabled-by-default rollout |
| Deployment | Internal-only non-root/read-only Worker, no public parser route, optional Knowledge Compose overlay, PostgreSQL-only mandatory state |
| Internationalization | Matching English and Simplified Chinese plans, verification records, operations guidance, roadmap, and acceptance evidence |
| End to end | Real Compose creates all five sources, reaches `READY`, inspects lineage, verifies unchanged resync and tombstone rejection, then proves persisted retry across Platform restart |

Slice evidence:

- [`p2-1a-verification.md`](p2-1a-verification.md)
- [`p2-1b-verification.md`](p2-1b-verification.md)
- [`p2-1c-verification.md`](p2-1c-verification.md)
- [`p2-1d-verification.md`](p2-1d-verification.md)
- [`p2-1e-verification.md`](p2-1e-verification.md)
- [`p2-1f-verification.md`](p2-1f-verification.md)
- [`p2-1-acceptance-candidate.md`](p2-1-acceptance-candidate.md)

## Git and CI evidence

- Candidate: [PR #10](https://github.com/huangheng-dev/Apvero/pull/10), head
  `7f529f0e65e9ae0550526b9b1ff6ad555458f5e6`.
- Accepted merge: `f259de456aa9e902b82ad84460e6fd6185a0a289`.
- Candidate CI: [run 30028841073](https://github.com/huangheng-dev/Apvero/actions/runs/30028841073).
- Post-merge `main` CI: [run 30029244123](https://github.com/huangheng-dev/Apvero/actions/runs/30029244123).
- Both runs passed backend, console, worker, contracts, Compose configuration, container builds,
  and `knowledge-compose`.

## State after acceptance

- `architecture/delivery-stages.yaml` records P2.1 as `completed`.
- P2 remains `in-progress`; the next milestone is P2.2.
- `modules/knowledge` remains `in-progress` and `contract-only`.
- `APVERO_KNOWLEDGE_ENABLED=false` remains the default.
- No product page or REST operation becomes live because of this milestone transition.
- No invariant, dependency boundary, public contract, release semantic, security policy,
  migration, stateful dependency, or technology baseline changes in this acceptance update.

## Rollback and follow-up

Operational rollback remains fail-closed: disable the Knowledge runner, drain bounded work, use
the previous compatible binary, and retain additive V8 rows for diagnosis and forward recovery.
Do not drop immutable tables or clear active leases manually.

Two non-blocking CI annotations remain separate maintenance work: `pnpm/action-setup@v4` reports
its deprecated Node action runtime, and GitHub Actions repairs the Linux executable bit for
`gradlew`. Neither changed the successful P2.1 evidence, and neither is silently folded into this
stage-transition commit.
