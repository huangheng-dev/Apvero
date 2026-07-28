# P2.2d-5 Production Runner and Operations Verification — Implementation Plan

Status: implementation candidate; business coding not started

Target: P2 / P2.2d-5

Authority: ADR-0006, the approved P2.2d durable Build baseline, and verified P2.2d-1 through
P2.2d-4 implementation

Reasoning level: high

## 1. Outcome

P2.2d-5 closes the durable Knowledge Index Build workflow:

```text
eligible Build
  -> bounded workspace-fair claim
  -> one fenced step execution
  -> durable progress or classified failure
  -> reclaim until READY / FAILED / CANCELLED / reconciliation-required
  -> observable health, metrics and safe diagnostics
  -> reproducible Compose acceptance evidence
```

The slice is complete when the existing Build state machine can run automatically, stop safely,
recover after interruption, expose bounded operational signals and prove the complete P2.2d
workflow in English and Simplified Chinese.

This is not a general distributed job platform. It does not activate Retrieval Lab, make Index
Version REST operations live, add a frontend page, introduce a provider health probe, promise
exactly-once external calls or claim queue-scale throughput.

## 2. Change declaration

| Item | Decision |
|---|---|
| Stage | P2 / P2.2d-5, `in-progress` |
| Primary module | `knowledge` |
| Supporting modules | Existing `identity`, `capability-registry`, `governance` public APIs |
| Allowed dependencies | Knowledge → Identity, Capability Registry, Governance |
| REST / OpenAPI / JSON Schema | No change; the accepted five Build operations remain live and Version list remains `contract-only` |
| Database migration | None |
| Stateful dependency / deployable | None; PostgreSQL remains the only mandatory stateful dependency |
| AI abstraction | Existing Spring AI/provider-neutral capability boundary only |
| Frontend / Python | No product behavior change; cumulative checks remain required |
| Exposure | Build automation is doubly gated and disabled by default at the outer Knowledge switch |

ADR-0006 and the approved P2.2d baseline authorize this runner and operations work. No invariant,
module boundary, public contract, release semantic, security policy or technology baseline changes,
so no new ADR is required. Implementation stops for architecture review if it requires a new
table, queue, deployable, module dependency, provider SDK type or public endpoint.

## 3. Current implementation inventory and gaps

The implementation must compose existing authority instead of creating a parallel workflow:

- `KnowledgeIndexBuildTransitionKernel` owns scoped claim, lease renewal, fenced transition,
  retry and terminal failure;
- the Embedding, validation and publication orchestrators each execute one already-claimed step;
- `WorkspaceScopeCatalog` is the public Identity seam for background-processing scopes;
- `KnowledgeIndexPersistenceRepository` is the only Build persistence seam;
- `KnowledgeIndexBuildRunnerProperties` already defines claim batch, lease, provider-call,
  commit-margin and retry timing;
- the ingestion runner demonstrates bounded scheduling but is not reused as a generic business
  runner;
- current Knowledge health reports only the parser worker state;
- current Compose enables Knowledge through an overlay but does not expose the separate Build
  runner configuration.

The missing work is one Build runner, lifecycle state, operational snapshots, bounded telemetry,
Compose acceptance and bilingual verification. No Build state or publication algorithm is missing.

## 4. Runner ownership and lifecycle

### 4.1 Two independent gates

The runner accepts claims only when both are true:

1. `apvero.knowledge.enabled`;
2. `apvero.knowledge.index-build-runner.enabled`.

The outer feature switch remains `false` by default. The Build runner switch also remains `false`
by default. Enabling the ingestion runner never implicitly enables Index Builds.

The runner exposes four internal lifecycle states:

- `disabled`: one or both configuration gates are off;
- `accepting`: scheduled ticks may claim work;
- `draining`: new claims are forbidden while submitted work receives a bounded completion window;
- `stopped`: the executor is closed and no local work is running.

These tokens are operational state, not new domain states.

### 4.2 Bounded executor and scheduling

Add these validated properties under `apvero.knowledge.index-build-runner`:

