# P2.2d-3 Governed Embedding Orchestration Verification

Status: implementation checkpoint candidate; P2.2d remains in progress

## Delivered scope

P2.2d-3 connects one leased Knowledge Index Build to one governed Embedding batch. It provides:

- deterministic reconstruction from the durable contiguous Entry cursor;
- exact Route resolution, quotation and execution through the provider-neutral
  `EmbeddingCapability`;
- idempotent Governance admission with a scoped immutable component snapshot;
- short lease-fenced transactions around admission, dispatch, Entry persistence, settlement and
  progress;
- provider I/O outside database transactions;
- at most one provider call per claim;
- complete-batch Entry persistence with stale-worker rejection;
- recovery from durable component and Entry evidence without inventing actual usage;
- bounded stable failure normalization that does not retain provider payloads.

The deterministic local Spring AI adapter and the existing OpenAI-compatible adapter are reached
through one capability implementation. Provider SDK types do not enter Knowledge APIs.

## Recovery and accounting

The durable cursor, not the first missing Entry, reconstructs the next batch. This recovers the
window where Entries committed but component settlement or Build progress did not.

- Equal Entries with a non-terminal component settle from the frozen estimate with `ESTIMATED`
  usage and do not call the provider.
- Equal Entries with a succeeded component advance progress without Governance or provider
  mutation.
- Unsafe unresolved dispatch becomes reconciliation-required and never auto-replays.
- Partial, conflicting, or terminal-ledger/inconsistent-artifact evidence fails closed.
- A successor lease fences every mutation attempted by the previous worker.

The normal path uses actual provider units when available. A safe timeout is retryable; an unsafe
timeout is ambiguous; provider rejection is permanent; invalid output is validation failure;
missing Secret material is security failure; unknown exception text is reduced to a bounded
internal code.

## Verification evidence

Unit and integration coverage proves:

- all component state × Entry state × replay-policy combinations;
- deterministic next-batch reconstruction and already-written unsettled recovery;
- stale lease rejection in the kernel and complete-batch writer;
- scoped component projection and cross-workspace not-found behavior;
- component admission rollback when an enclosing lease-fenced transaction rolls back;
- one governed PostgreSQL end-to-end batch with a 256-dimensional vector;
- Entries-committed-before-settlement recovery with zero repeated provider calls;
- settlement-committed-before-progress recovery with zero repeated provider calls;
- previous-owner rejection after successor claim;
- transition to `INDEXING` only after the complete durable cursor;
- stable failure category, retry, reconciliation, and sensitive-detail reduction.

Executed gates:

```text
gradlew :modules:capability-registry:test :modules:knowledge:test
gradlew :apps:platform-server:test --tests P22d3KnowledgeEmbeddingOrchestrationIntegrationTest
gradlew test
gradlew check bootJar
git diff --check
```

All gates passed. The full repository suite included historical P1 Governance, P2.1 ingestion,
P2.2c Embedding, P2.2d-1 Build API, P2.2d-2 lease, Flyway/Testcontainers and Spring architecture
regressions.

## Architecture and compatibility

No REST, OpenAPI, JSON Schema, database migration, table, queue, deployable, frontend key, Python
contract, Release semantic, Runtime behavior or new stateful dependency changed. Knowledge keeps
only its approved dependencies on Identity, Capability Registry and Governance.

The runner remains disabled. This checkpoint does not make background Build execution or a product
page live. A previous compatible binary can ignore the internal orchestrator and additive
Governance snapshot while retaining existing V11 data.

## Remaining P2.2d work

P2.2d-4 must validate and atomically publish immutable Index Versions. P2.2d-5 must activate the
bounded runner, add metrics and health, complete Compose verification, and publish final bilingual
acceptance evidence. Maintainer acceptance is required before this checkpoint is merged or the
next dependent implementation begins.
