# P2.3f Closure and Compatibility Hardening Verification Candidate

Status: accepted by the maintainer on 2026-07-31. P2.3 remains `in-progress` until its cumulative
candidate passes clean-host CI and receives separate milestone acceptance.

## Scope

P2.3f assembles and hardens the complete Application-to-cited-Run workflow:

```text
source snapshot
  -> immutable revision and recoverable ingestion
  -> immutable published index version
  -> exact retrieval policy
  -> opaque Application draft binding
  -> immutable Manifest 1.1 RAG ReleaseBundle
  -> governed grounded Run
  -> retained evidence
  -> evidence-derived verified citations
```

It also preserves governed CHAT execution for actual historical Manifest 1.0 releases and explicit
Manifest 1.1 CHAT releases. P2.3f does not make Knowledge product pages live; that remains P2.4.

## Authority and boundaries

- Stage: P2 / P2.3 / P2.3f.
- Primary modules: Application, Release, Runtime, Knowledge, and Capability Registry.
- Runtime dependencies remain limited to approved public APIs.
- No cross-module SQL, module, deployable, database, queue, framework, migration, or mandatory
  stateful dependency was added.
- PostgreSQL and pgvector remain the only mandatory stateful baseline.
- Spring AI remains the single core Java AI abstraction.
- ADR-0006 already authorizes this compatibility and closure work.

## Compatibility defect found and corrected

The closure audit found that actual historical seed releases contain Model Route and Prompt
references such as `local-deterministic@1.0.0`, while current capability governance resolves the
canonical implemented identity `local-deterministic@1`. Unit-level Provider compatibility tests
did not exercise that governed database path, so the historical release could validate but fail
before execution.

Runtime now creates an execution-only projection for Manifest 1.0:

- an actual historical `name@N.0.0` reference becomes canonical `name@N` in memory;
- the stored ReleaseBundle, Manifest JSON, artifact digest, and release identity are unchanged;
- Manifest 1.1 is never normalized;
- non-zero semantic forms such as `name@1.2.3` are not guessed or silently redirected.

The integration suite now executes both the seeded Manifest 1.0 CHAT release and an explicit
Manifest 1.1 CHAT release through governance. Neither creates RAG retrieval evidence. Manifest 1.1
RAG remains routed exclusively through grounded execution and cannot silently fall back to CHAT.

## Reproducibility and restart behavior

Runtime execution resolves the exact immutable ReleaseBundle before dispatch and never consults a
later mutable Application draft. A compatibility test changes the draft binding after release,
executes the old release, clears all mocked Provider and retrieval state, and then reads the
persisted Run and verified citation lineage from PostgreSQL.

Source resynchronization creates a new immutable revision and an unchanged snapshot remains an
audited no-op. Tombstoning excludes the source from future builds but does not filter an already
published Index Version at retrieval time. This preserves old-release membership while allowing a
new release to pin a later Index Version.

Persisted ingestion leases recover after process loss at every durable step. Ambiguous external
Provider outcomes remain `RECONCILIATION_REQUIRED`; deterministic pre-dispatch failures release
their reservation; terminal Runs and releases remain immutable.

## Security, retention, and failure separation

The cumulative tests prove:

- cross-tenant and cross-workspace source, retrieval, Run-evidence, and citation reads fail closed;
- hostile evidence remains bounded JSON data and cannot choose capabilities or policy;
- only markers present in the same Run's retained evidence can become citations;
- source locators are generated at authorized read time and no local/object-store path is stored;
- retention can discard Run input and output while retaining the minimum immutable citation
  lineage;
- sensitive input fields are recursively masked when payload retention is enabled;
- `NO_EVIDENCE`, Knowledge disabled, malformed output, invalid citation, safe Provider failure,
  pre-dispatch failure, and ambiguous external outcome remain distinct stable outcomes;
- known Provider usage and cost are preserved when output or citation validation fails.

## Contract and rollback floor

Manifest 1.1 changes from `contract-only` to Release and Runtime `baseline`. Manifest 1.0 remains
`legacy-live` and immutable. Grounded Answer 1.0, Citation 1.0, and the citation-list operation
remain baseline.

Once a Manifest 1.1 RAG release exists, a P1-only binary is below the supported rollback floor.
Before publishing the P2.3 milestone, rollback may disable RAG execution or restore the last P2.3
binary while retaining V13. Existing ReleaseBundles, terminal Runs, evidence, citations,
reservations, usage, and cost must not be rewritten.

## Verification evidence

Focused verification passed for:

- historical Manifest 1.0 CHAT, explicit Manifest 1.1 CHAT, and Manifest 1.1 RAG;
- immutable authoritative RAG release pinning and rollback on invalid binding;
- strict Manifest, Grounded Answer, and Citation contract status;
- source resynchronization, unchanged no-op, tombstone, and scoped content;
- ingestion crash recovery, retry, idempotency, and workspace isolation;
- exact pgvector ranking, cross-workspace denial, and published tombstone history;
- governed retrieval settlement and current retention;
- immutable-release-only Runtime execution and PostgreSQL-backed citation reads;
- the covered typed grounded success and failure paths.

The final closure audit also added a real isolated Compose path from a public text upload through
ingestion, immutable Index publication, policy publication, RAG Application binding, Manifest 1.1
Release creation, governed Run execution and verified Citation inspection. That path exposed and
fixed three integration defects hidden by fixture and mocked boundaries:

- captured Source Revisions legitimately have no processing version until immutable Documents and
  Chunks exist, so publication now validates their authoritative processing versions without
  weakening a non-null Revision declaration;
- CHAT route projections now exclude EMBEDDING route rows whose CHAT-only fields are intentionally
  null;
- each ordered Retrieval receives a deterministic child trace identity so its Knowledge governance
  reservation cannot collide with the Run root trace used by Chat governance.

The clean isolated run persisted one uploaded Revision, one Build Entry, one published Index
Version, one Manifest 1.1 RAG Release, one successful grounded Run and one `[K1]` Citation whose
source Revision exactly matched the Build's frozen Revision. A foreign workspace could not read
that Citation.

Complete local verification:

- Gradle: 333 tests across 96 suites, 0 failures, 0 errors, with the expected opt-in exact
  retrieval benchmark skipped; `bootJar` passed;
- AI Worker: 19 tests, Ruff, and dependency audit passed with no known third-party
  vulnerabilities;
- Console: strict TypeScript, 5 unit tests, production build, and 405/405 English/Simplified
  Chinese leaf-key coverage passed;
- contracts: all JSON parsed, the Manifest 1.1 RAG example passed Draft 2020-12 AJV validation,
  and both OpenAPI documents passed lint with only the two established health/info 4xx warnings;
- default and Knowledge-profile Compose configuration passed;
- Platform Server `runtime-prebuilt` and AI Worker container images built successfully;
- Git whitespace validation passed.

## Known limits

1. Verified citation lineage does not prove universal semantic answer correctness.
2. Product live-state, bilingual page acceptance, and operational presentation remain P2.4.
3. Evaluation, reranking, hybrid search, OCR, broad connectors, streaming, and tools are not
   silently included.
4. The compatibility projection supports the actual historical zero semantic form only; it does
   not guess an identity for arbitrary imported semantic route versions.

## Exit statement

The maintainer accepted the following P2.3f statement on 2026-07-31:

> Apvero preserves real Manifest 1.0 CHAT execution, supports explicit Manifest 1.1 CHAT and
> grounded RAG without fallback, executes only immutable release pins, survives loss of in-memory
> execution state, keeps old published index behavior reproducible after resync or tombstone, and
> returns workspace-scoped citations derived only from retained Run evidence.