| Property | Default | Rule |
|---|---:|---|
| `poll-interval` | `1s` | positive, at most 24 hours |
| `concurrency` | `4` | 1–64 |
| `graceful-drain` | `30s` | positive, at most 24 hours |

Existing `claim-batch` remains 1–100. Each tick computes free local capacity first and passes
`min(free capacity, claim-batch)` to the transition kernel. A fixed-size platform-thread executor
with a bounded queue no larger than configured concurrency owns execution. Free capacity includes
active and queued tasks. Unbounded queues, unbounded virtual-thread fan-out and one executor per
workspace are forbidden.

`@Scheduled` uses fixed delay. An atomic tick guard prevents overlapping scans even if future
scheduler configuration becomes concurrent. Rejected submission releases no additional claim:
the runner stops scanning and lets the already-persisted lease expire for safe reclaim.

### 4.3 Workspace fairness

The runner obtains scopes only from `WorkspaceScopeCatalog.listForBackgroundProcessing()`.
Repository claims remain tenant/workspace scoped; no cross-workspace Build SQL is introduced.

The sorted workspace list is visited from a rotating in-memory cursor. One workspace may claim no
more than the remaining tick capacity, and the next tick starts after the last visited workspace.
This prevents the first stable workspace from permanently consuming all local capacity. Process
restart may reset the cursor without affecting correctness.

The lease owner is a bounded opaque per-process value. It may be persisted for fencing and debug
correlation but never appears as a metric label, health detail, API response or normal log field.

### 4.4 One claim, one durable unit

The runner dispatches by the claimed Build's exact status and step:

| Claimed state | Operation | Durable outcome |
|---|---|---|
| `EMBEDDING / EMBEDDING` | execute one governed embedding batch | progress and release, advance to INDEXING, retry/fail, or reconciliation |
| `INDEXING / INDEXING` | reconstruct and validate the complete artifact | advance to VALIDATING or fail |
| `VALIDATING / VALIDATING` | atomically publish | READY, equal replay, or fail |

Any mismatched or terminal state is a stable state conflict and is never guessed into another
step. One claim performs at most one provider dispatch and one durable unit. Continuing work is
reclaimed on a later tick to preserve fairness and crash recovery.

### 4.5 Failure normalization

The runner does not replace orchestrator-specific decisions. It normalizes only unexpected
failures that escape a step:

- lease/state/concurrency conflicts become a bounded stale-lease outcome and do not overwrite a
  newer owner;
- known transient local failures enter the existing retry policy only if the current lease is
  still owned;
- validation/integrity failures are non-retryable;
- ambiguous provider dispatch is reconciliation-required and never blind-retried;
- an unknown failure is recorded with one stable internal category when safe ownership can still
  be proven; otherwise the lease expires for reclaim.

Logs contain step, bounded outcome and stable code only. They never contain IDs, source text,
vectors, URLs, provider bodies, credentials, lease owner or raw SQL.

## 5. Lease timing and shutdown

The existing invariant remains:

```text
lease-duration > external-call-timeout + commit-margin
```

The runner must not silently assume a Java timeout cancels a provider request. The pinned Route
timeout remains the execution timeout and must be no greater than the configured
`external-call-timeout` safety ceiling; an unsafe Route/runner combination fails before dispatch.
A step renews its lease before an external call or publication section when the remaining
database-time lease budget cannot safely cover that declared call plus commit margin.

Shutdown follows this order:

1. atomically change `accepting` to false;
2. prevent subsequent scheduled claims;
3. close executor submission;
4. wait at most `graceful-drain`;
5. preserve interruption and stop waiting when the process is interrupted;
6. never write a synthetic success or failure merely because drain timed out.

After the bound, unfinished provider work may still be ambiguous. The persisted lease and existing
recovery matrix determine the next process's action. Forced thread interruption is not treated as
proof that an external request did not happen.

## 6. Metrics contract

Use Micrometer and bounded enum/boolean tags only. The required meter families are:

