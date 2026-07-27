# P2.2d-2 Lease and Transition Kernel — Implementation Plan

Status: planning candidate; maintainer approval required before implementation

Target: P2 / P2.2 / P2.2d-2

Authority: ADR-0006, the P2.2d durable Build baseline, V11 database guards, and the current
Knowledge module contracts.

## 1. Outcome and boundary

This checkpoint adds the internal PostgreSQL lease and state-transition kernel used by later
P2.2d execution checkpoints. It must prove that one worker can own one durable Build unit, that an
expired owner cannot write after a successor claims it, and that retries and terminal failures are
persisted deterministically.

It does not run Embedding, call Governance, publish an Index Version, add a scheduler, activate a
page, change OpenAPI, or add a database migration. P2.2d-3 owns governed Embedding orchestration;
P2.2d-4 owns validation and publication; P2.2d-5 owns the production runner and operational gate.

Target module: Knowledge only. Identity remains an allowed dependency for scoped background
workspace enumeration, but this checkpoint receives an already authorized `WorkspaceScope` and
does not introduce a new cross-module call.

## 2. Architecture decision check

No ADR is required. The plan stays within ADR-0006's approved PostgreSQL lease runner and the
P2.2d state machine. It adds no module, deployable, stateful dependency, public contract, release
semantic, security-policy exception, or technology baseline.

V11 already contains every required persistence field and database guard. A missing field or an
incompatible guard discovered during implementation stops the checkpoint for architecture review;
it must not be worked around with a generic table, an in-memory lease, or an unreviewed V12.

## 3. Kernel shape

Use one package-private `KnowledgeIndexBuildTransitionKernel` backed by narrow repository
operations. Do not expose a generic `transition(build, nextStatus)` method.

The kernel accepts bounded values and returns immutable internal results:

- `claim(scope, owner, capacity)` returns zero or more claimed Build snapshots;
- `renew(scope, claim)` extends an owned, unexpired lease;
- `recordEmbeddingProgress(scope, claim, progress)` records monotonic progress without changing
  the durable step;
- `advanceToIndexing(scope, claim)` and `advanceToValidating(scope, claim, evidence)` encode only
  approved forward transitions;
- `releaseForRetry(scope, claim, failure)` schedules deterministic retry or persists terminal
  failure;
- `fail(scope, claim, failure)` persists an explicit non-retry or reconciliation outcome;
- later P2.2d-4 adds the separate publication transaction rather than a `READY` transition here.

Every successful mutation returns the new Build snapshot and its incremented `lock_version`.
Zero affected rows is a typed stale/concurrent result, never an unconditional retry and never a
silent success.

## 4. Claim and reclaim transaction

Claims are workspace-scoped and bounded:

1. validate nonblank bounded owner, positive capacity and bounded lease configuration;
2. select eligible rows for the exact tenant and workspace in
   `next_attempt_at NULLS FIRST, created_at, id` order;
3. use `FOR UPDATE SKIP LOCKED LIMIT ?`;
4. eligibility is an unleased due `QUEUED`/`RETRY_WAIT`, or an active
   `EMBEDDING`/`INDEXING`/`VALIDATING` row whose lease expired;
5. a waiting claim changes status to the active status matching `current_step`, increments
   `attempt_count`, clears `next_attempt_at`, sets `started_at` only once, sets owner and expiry,
   and increments `lock_version`;
6. reclaim preserves status, step, attempt, progress, digests and failure evidence, replaces owner
   and expiry, and increments `lock_version`;
7. commit before any external or expensive work.

Database time is the lease authority for selection, expiry comparison and persisted expiry.
Application `Clock` may drive deterministic policy tests, but production SQL must not depend on a
worker host's wall clock. Lease duration is added to the same database timestamp captured by the
claim transaction.

An expired active lease represents unknown execution history. Reclaim never resets or advances
durable state and never proves that a provider call was absent.

## 5. Fencing and mutation rules

`lock_version` is the fencing token; no second token or table is added. Each leased mutation uses
one SQL compare-and-set containing:

```text
tenant_id + workspace_id + build_id
+ expected status/step
+ expected lease_owner
+ expected lock_version
+ lease_until > database_now
```

The update increments `lock_version` exactly once. The caller must replace its claim snapshot
after every successful mutation. A stale snapshot, wrong workspace, wrong owner, expired lease or
successor claim changes zero rows.

Lease renewal extends from database now, not from the previous expiry. It may not revive an expired
lease. One durable unit releases owner and expiry so another Build can be scheduled fairly. No
network call or long digest computation is allowed inside a claim/transition transaction.

## 6. Attempts, retry and failure

An attempt starts when a waiting Build is claimed. Reclaim of an expired active Build does not
increment the attempt because it continues reconciliation of the same durable attempt.

Automatic failure handling is one transaction:

- retryable and `attempt_count < maximum_attempts` becomes `RETRY_WAIT`, retains the current step,
  clears the lease, records the bounded error, and sets `next_attempt_at`;
- retryable with the automatic window exhausted becomes `FAILED` with `retryable=true`, allowing
  the already implemented audited manual retry to reset the window;
- permanent or integrity failure becomes `FAILED` with `retryable=false`;
- ambiguous dispatch becomes `FAILED`, `reconciliation_required=true`, and is never automatically
  retried;
