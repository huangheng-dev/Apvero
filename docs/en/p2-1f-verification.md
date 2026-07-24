# P2.1f durable ingestion runner verification

Status: accepted P2.1 implementation checkpoint, not a complete P2 feature claim.

## Delivered boundary

P2.1f completes the persisted source-processing loop inside the existing `knowledge` module:

`QUEUED -> SNAPSHOTTING/PARSING -> CHUNKING -> READY`

The modular monolith polls PostgreSQL by authorized workspace scope. Claim transactions use `FOR UPDATE SKIP LOCKED`, persist a unique process lease, increment a bounded attempt count, and commit before web or Worker I/O. Step output is committed in short transactions. A lease owner and optimistic version must still match, and the lease must remain unexpired, before a result can mutate durable state.

`READY` means deterministic Documents and Chunks exist. It does not mean Embedding, Knowledge Index, retrieval, Application binding, Release, or grounded Run. All P2 REST operations therefore remain `contract-only` until the complete P2 acceptance gate passes.

## Recovery and control behavior

- Execution is at least once; deterministic document and chunk identities make replay a compare-or-no-op operation.
- An expired active lease is reclaimable. An expired lease at the maximum attempt count becomes a retryable terminal `FAILED` job.
- Transient failures use bounded exponential backoff with deterministic jitter. Stable error code and category are persisted without raw content, URL, filename, or exception text.
- Manual retry is allowed only for a retryable `FAILED` job and restarts the attempt budget from the last durable step.
- Cancellation is allowed only for `QUEUED` and `RETRY_WAIT`. Active external work is never reported as cancelled.
- Shutdown stops new claims, drains the bounded executor, then interrupts remaining local work. Persisted leases make interrupted work recoverable.
- Web snapshot completion resets the attempt budget before the independent parsing step.

## Workspace isolation and API

The runner enumerates workspace identities only through `WorkspaceScopeCatalog`; every Knowledge repository call still receives full tenant/workspace scope first. The existing OpenAPI 3.1 routes are implemented without adding or renaming contracts:

- `GET /api/v1/knowledge-ingestion-jobs`
- `GET /api/v1/knowledge-ingestion-jobs/{jobId}`
- `POST /api/v1/knowledge-ingestion-jobs/{jobId}/retry`
- `POST /api/v1/knowledge-ingestion-jobs/{jobId}/cancel`

API failures return stable `APVERO_*` codes. Retry, cancellation, readiness, exhaustion, and web snapshot transitions append audit events in the same database transaction as the business mutation.

## Operations

Knowledge and its runner remain disabled by default until P2 acceptance. Enable them explicitly only in an isolated P2 environment:

| Environment variable | Default | Purpose |
|---|---:|---|
| `APVERO_KNOWLEDGE_ENABLED` | `false` | Enables Knowledge commands, queries, and background capability |
| `APVERO_KNOWLEDGE_RUNNER_ENABLED` | `true` | Enables claims when Knowledge is enabled; set `false` for maintenance |
| `APVERO_KNOWLEDGE_RUNNER_CLAIM_BATCH` | `4` | Maximum jobs claimed per scoped poll |
| `APVERO_KNOWLEDGE_RUNNER_CONCURRENCY` | `4` | Bounded local worker threads |
| `APVERO_KNOWLEDGE_RUNNER_LEASE_DURATION` | `60s` | Exclusive result-commit window; keep above worst expected external step time |
| `APVERO_KNOWLEDGE_RUNNER_POLL_INTERVAL` | `1s` | Delay between polls |
| `APVERO_KNOWLEDGE_RUNNER_BACKOFF_BASE` | `2s` | Initial retry delay |
| `APVERO_KNOWLEDGE_RUNNER_BACKOFF_MAXIMUM` | `5m` | Retry delay ceiling |
| `APVERO_KNOWLEDGE_RUNNER_GRACEFUL_DRAIN` | `30s` | Maximum local shutdown drain |

The supported Compose activation uses the Knowledge overlay. It enables Knowledge and makes
Platform Server startup depend on Worker health without changing the default disabled profile:

```text
docker compose --profile knowledge \
  -f deploy/compose/compose.yaml \
  -f deploy/compose/compose.knowledge.yaml \
  up -d --build --wait
```

The runner publishes low-cardinality Micrometer metrics:

- `apvero.knowledge.ingestion.claimed` tagged by step;
- `apvero.knowledge.ingestion.queue.wait` tagged by step;
- `apvero.knowledge.ingestion.step.duration` tagged by step, outcome, and error category;
- `apvero.knowledge.ingestion.failures` tagged by step, category, and retryability;
- input bytes, output document/chunk counts, and Worker latency tagged only by bounded source or algorithm dimensions.

Tenant, workspace, source, revision, job, URL, filename, and content never become metric tags. Operational logs contain only step, stable category, and stable code.

Safe rollback is `APVERO_KNOWLEDGE_RUNNER_ENABLED=false`, followed by the configured drain. Do not delete job rows or clear active leases manually. A prior binary can inspect all V8 state because P2.1f adds no migration.

## Verification evidence

Automated coverage includes:

- deterministic bounded backoff and invalid configuration;
- scoped exclusive claim and `SKIP LOCKED` behavior;
- expired lease recovery, bounded exhaustion, and manual retry;
- honest cancellation and retry state conflicts;
- cross-workspace query and command denial;
- end-to-end inline source to leased Worker execution, atomic Documents/Chunks, `READY`, and audit;
- the existing five media types, Worker validation, web SSRF controls, immutable replay, migrations, architecture, and internationalization suites.
- recovery after a crash at each persisted `SNAPSHOTTING`, `PARSING`, and `CHUNKING` step;
- automatic transient retry claimed by a new runner identity;
- direct Worker request/response validation against the committed OpenAPI 3.1 contract;
- Platform REST method/path conformance against the committed P2.1 OpenAPI subset;
- log-redaction verification using a deliberately sensitive exception message;
- an enabled Compose workflow covering all five source types, lineage inspection,
  unchanged web resynchronization, tombstone rejection, Worker outage, Platform restart,
  automatic retry, health recovery, and final `READY`.
- local isolated Compose evidence for the five-source workflow and for the same persisted job
  moving from attempt 1 `RETRY_WAIT` to attempt 2 `READY` across a Platform Server restart.
- integration regressions for HTTP/1.1 Worker transport and canonical media-type persistence.

Required command:

```text
./gradlew test check
```

This evidence passed on candidate PR #10 and the resulting `main` run. The maintainer accepted
P2.1 on 2026-07-24. P2 remains in progress and the P2 REST surface remains `contract-only`.