| Meter | Type | Bounded tags |
|---|---|---|
| `apvero.knowledge.index.build.claimed` | counter | `step` |
| `apvero.knowledge.index.build.queue.wait` | timer | `step` |
| `apvero.knowledge.index.build.step.duration` | timer | `step`, `outcome`, `error_category` |
| `apvero.knowledge.index.build.attempt` | counter | `step`, `attempt_bucket` |
| `apvero.knowledge.index.build.batch.items` | distribution summary | `outcome` |
| `apvero.knowledge.index.build.batch.units` | distribution summary | `quality`, `outcome` |
| `apvero.knowledge.index.build.entries` | distribution summary | `kind`, `outcome` |
| `apvero.knowledge.index.build.retry` | counter | `step`, `error_category` |
| `apvero.knowledge.index.build.stale.lease` | counter | `step`, `operation` |
| `apvero.knowledge.index.build.recovery` | counter | `action`, `outcome` |
| `apvero.knowledge.index.build.publication.validation` | counter | `outcome`, `error_category` |
| `apvero.knowledge.index.build.publication` | counter | `outcome` |
| `apvero.knowledge.index.build.inflight` | gauge | none |
| `apvero.knowledge.index.build.oldest.eligible.age` | gauge | none |
| `apvero.knowledge.index.build.reconciliation` | gauge | none |

The allowed vocabularies are compile-time enums. `attempt_bucket` is a fixed set such as
`1`, `2`, `3`, `4_plus`, never the configured or raw attempt value. Count summaries record numeric
values, not IDs.

Tenant, workspace, Build, Index, Route, provider request, Chunk, source, URL, content, exception
message and lease owner are forbidden as tags. Tests enumerate every registered tag key and value
and fail on identity-shaped or unbounded additions.

Operational gauges are backed by an in-memory immutable snapshot refreshed by the runner scan, not
an unscoped database query on every scrape. Oldest age is clamped to zero and marked unavailable
until the first successful scan.

## 7. Health contract

Add a separate `knowledgeIndexBuildRunner` health contributor. Do not overload the parser-worker
health component and do not call an embedding provider.

Health details are bounded and contain:

- `featureEnabled`;
- `runnerEnabled`;
- `accepting`;
- `lifecycle`;
- `inFlight`;
- `oldestEligibleBuildAgeSeconds`, or `unknown`;
- `reconciliationCount`, or `unknown`;
- `lastScanOutcome`;
- `snapshotAgeSeconds`.

Expected status:

- both gates intentionally disabled: `UP`, lifecycle `disabled`;
- enabled, accepting and last scan current: `UP`;
- draining during controlled shutdown: `UP`, lifecycle `draining`;
- enabled but repeated scope/claim scans fail or the snapshot becomes stale beyond a documented
  multiple of `poll-interval`: `DOWN`;
- reconciliation-required Builds do not make the service `DOWN`; their count is an operator action
  signal.

Health does not expose IDs, error messages, routes, endpoints or provider availability. Readiness
must reflect the local runner's ability to accept durable work, not model quality.

## 8. Operational snapshot queries

Add only Knowledge-owned, workspace-scoped aggregate reads for:

- oldest Build eligible for claim;
- reconciliation-required terminal Build count.

The runner aggregates those results across scopes returned by the public Identity catalog and
publishes one immutable local snapshot. Query predicates use the same eligible statuses,
`next_attempt_at`, database time and lease-expiry rules as `claimBuilds`; a second eligibility
definition is forbidden.

A failed workspace scan marks the whole snapshot incomplete and retains no misleading “zero.”
No tenant or workspace cardinality is exported. No new table, materialized view or cache service is
needed.

## 9. Performance and supported envelope

P2.2d uses PostgreSQL polling and O(entries) publication validation deliberately. Acceptance must
measure this honestly instead of advertising arbitrary scale.

The reference envelope is:

- up to 1,000 immutable Entries in one Build;
- up to 100 eligible Builds across 20 workspaces in the scheduler fairness test;
- runner concurrency 1, 4 and 8;
- one full validation/publication transaction at 1, 100 and 1,000 Entries.

CI enforces correctness, bounded completion and absence of deadlock with explicit test timeouts.
It records observed queue, validation and publication duration as evidence, but does not turn one
shared-runner wall-clock result into a universal latency SLA. Any higher supported envelope must be
measured and documented before a scale claim.

