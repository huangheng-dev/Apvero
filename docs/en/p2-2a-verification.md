# P2.2a Capability and Governance Shell Verification

Status: implementation candidate; maintainer acceptance pending

Target: P2 / P2.2a

Decision authority: ADR-0006 and the maintainer-approved P2.2 implementation plan

## Delivered boundary

P2.2a establishes the prerequisites for governed Embedding execution without claiming that indexing
or retrieval is live:

- P2.2 is recorded as `in-progress`.
- V9 backfills every existing Model Route to `CHAT`.
- V9 adds the discriminated, immutable EMBEDDING Route profile with dimension, aggregate input-unit,
  batch-size, normalization, Model capability, status, and shape constraints.
- The provider-neutral Java API exposes exact Route snapshots, ordered digest-bound inputs, validated
  finite non-zero vectors, usage quality, cost, safe request identity, and latency.
- `apvero-utf8-byte-v1` provides a deterministic conservative input-unit estimator.
- Governance exposes typed execution subjects and billable components while preserving the existing
  P1 single-CHAT API.
- Knowledge component reservation, dispatch, and settlement remain fail-closed until their approved
  persistence is delivered.
- Spring Modulith and ArchUnit protect module internals and prevent provider abstractions from
  entering the Embedding public API.

The matching design record is `docs/en/p2-2a-embedding-decision.md`; the executable bilingual and
adversarial corpus is
`modules/capability-registry/src/test/resources/p2-2a-embedding-corpus.json`.

## Migration and compatibility evidence

The V9 Testcontainers checks prove:

- a clean install reaches V9;
- a real V8 schema upgrades to V9 with exactly one migration;
- a referenced legacy Model missing explicit CHAT retains its capabilities and gains CHAT;
- existing CHAT Route identity, output-token configuration, and null Embedding fields are retained;
- valid EMBEDDING profiles can be stored;
- dimension `16001` is rejected;
- an EMBEDDING Route cannot reference a CHAT-only Model;
- published Route update and delete are rejected.

The P2.1 V7-to-V8 test now targets V8 explicitly, so historical milestone verification does not
drift when later migrations are added.

Previous compatible binaries continue inserting CHAT Routes because `route_capability` defaults to
`CHAT`; they ignore the new nullable fields. Forward rollback deploys the previous binary while
retaining V9 and its data. There is no destructive down migration.

## Verification

| Check | Result |
|---|---|
| `gradlew test :apps:platform-server:bootJar --no-daemon` | Passed; 94 tests, 0 failures, 0 errors, 0 skipped |
| Spring Modulith and ArchUnit | Passed |
| Capability and Governance unit contracts | Passed |
| Clean Flyway migration and V8-to-V9 upgrade | Passed |
| Existing P1 governance and P2.1 ingestion regressions | Passed |
| Redocly OpenAPI 3.1 lint for both contracts | Valid; two pre-existing operation-4xx warnings only |
| Default and Knowledge-profile Compose configuration | Passed |
| English/Chinese decision-document structure | Seven matching sections |
| Corpus fixture parsing and frozen expected units | Ten cases passed |
| `git diff --check` | Passed |

## Honest limitations

This slice does not:

- expose the contract-only EMBEDDING REST create operation as live;
- implement `EmbeddingCapability` with Spring AI or any Provider;
- persist Governance components;
- create Knowledge Index/Build/Entry/Version tables;
- execute paid traffic, build vectors, publish an index, or run Retrieval Lab;
- change the Console or make the Knowledge page live.

Those boundaries are intentional. P2.2b is next and adds scoped immutable Knowledge persistence plus
the Governance component ledger. P2.2c then implements governed Embedding execution.
