# P2.2f Exact Retrieval Performance Envelope

Status: measured local evidence for the P2.2 acceptance candidate; not a portable SLA.

Measurement date: 2026-07-31

## Scope and honesty boundary

The executable harness is
`P22fExactRetrievalBenchmark`. It migrates an empty PostgreSQL database through V11, creates
immutable production-shaped Knowledge artifacts, executes
`JooqKnowledgeIndexPersistenceRepository.EXACT_RETRIEVAL_SQL`, consumes every returned row and
captures `EXPLAIN (ANALYZE, BUFFERS)`.

Run it explicitly:

```powershell
$env:APVERO_P22F_BENCHMARK='true'
.\gradlew.bat :apps:platform-server:test --tests '*P22fExactRetrievalBenchmark'
```

The class does not match Gradle's ordinary `*Test` naming convention and also requires the
environment switch. Normal unit and CI runs therefore do not accidentally perform a local
performance test.

The harness measures the database/JDBC retrieval path. It includes opening a connection and
consuming results, but excludes authentication, authorization, Governance admission, query
Embedding provider latency, HTTP serialization and network latency. English, Simplified Chinese
and mixed query profiles select reproducible vector inputs; this verifies multilingual path
equivalence, not semantic relevance.

`pg_buffercache_evict_all()` is used before each shared-buffer-cold query. This is the PostgreSQL
18 developer-testing mechanism for evicting unpinned shared buffers. It does not clear the host
operating-system page cache. The extension is created only inside the disposable benchmark
database and is not a production migration or dependency.

## Reference machine

| Item | Value |
|---|---|
| Host CPU | Intel Core i9-9900KF, 8 cores / 16 logical processors |
| Host memory | 34,290,765,824 bytes |
| Docker Desktop allocation | 16 logical CPUs, 16,732,790,784 bytes |
| Host OS | Windows 10 10.0 |
| JVM | OpenJDK 25.0.3 |
| Database | PostgreSQL 18.4 |
| Vector extension | pgvector 0.8.5 |
| Measured Entry + Chunk storage | 143,302,656 bytes across all benchmark fixtures |

These values identify one reference run. They are not minimum hardware requirements.

## Exact Retrieval results

Each scenario used five warmups and thirty measured requests. Measured requests rotate English,
Simplified Chinese and mixed profiles and include low threshold, no-evidence/high-threshold and
`topK=100` cases.

| Scenario | Dimension | Entries | Shared-buffer-cold | p50 | p95 | p99 |
|---|---:|---:|---:|---:|---:|---:|
| Small | 256 | 1,000 | 42.709 ms | 47.256 ms | 109.455 ms | 143.274 ms |
| Medium | 256 | 5,000 | 113.704 ms | 73.840 ms | 115.945 ms | 119.505 ms |
| Retrieval measurement limit | 256 | 10,000 | 134.826 ms | 117.931 ms | 168.562 ms | 169.334 ms |
| Medium | 384 | 5,000 | 100.885 ms | 88.235 ms | 145.967 ms | 147.369 ms |
| Medium | 768 | 5,000 | 139.569 ms | 152.910 ms | 282.444 ms | 297.067 ms |
| Medium | 1,536 | 5,000 | 241.053 ms | 142.761 ms | 238.059 ms | 241.701 ms |

The plans retain exact full recall and use scoped B-tree access before cosine ordering. The
10,000×256 plan reports a 94.780 ms database execution time; representative 5,000-Entry plan
execution times are 42.876 ms at 384 dimensions, 89.262 ms at 768 dimensions and 134.088 ms at
1,536 dimensions. There is no ANN index and no global vector scan followed by Java filtering.

An immediately preceding valid run on the same machine was materially faster: 10,000×256 p95 was
62.020 ms, 5,000×768 p95 was 78.626 ms and 5,000×1,536 p95 was 75.446 ms. The table deliberately
records the slower repeat instead of selecting the better-looking result. Docker Desktop and host
load make this a reference envelope, not a deterministic latency promise.

## Concurrent read and write pressure

The acceptance-limit 10,000×256 immutable Version received 80 completed reads from eight clients
while a separate unpublished Build accepted 1,000 Entry inserts.

| Measure | Result |
|---|---:|
| Read p50 | 149.708 ms |
| Read p95 | 178.894 ms |
| Read p99 | 196.346 ms |
| Failed reads | 0 |
| Direct fixture Entry write | 0.554 s |

The direct fixture write proves concurrent table and trigger pressure only. It is not called
governed Build throughput because it deliberately bypasses Embedding execution and the runner.

## Governed Build evidence and supported envelope

The accepted P2.2d runner evidence remains the source for complete Build throughput:

| Entries | Queue through embedding/indexing | Validation | Atomic publication |
|---:|---:|---:|---:|
| 1 | 3.133 s | 0.809 s | 0.495 s |
| 100 | 3.586 s | 0.549 s | 0.192 s |
| 1,000 | 72.248 s | 2.324 s | 0.717 s |

Therefore the P2.2 self-hosted support statement is deliberately narrower than the isolated
retrieval measurement:

- supported complete deterministic Build-to-Retrieval workflow: at most **1,000 Entries per
  immutable Index Version at 256 dimensions**;
- supported runner concurrency baseline: **4**, with the accepted scheduler evidence also covering
  1 and 8;
- measured Retrieval-only evidence: 10,000 Entries at 256 dimensions and 5,000 Entries at
  384/768/1,536 dimensions;
- measured Retrieval target on the reference machine: database/JDBC p95 below 300 ms for the
  declared matrix, including eight-reader write pressure;
- no support claim above the complete 1,000-Entry envelope until the governed runner, recovery,
  publication and retrieval matrix is rerun at the larger size.

This is an intentional safety boundary, not a database maximum.

## Rejection and rerun rules

P2.2 does not add ANN, hybrid search or another vector store to improve these numbers. A future
increase requires a new reproducible run with the same isolation, history, Governance, restart,
publication and retrieval gates. A result is rejected when any request fails, the plan loses its
workspace/version scope, p95 exceeds 300 ms in the declared matrix, publication becomes partial, or
the measured hardware and software versions are absent.

Generated full plans remain under
`apps/platform-server/build/reports/p22f/exact-retrieval-benchmark.md` after a local run and are not
committed because they contain ephemeral fixture identities and very large vector literals.