The test proves:

- claims never exceed configured local capacity;
- every workspace eventually receives a claim under sustained work;
- duplicate workers do not execute the same active lease;
- publication retains atomicity at the maximum reference corpus;
- metric cardinality remains constant as workspace and Build counts grow.

## 10. Compose acceptance

Extend Compose configuration with explicit Build runner variables while keeping safe defaults:

- base Compose: Knowledge and Build runner remain disabled unless selected;
- Knowledge overlay: enables parser/ingestion dependencies but does not silently claim production
  Build readiness;
- a verification invocation explicitly enables the Build runner and uses the deterministic local
  provider adapter; it never requires a paid key.

The acceptance script must:

1. build or use the exact tested images;
2. start PostgreSQL, platform server and required local worker;
3. wait for container and application health;
4. create or load deterministic two-workspace fixtures;
5. request equal and distinct Builds through accepted APIs;
6. observe automatic progress to one immutable READY Version;
7. prove equal request/publication replay creates no duplicate;
8. restart the platform server during eligible/in-flight work and prove lease-based recovery;
9. prove cross-workspace reads and mutations fail closed;
10. inspect metrics and health for required fields and forbidden identities;
11. run with the Build runner disabled and prove no new claim occurs;
12. save commands, image identities, test summaries and sanitized operational samples.

Preview or demo state is not server success evidence. Compose evidence comes from persisted API,
database and audit assertions.

## 11. Security, safe errors and audit

- authorization remains deny-by-default on the accepted Build APIs;
- every repository operation remains tenant/workspace scoped;
- no provider key, Base URL, vector, source content or raw request/response enters health, metrics
  or normal logs;
- stable backend codes remain the localization boundary; no new user-visible hard-coded message is
  added;
- Build request, manual retry/cancel, terminal reconciliation/failure and successful publication
  retain the existing audit policy;
- scheduled claim and per-batch progress remain typed state/metrics, not administrative audit
  spam;
- metrics and health endpoints follow existing actuator authorization and exposure rules.

This slice adds no secret, retention, egress or plugin permission behavior.

## 12. Verification matrix

### 12.1 Focused runner tests

- both gates and every lifecycle transition;
- no overlapping poll and no claim beyond free capacity;
- rotating workspace fairness and empty/failed scope scans;
- exact state-to-orchestrator dispatch;
- one provider batch at most per claim;
- rejected submission, executor failure and safe lease expiry;
- graceful drain completion, timeout and interruption;
- stale owner cannot persist a runner-normalized failure;
- retry, ambiguous dispatch and reconciliation paths;
- metric names, tag vocabularies and identity redaction;
- health status, snapshot staleness and no paid-provider invocation.

### 12.2 PostgreSQL and recovery tests

- eligible ordering uses database time and matches claim semantics;
- lease expiry, renewal, stale worker and two-runner races;
- two-workspace scope isolation for claim and aggregate reads;
- automatic EMBEDDING → INDEXING → VALIDATING → READY;
- crash/restart at every P2.2d failure-matrix boundary;
- admission denial occurs before provider invocation;
- partial/extra/missing/wrong-lineage/dimension/digest artifacts never publish;
- equal publication replay and two-publisher race remain deterministic;
- audit failure rolls back publication;
- clean V11 and V10-to-V11 migrations remain green with no new migration.

### 12.3 Cumulative gate

- Spring Modulith and ArchUnit;
- all Java unit/module/Testcontainers suites and `bootJar`;
- OpenAPI and JSON Schema compatibility;
- Flyway migration tests;
- TypeScript strict typecheck, unit tests and Playwright critical paths;
- English/Simplified Chinese key validation;
- Python tests, Ruff and type checks;
- secret, dependency, image and source scans;
- Compose builds, health and the deterministic acceptance flow;
- bounded runtime-path performance evidence.

P1 CHAT, P2.1 ingestion, P2.2a/b/c and all P2.2d-1 through d-4 behavior must remain green.

## 13. Bilingual evidence

Implementation produces matching:

- `docs/en/p2-2d-5-verification.md`;
- `docs/zh-CN/p2-2d-5-verification.md`.

