# P2.3 Application to Cited Run Closure Acceptance Candidate

Status: local cumulative candidate assembled on 2026-07-31. P2.3 remains `in-progress`.

Maintainer milestone acceptance and a green clean-host candidate CI run are required before P2.3
can become `completed`. P2.4 remains planned and owns live bilingual product and operations pages.

## Candidate outcome

The implementation supports this bounded statement:

> From one authorized workspace, Apvero can upload and process a supported source, publish a
> governed immutable Knowledge Index Version, bind its exact version and Retrieval Policy to a RAG
> Application, create an immutable Manifest 1.1 ReleaseBundle, execute a governed Run using only
> those release pins, retain ordered retrieval evidence, and return a verified Citation derived
> from that evidence while failing closed across workspaces and preserving historical CHAT
> compatibility.

This is a server-side lifecycle claim. It does not make partial Console pages live, claim universal
semantic correctness, add hybrid search, reranking, OCR, Evaluation, Tools, MCP, Agent loops,
streaming or external Gateway behavior.

## Slice evidence

| Slice | Accepted result |
|---|---|
| P2.3a | Application owns ordered opaque draft IDs; Knowledge owns exact scoped resolution; forbidden `application -> knowledge` dependency remains absent |
| P2.3b | Release resolves authoritative READY pins and stores a strict immutable Manifest 1.1 plus canonical digest while preserving Manifest 1.0 |
| P2.3c | Runtime persists workspace-scoped ordered Retrieval and Hit evidence with retention-aware content and immutable lineage |
| P2.3d | Runtime executes exact ordered retrieval, bounded untrusted context, typed `NO_EVIDENCE`, governance and immutable-release-only dispatch |
| P2.3e | Structured Grounded Answer validation rejects malformed or fabricated markers and derives public Citations only from retained Run evidence |
| P2.3f | Historical compatibility, restart-read behavior, retention, failure separation, rollback floor and a real upload-to-citation Compose path are hardened |

Detailed bilingual evidence:

- [`p2-3a-verification.md`](p2-3a-verification.md)
- [`p2-3b-verification.md`](p2-3b-verification.md)
- [`p2-3c-verification.md`](p2-3c-verification.md)
- [`p2-3d-verification.md`](p2-3d-verification.md)
- [`p2-3e-verification.md`](p2-3e-verification.md)
- [`p2-3f-verification.md`](p2-3f-verification.md)

## Real end-to-end closure

The isolated Knowledge Compose gate now performs one continuous API and persisted-state workflow:

```text
public text upload
  -> persisted asynchronous ingestion READY
  -> deterministic Document and Chunk
  -> governed Embedding Build
  -> atomic immutable Index Version
  -> immutable Retrieval Policy
  -> RAG Application draft
  -> exact ordered Knowledge binding
  -> Manifest 1.1 ReleaseBundle
  -> governed exact Retrieval
  -> deterministic child Retrieval trace
  -> governed Chat generation
  -> structured Grounded Answer
  -> persisted [K1] evidence
  -> authorized Citation read
```

The clean run asserts:

- the Build uses the Revision created by the public upload path;
- the Release pins the exact Index and Policy references;
- the Run is `SUCCEEDED` with a `GROUNDED` Grounded Answer;
- `[K1]` has the same source Revision and content digest as retained Run evidence;
- the database joins the Citation hit back to the Build's frozen Revision;
- a foreign workspace receives a fail-closed not-found response;
- the deterministic local Provider makes the workflow reproducible without a paid key.

## Closure defects found by self-audit

The cumulative unit and integration suites initially left three real boundaries disconnected.
Adding the one-stack workflow exposed and corrected them:

1. Captured Source Revision rows may have null processing-version metadata because the immutable
   snapshot precedes processing. Publication now treats immutable Document and Chunk versions as
   authoritative while still rejecting any non-null conflicting Revision declaration.
2. The legacy CHAT `ModelRoute` projection attempted to map EMBEDDING rows whose CHAT-only fields
   are null. CHAT route listing and release resolution now exclude EMBEDDING rows; Embedding keeps
   its dedicated public projection.
3. Knowledge query and Chat governance reservations initially reused the Run root `trace_id`,
   violating the durable uniqueness rule. Ordered Retrievals now receive deterministic child trace
   identities while Chat retains the root Run trace.

P2.3f also corrected historical Manifest 1.0 route/prompt references by applying a narrow
execution-only `name@N.0.0 -> name@N` compatibility projection. Stored immutable manifests,
digests and release identities remain untouched; Manifest 1.1 is never normalized.

## Architecture, security and rollback

- The modular-monolith boundaries and approved dependency graph are unchanged.
- No cross-module SQL, new module, deployable, database, queue, framework or mandatory stateful
  dependency was added.
- PostgreSQL with pgvector remains the only mandatory stateful baseline.
- Spring AI remains the single core Java AI abstraction.
- Production execution resolves only an immutable ReleaseBundle.
- Retrieval content is bounded untrusted data and cannot select capabilities or policy.
- Citations are evidence-derived; unknown markers fail rather than being silently removed.
- Workspace scope applies to release resolution, retrieval, evidence and Citation reads.
- Payload retention can omit content while preserving allowed immutable lineage.
- Once a Manifest 1.1 RAG Release exists, a P1-only binary is below the rollback floor. Rollback
  keeps V13 data and restores a P2.3-compatible binary or disables Knowledge fail-closed; it never
  rewrites releases, terminal Runs, evidence, reservations, usage or cost.

## Local gate evidence

The cumulative local verification covers:

- Spring Modulith, ArchUnit, 333 Java unit/module integration/Testcontainers/Flyway tests across
  96 suites with 0 failures and 0 errors; the one opt-in exact retrieval benchmark is skipped by
  design; `bootJar` passed;
- Manifest 1.0 CHAT, Manifest 1.1 CHAT and Manifest 1.1 RAG compatibility;
- all contract JSON parsing, Manifest 1.1 RAG Draft 2020-12 AJV validation and OpenAPI lint; only
  the two established health/info 4xx warnings remain;
- 19 Worker tests, Ruff and dependency audit with no known third-party vulnerabilities;
- Console strict TypeScript, 5 unit tests, production build and 405/405 English/zh-CN leaf keys;
- default and Knowledge Compose configuration;
- non-root Platform Server and Worker image builds;
- isolated healthy PostgreSQL/Platform/Worker upload-to-citation execution;
- temporary Compose container, network and volume cleanup;
- Git whitespace validation.

Clean-host job identities will be recorded after the cumulative candidate commit is published and
CI completes.

## Remaining acceptance procedure

1. Publish the P2.3 candidate branch and one Draft PR through the GitHub API.
2. Verify Blob, Tree, Commit and Ref identities.
3. Require all clean-host CI jobs, including the upgraded `knowledge-compose`, to pass.
4. Present the evidence for explicit maintainer P2.3 acceptance.
5. Only after acceptance, set P2.3 to `completed`; P2 remains `in-progress` and advances to P2.4.