- `completed_at` is set only for `FAILED`; `RETRY_WAIT` remains incomplete.

Backoff is deterministic:

```text
delay = min(backoffMaximum, backoffBase * 2^(attemptCount - 1))
```

Overflow is saturated before multiplication. There is no random jitter. The separate
`apvero.knowledge.index-build-runner.*` configuration is validated so base, maximum, lease,
provider-timeout margin and capacity are positive and bounded. P2.2d-2 defines and tests the
policy; P2.2d-5 wires scheduling and shutdown.

## 7. Cancellation boundary

The existing public cancel operation remains the only cancellation path in this checkpoint. It
accepts only unleased `QUEUED` or `RETRY_WAIT` rows. Active Build cancellation, provider-call
interruption, and `cancellation_requested` mutation are intentionally not added because V11 and the
approved baseline do not permit an active-to-cancelled transition.

The unused persistence field is retained for compatibility and must not be presented as a working
feature.

## 8. Errors, security and telemetry

Internal failures use stable codes for invalid kernel input, lease conflict, state conflict,
attempt exhaustion and bounded failure metadata. Public REST behavior does not change. Lease owner,
failure bodies, provider data, source content and cross-workspace existence never enter normal
responses.

The kernel exposes bounded typed outcomes so P2.2d-5 can instrument:

- claim outcome and claimed count;
- reclaim and renewal outcome;
- transition outcome by bounded status/step;
- retry scheduled/exhausted;
- stale lease rejection.

P2.2d-2 does not bind Micrometer meters or health indicators; that production instrumentation
belongs to P2.2d-5. The typed outcome dimensions are bounded enums only. Tenant, workspace, Build,
owner, Route and error text are forbidden dimensions. Routine claim/progress events do not create
administrative audit records.

## 9. Implementation order

1. Add validated index-build runner properties and deterministic backoff policy with unit tests.
2. Add immutable internal claim/failure/progress records and narrow repository methods.
3. Implement scoped `SKIP LOCKED` claim/reclaim and leased compare-and-set SQL.
4. Implement the transition kernel and typed stale/state outcomes.
5. Prove bounded outcome data and failure redaction; leave meter binding to P2.2d-5.
6. Run module, migration, architecture and cumulative regression verification.

This is one coherent implementation checkpoint and one implementation pull request. Planning and
implementation remain on separate branches.

## 10. Verification matrix

PostgreSQL Testcontainers evidence must prove:

1. two workers cannot claim the same waiting Build;
2. parallel claims skip locked rows and preserve deterministic ordering;
3. a due retry is claimable and a future retry is not;
4. an expired active lease is reclaimable without changing attempt or progress;
5. the predecessor cannot renew, update progress, fail or advance after reclaim;
6. a lease at the exact expiry boundary is expired;
7. a successful mutation increments `lock_version` exactly once;
8. wrong tenant/workspace/owner/version/status/step and expired leases change zero rows;
9. claim transaction rollback leaves the Build claimable;
10. retry delay is deterministic, bounded and overflow-safe;
11. automatic retry, exhausted retry, permanent failure and reconciliation have exact durable
    shapes;
12. progress and attempt counters never decrease or exceed their approved bounds;
13. waiting cancellation wins or claim wins, but no row becomes both active and cancelled;
14. V11 rejects direct illegal transitions that bypass the repository;
15. no typed telemetry outcome exposes a high-cardinality or sensitive dimension.

Also run Knowledge unit/module tests, Spring Modulith and ArchUnit, V11 clean/upgrade tests,
OpenAPI compatibility, Java formatting/static checks, `bootJar`, and P1/P2 cumulative tests. No
frontend, i18n key, Python or new migration change is expected, but cumulative CI remains required.

## 11. Failure injection and rollback

Inject rollback immediately after row selection, after claim update, after retry/failure update,
and after each legal forward transition. Persisted state after rollback must equal the pre-call
state.

This checkpoint has no live scheduler and no schema change. Rollback is therefore the previous
P2-compatible binary with V11 retained. Any rows legitimately mutated by manual test invocation
remain valid under V11 and are later claimable or inspectable; rollback never deletes them.

## 12. Self-review

- A global cross-tenant claim would be simpler but weakens tenant isolation; rejected in favor of
  authorized workspace enumeration plus scoped claim.
- Application-host time would simplify tests but creates clock-skew ownership bugs; rejected in
  favor of PostgreSQL lease time.
- A generic transition API reduces code but makes illegal states easy to express; rejected in
  favor of narrow operations.
- Incrementing attempts on reclaim looks intuitive but can exhaust retries after process crashes
  without a new execution decision; rejected.
- Random retry jitter can smooth load but harms deterministic recovery evidence; omitted until
  measured contention justifies a versioned deterministic jitter policy.
- Active cancellation would be attractive but conflicts with the approved V11 state machine and
  cannot honestly abort an unknown provider call; deferred rather than simulated.
- This kernel alone is not a user feature. It remains internal and non-live until the complete
  P2.2d and later P2 workflow gates pass.

## 13. Approval gate

Approval authorizes implementation of this internal kernel only. It does not authorize P2.2d-3
Embedding calls, Governance contract changes, P2.2d-4 publication, P2.2d-5 scheduler activation,
OpenAPI changes, a migration, a new table/deployable/dependency, or a broader state machine.