Both documents contain the same commit and image identities, commands, environment, configuration,
test counts, failure-matrix results, corpus sizes, observed timings, known limitations, rollback
floor and unresolved risks. English remains the source document; Simplified Chinese has equal
feature coverage and is not a shortened summary.

Evidence must distinguish:

- automated pass/fail proof;
- measured local/CI observations;
- architectural assertions inherited from an approved ADR;
- limitations that remain for later stages.

P2.2d is not marked complete until both files exist, parity checks pass and the maintainer accepts
the recorded evidence.

## 14. Implementation checkpoints

1. **d5.1 — runner lifecycle and bounded dispatch**
   - configuration, double gate, fixed executor, rotating workspace scan, step dispatch, drain and
     focused unit tests.
2. **d5.2 — operational telemetry and recovery**
   - workspace-scoped snapshot queries, metrics, health, safe failure normalization and
     PostgreSQL concurrency/recovery tests.
3. **d5.3 — Compose, envelope and final evidence**
   - deterministic end-to-end acceptance, reference corpus measurements, cumulative gates,
     matching bilingual evidence and stage-status proposal.

These are coherent commits in one future `feature/` implementation branch and PR. This `docs/`
branch contains planning only. Maintainer acceptance of d5.3 is still required before changing
P2.2d to completed or starting P2.2e.

## 15. Rollout and rollback

- the outer Knowledge switch and separate Build runner switch remain fail-closed defaults;
- rollout starts with deterministic local capability, concurrency 1 and an observed small corpus;
- operators inspect queue age, reconciliation count, failures and publication outcome before
  increasing concurrency;
- disabling the Build runner stops new claims first and drains only for the configured bound;
- disabling does not cancel, delete or rewrite Build, Entry, Version, Governance or audit evidence;
- before any READY Version exists, the previous compatible binary may run while retaining V11;
- after READY exists, the ADR-0006 P2-compatible rollback floor applies;
- rollback never resolves a mutable `latest` resource or falls back to ungrounded production chat.

## 16. Self-critique and rejected shortcuts

1. Scanning all workspaces is bounded only by the current Identity catalog. The rotating cursor
   prevents first-workspace starvation, but very large installations will eventually need a
   proven partitioning boundary; P2 does not pretend otherwise.
2. An in-memory health snapshot can be stale after a process pause. Explicit snapshot age and
   `unknown` values are more honest than an expensive unscoped query on every scrape.
3. Fixed platform threads cap local resource use but cannot make provider calls exactly-once.
   Ambiguous dispatch remains reconciliation-required.
4. Graceful shutdown cannot prove a timed-out external request stopped. Interrupt-driven success
   or blind retry is rejected.
5. Full publication validation is O(entries) and holds locks. The 1,000-Entry reference envelope
   is a measured P2 support statement, not a forever architecture limit or a universal SLA.
6. PostgreSQL polling is operationally simpler for self-hosting but is not Kafka-scale scheduling.
   Adding a queue without a demonstrated boundary and ADR is rejected.
7. Reconciliation count is an action signal, not liveness failure. Marking the application DOWN
   merely because human review is required would create restart loops without repairing evidence.
8. A generic runner abstraction shared with ingestion would hide different recovery semantics.
   Reusing patterns and public seams is safer than forcing both workflows into one framework.
9. Metrics without strict vocabulary tests tend to acquire IDs during incident debugging.
   Cardinality and redaction tests are part of correctness, not optional observability polish.
10. READY proves structural reproducibility only. Retrieval relevance, ranking and evaluation
    remain P2.2e responsibilities.

Rejected shortcuts include a database-global claim query, unbounded virtual threads, provider
health probes, mutable publication, synthetic shutdown success, raw exception labels, mock Compose
success, paid-key acceptance, activating Version APIs and starting Retrieval Lab early.

## 17. Approval gate

Maintainer approval of this plan authorizes only P2.2d-5 implementation as specified. It does not
authorize P2.2e, frontend activation, a new migration/table/queue/deployable/stateful dependency,
public contract changes, another AI framework, provider types in core APIs or a new scale claim.
