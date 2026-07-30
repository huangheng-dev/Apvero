# P2.2e-3 Governed Retrieval Execution Verification

Status: locally verified implementation checkpoint; milestone publication and GitHub CI are
deferred until the complete P2.2 verification candidate.

## Scope

P2.2e-3 connects one query to the existing governed Embedding path:

```text
validate query and compute digest
  -> load scoped Policy, READY Version and pinned Build
  -> resolve and quote the exact Embedding Route
  -> admit KNOWLEDGE_QUERY / EMBEDDING_QUERY
  -> dispatch one query Embedding
  -> settle once or require reconciliation
  -> execute the P2.2e-2 exact ranking kernel
```

It does not yet apply overlap collapse, context budget, current retention disclosure, final
`MATCHES`/`NO_EVIDENCE` projection, REST authorization, telemetry dashboards or the product page.
Those remain P2.2e-4 and P2.2e-5 work.

## Architecture result

- P2 and P2.2e remain `in-progress`.
- Knowledge owns the orchestration and uses only public Identity, Capability Registry and
  Governance APIs.
- Provider SDK, Spring AI, jOOQ records, secrets and provider options do not enter the public
  Knowledge boundary.
- No migration, table, stateful dependency, deployable, queue, framework, REST contract or page
  changed.
- `EmbeddingCapability` remains the single provider-neutral execution SPI.
- The Provider call executes without an open Knowledge transaction. Governance operations keep
  their existing narrow transactional boundaries.

## Query and pinned artifact validation

The executor:

- requires Knowledge to be enabled before resolving any live workflow;
- resolves the authenticated workspace scope;
- strips only boundary whitespace, rejects blank input and more than 20,000 Unicode code points,
  and otherwise preserves query bytes;
- stores and returns only a `sha256:` digest of the normalized UTF-8 query;
- loads the exact Policy Version, READY Index Version and its READY Build in one workspace;
- verifies Build, Version, Route ID, exact Route reference, vector dimension, input limit, batch
  limit and normalization;
- rejects unavailable or mutable Route drift before admission;
- rejects an estimated query larger than the pinned Route input limit.

The query itself is sent only to the selected Embedding adapter. It is not written to Knowledge or
Governance tables, component identities, trace identities, exceptions or normal logs.

## Governance and idempotency

The operation quotes before admission and creates one:

- subject: `KNOWLEDGE_QUERY`;
- component: `EMBEDDING_QUERY`;
- deterministic request-scoped component identity;
- exact pinned Route ID and reference;
- estimated units, cost and currency.

The identity digest includes tenant, workspace, Index Version, Policy Version, caller trace and
query digest. An identical request trace converges on the existing reservation. A previously
settled request is not charged or dispatched again; because query vectors are intentionally not
persisted, the replay returns a stable `APVERO_KNOWLEDGE_QUERY_ALREADY_SETTLED` conflict instead of
pretending to reproduce a response.

Admission denial happens before `markDispatched` and before the Provider call.

## Provider and settlement behavior

Exactly one input is sent with:

- exact pinned Route reference;
- deterministic execution and item identities;
- unprefixed SHA-256 input digest;
- the boundary-trimmed query.

The result must map to that exact input and must match Route ID, reference, dimension and currency.
The returned vector is already validated by Capability Registry and is validated again by the
exact kernel before SQL ranking.

Outcomes are fail-closed:

- success settles actual usage when available, otherwise conservative estimated usage;
- a known Provider failure settles once as failed with its normalized stable code;
- a timeout or other ambiguous paid outcome under a reconciliation-required adapter moves the
  component to `RECONCILIATION_REQUIRED`;
- an existing `DISPATCHED` component is never blindly replayed;
- Provider identity or settlement-ledger failure returns
  `APVERO_KNOWLEDGE_QUERY_SETTLEMENT_CONFLICT` and leaves durable evidence for reconciliation;
- no implementation path automatically retries the Provider.

## Verification evidence

Unit and protocol-stub tests prove:

- exact call order from Route resolution through ranking;
- boundary trimming and digest-only component identity;
- admission denial before dispatch;
- one Provider call and one successful settlement;
- known failure settlement with estimated usage;
- ambiguous outcome and pre-existing dispatch reconciliation;
- no Provider replay after dispatch;
- safe failure when ledger enrichment fails after a Provider response.

The PostgreSQL/Testcontainers integration test uses the real deterministic local Embedding adapter,
real Capability Registry route resolution, real Governance reservation/component persistence and
the real pgvector exact ranking kernel. It proves:

- one matching immutable Chunk is returned at score `1.0`;
- one `KNOWLEDGE_QUERY` reservation reaches `SUCCEEDED`;
- one `EMBEDDING_QUERY` component reaches `SUCCEEDED` with estimated usage and zero local cost;
- no raw query or provider request identity is retained by the deterministic path;
- replaying the same trace creates no second reservation or Provider charge.

## Verification executed

Passed locally:

- complete Knowledge module tests;
- governed execution protocol-stub tests;
- real PostgreSQL 18, Governance, deterministic local Embedding and pgvector integration;
- platform test compilation.

Milestone-level architecture, OpenAPI, Compose, security and complete CI verification remains
deferred to the assembled P2.2 candidate under the repository publication policy.

## Rollback

- revert the P2.2e-3 local implementation commit or use the previous compatible binary;
- keep existing immutable Index, Policy and Governance ledger rows;
- no migration or data reversal is required;
- Knowledge remains disabled by default;
- no endpoint or product page became live.

## Exit statement

P2.2e-3 is locally complete when:

> One authorized, scoped query uses the exact Index Version's pinned Embedding Route, is quoted and
> admitted before dispatch, invokes the Provider at most once, settles a known outcome once,
> requires reconciliation for ambiguity, and passes one validated vector into deterministic exact
> ranking without retaining the raw query or silently replaying billable work.

The next checkpoint is P2.2e-4 policy application and disclosure. P2.2e and P2.2 remain
`in-progress`.
