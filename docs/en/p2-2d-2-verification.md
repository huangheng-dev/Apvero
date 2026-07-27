# P2.2d-2 Lease and Transition Kernel Verification

Status: implementation checkpoint candidate; P2.2d remains in progress

## Delivered scope

P2.2d-2 adds the internal Knowledge Index Build lease and transition kernel. It provides:

- bounded, workspace-scoped PostgreSQL claim with `FOR UPDATE SKIP LOCKED`;
- deterministic continuation of unleased active work and recovery of expired leases;
- `lock_version` fencing plus owner, status, step, scope and unexpired-lease compare-and-set;
- lease renewal from PostgreSQL time;
- durable Embedding progress with a contiguous ordinal prefix;
- narrow transitions from Embedding to Indexing and Indexing to Validating;
- deterministic bounded exponential retry without random jitter;
- explicit permanent, exhausted and reconciliation-required failure shapes.

The kernel is package-internal. It does not expose a generic status setter. Every successful
mutation increments `lock_version` exactly once and returns the newly persisted Build snapshot.
Zero affected rows becomes the stable `APVERO_KNOWLEDGE_INDEX_BUILD_LEASE_CONFLICT` outcome.

## Time, attempts and fairness

PostgreSQL `transaction_timestamp()` is authoritative for claim eligibility, lease expiry, renewal,
retry scheduling and mutation validity. A worker host clock cannot acquire or extend ownership.

A waiting Build starts one attempt when claimed. Reclaiming an expired active Build, or continuing
an active Build after one durable unit released its lease, preserves the attempt counter. This
prevents process restarts and fair batch yielding from consuming the retry budget.

One durable progress or forward-transition operation clears the lease. The next scoped claim can
continue the same active attempt. This is required by the P2.2d fairness rule and does not represent
a new retry.

## Security and failure behavior

Every repository predicate includes tenant, workspace and Build identity before ownership and
version checks. Wrong scope, owner, version, status, step or expired lease changes zero rows.
Lease-owner identity and failure details remain internal.

Failure input accepts only a stable bounded code and a bounded category enum. Arbitrary provider
payloads are not retained. Ambiguous failure must be non-retryable, use the `AMBIGUOUS` category and
persist `reconciliation_required=true`. Automatic retry exhaustion persists `FAILED` with
`retryable=true`, preserving the existing audited manual-retry path.

Active cancellation and provider interruption are not implemented. Only the already-approved
unleased waiting cancellation remains valid. Tests prove that cancellation and claim cannot both
win.

## Verification coverage

The checkpoint verifies:

- exact deterministic backoff values, cap and overflow behavior;
- disabled-by-default runner configuration and timeout/lease safety margins;
- deterministic scoped ordering and claim-batch bounds;
- concurrent single-winner claim with skipped locked rows;
- transaction rollback after claim, progress, failure and both forward transitions;
- exact lease-expiry boundary recovery;
- stale predecessor rejection after a successor claim;
- monotonic contiguous progress and complete-only forward transitions;
- due versus future retry eligibility;
- automatic retry, permanent failure, retry exhaustion and ambiguous reconciliation;
- cross-workspace, wrong-owner and stale-version fail-closed behavior;
- cancellation-versus-claim exclusivity;
- V11 clean/upgrade, transition and immutable persistence regression;
- P2.2d-1 API and OpenAPI conformance regression;
- Spring Modulith and Knowledge module tests.

One combined local command attempted to start several independent PostgreSQL containers
simultaneously and one P2.2b container failed to launch. The P2.2b suite was rerun independently
and passed in full; the failure was container startup contention rather than a code or migration
failure. The exact CI backend command, `gradlew test :apps:platform-server:bootJar`, subsequently
passed with the complete repository test suite.

## Architecture and compatibility

No public REST, OpenAPI, JSON Schema, module dependency, table, migration, provider abstraction,
frontend key, Python contract or deployment unit changed. V11 remains the active schema baseline.
The separate `apvero.knowledge.index-build-runner.*` configuration defaults to disabled; P2.2d-5
owns scheduler activation, metrics and health.

Rollback uses the previous P2-compatible binary with V11 retained. No durable Build row is deleted
or rewritten during rollback.

## Remaining P2.2d work

P2.2d-3 must connect the kernel to governed one-batch Embedding and component-ledger recovery.
P2.2d-4 must validate and atomically publish immutable Index Versions. P2.2d-5 must activate the
runner, add bounded metrics and health, run final Compose verification, and publish final
bilingual acceptance evidence. No page or background Build execution is live in this checkpoint.
