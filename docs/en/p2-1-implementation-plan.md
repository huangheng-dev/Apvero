# P2.1 Durable Ingestion Spine — Implementation Plan

Status: maintainer-approved plan; implementation has not started and is not authorized by this documentation change

Target stage: P2, milestone P2.1

Decision baseline: ADR-0006 (accepted)

Feature flag: `APVERO_KNOWLEDGE_ENABLED=false` until full P2 acceptance

## 1. Outcome

P2.1 will establish one durable, restart-safe ingestion workflow:

```text
authorized workspace
  -> Knowledge Base
  -> bounded source submission or safe web capture
  -> immutable Source Revision
  -> persisted leased ingestion job
  -> stateless parser/chunker worker
  -> immutable Document and Chunk lineage
  -> inspect, retry, resynchronize, or tombstone
```

P2.1 does not produce embeddings, indices, retrieval results, Application bindings, releases, grounded Runs, or live product pages. Those remain P2.2–P2.4 work. All P2 REST operations remain `contract-only` and all partial product surfaces remain demo, planned, hidden, or disabled until the complete P2 gate passes.

## 2. Change declaration required before editing

| Item | P2.1 decision |
|---|---|
| Stage | P2 / P2.1, currently planned |
| Primary module | New physical `knowledge` module |
| Supporting modules | `identity` and `governance`; no Application, Release, or Runtime dependency |
| Allowed dependencies | Declared: `knowledge -> identity, capability-registry, governance`; actually exercised in P2.1: `identity`, `governance` only |
| Public contracts | P2.1 Knowledge REST subset and internal Worker API 1.0 |
| Migration | One forward-only migration after V7; no down migration |
| New stateful dependency | None |
| New deployable | None |
| AI abstraction | None used in P2.1; Spring AI remains the only approved Java AI abstraction |
| Product exposure | Disabled/non-live |

No P2.1 slice may add Kafka, Redis, MinIO, Milvus, Elasticsearch, another database, another AI framework, or a browser-accessible parser endpoint.

## 3. Approved contract correction before coding

The P2.0 contract-only baseline originally placed `EMBEDDING`, `INDEXING`, and `VALIDATING` in `IngestionJobStatus` and `currentStep`. That conflicted with the domain model, which gives those states to the separate P2.2 `IndexBuild` lifecycle.

The approved correction is:

```text
Source ingestion status:
QUEUED | SNAPSHOTTING | PARSING | CHUNKING |
READY | RETRY_WAIT | FAILED | CANCELLED

Source ingestion current step:
SNAPSHOTTING | PARSING | CHUNKING | COMPLETE

Index build status (unchanged, P2.2):
QUEUED | EMBEDDING | INDEXING | VALIDATING |
READY | RETRY_WAIT | FAILED | CANCELLED
```

This is a pre-implementation correction to operations explicitly marked `contract-only`; no live client is affected. The maintainer approved it on 2026-07-22. P2.1 must not invent a combined job merely to preserve the mistaken enum.

The same correction pass must also specify that chunk offsets are zero-based Unicode code-point offsets into normalized document text and use a half-open interval `[startOffset, endOffset)`. Page, paragraph, and line anchors remain one-based.

## 4. Domain ownership and public Java boundary

The module root is `io.apvero.platform.knowledge`. Only types directly under that package are public module APIs. Controllers live in `.api`; repositories, job execution, worker clients, web capture, media detection, and persistence mappings live in `.internal`.

Planned public interfaces:

- `KnowledgeBaseCatalog`: create and list workspace-scoped bases.
- `KnowledgeSourceCatalog`: list sources and revisions, submit inline/file/web sources, add revisions, synchronize, tombstone, and stream authorized snapshot content.
- `KnowledgeIngestionCatalog`: list/get jobs and request retry/cancellation.

Planned public records use only Java/JDK types and provider-neutral identifiers:

- `KnowledgeBase`, `KnowledgeSource`, `KnowledgeSourceRevision`;
- `KnowledgeIngestionJob` and explicit status/step/outcome enums;
- commands and receipts matching the OpenAPI schemas.

Rules:

1. `knowledge` obtains tenant/workspace scope only through `WorkspaceScopeCatalog`; it never queries identity tables.
2. Security mutations append an audit event through `AuditEventCatalog` in the same Spring transaction. Audit failure fails the mutation.
3. No provider SDK, Application, Release, Runtime, vector, or embedding type enters the module.
4. Repositories accept both tenant and workspace scope for every read and write. An unscoped repository method is forbidden.
5. Cross-workspace identifiers return the same stable not-found result and do not reveal existence.

## 5. Persistence design

P2.1 adds only ingestion tables. P2.2 index and retrieval tables are deliberately excluded.

### `knowledge_base`

Key fields: `id`, `tenant_id`, `workspace_id`, `slug`, `name`, `description`, `status`, `version`, `created_at`, `updated_at`.

Constraints include composite workspace scope, unique `(workspace_id, slug)`, positive optimistic version, bounded status, and an index on `(workspace_id, updated_at desc)`.

### `knowledge_source`

Key fields: `id`, full scope, `knowledge_base_id`, `name`, `source_type`, `status`, protected web locator metadata, latest revision number/identity, optimistic version, timestamps, and tombstone metadata.

The canonical web URI is write-only at the REST boundary. It may be stored for resynchronization but must never be logged, emitted in unrestricted projections, or used without repeating SSRF validation.

### `knowledge_source_revision`

Key fields: `id`, full scope, `source_id`, monotonically increasing `revision`, `content_digest`, `media_type`, `byte_size`, safe original filename, captured response metadata, immutable snapshot bytes, snapshot status, parser/chunker versions, and `created_at`.

Rules:

- SHA-256 uses the exact stored bytes and the canonical form `sha256:<64 lowercase hex>`.
- `(source_id, revision)` and `(source_id, content_digest)` are unique.
- Snapshot bytes are bounded and stored in PostgreSQL `bytea`; local disk is temporary processing space only.
- Update and ordinary delete are rejected by a database trigger.

### `knowledge_document`

Key fields: `id`, full scope, `source_revision_id`, ordinal, title, normalized text digest, parser version, processing profile, and `created_at`.

The normalized text itself is not duplicated when chunks plus the immutable source are sufficient. If parser reconstruction tests show it is required for stable offsets, its bounded storage must be justified in the migration review before implementation.

### `knowledge_chunk`

Key fields: `id`, full scope, `source_revision_id`, `document_id`, ordinal, immutable text, content digest, start/end offsets, page/heading/paragraph/line anchors, chunker version, and `created_at`.

Unique `(document_id, ordinal)` and digest/offset checks make duplicate persistence fail closed. Document and Chunk rows are insert-only. A repeated processing result must compare equal and become a no-op; a different result for the same revision/profile fails with `APVERO_KNOWLEDGE_NON_DETERMINISTIC_OUTPUT` and never overwrites lineage.

### `knowledge_ingestion_job`

Key fields: `id`, full scope, base/source/revision identities, job kind, status, current step, sync outcome, attempt count, maximum attempts, next attempt time, lease owner/until, optimistic lock version, idempotency key, stable error code/category, safe failure metadata, cancellation request, started/completed timestamps, and created/updated timestamps.

Constraints include one active idempotency identity per requested operation, legal status/step combinations, non-negative attempts, and scoped foreign keys. Error metadata cannot contain source content, raw URLs, credentials, stack traces, or provider responses.

The migration must use composite foreign keys inside Knowledge and may reference the existing workspace composite key for integrity. Knowledge runtime SQL must not read the workspace table; authorization remains a call to Identity's public API.

## 6. Source workflows

### Inline text and Markdown

1. Authorize workspace and validate base.
2. Enforce character and encoded-byte limits before allocation grows unbounded.
3. Normalize transport encoding only; persist the submitted UTF-8 snapshot bytes unchanged after accepted encoding conversion.
4. In one transaction create source, revision, queued job, and audit evidence.
5. Return `202` receipt; no parsing occurs in the request transaction.

### PDF, DOCX, Markdown, and text upload

1. Stream or buffer only within the measured configured envelope.
2. Compute digest while capturing bytes.
3. Detect actual media structure; filename and request content type are hints only.
4. Reject executable, macro-enabled, encrypted, malformed, or unsupported content before queuing.
5. Commit source, immutable revision, queued job, and audit together.

### Public web source

1. The create request persists source identity and a `SNAPSHOTTING` job; revision is initially null.
2. The Java job runner performs the fetch. The Python worker never receives a URL.
3. Allow only HTTP/HTTPS, reject user-info, and canonicalize host/port/path safely.
4. Resolve every hop, deny loopback/private/link-local/multicast/reserved/metadata ranges, pin the validated addresses for the connection, and revalidate every redirect.
5. Disable implicit proxy bypass, bound redirects/time/body, accept supported HTML/text only, and validate TLS against the original hostname.
6. Persist the final bytes and safe capture metadata before parsing.
7. Compare digest with the latest revision. Unchanged content completes the job with `UNCHANGED`, emits audit/telemetry, and creates no revision or chunks.

### Resynchronization and tombstone

- Inline/upload revisions use digest comparison. An unchanged request returns an audited no-op with no job.
- Web sync always creates a persisted job because the digest is unknown until capture.
- Changed content creates a new immutable revision; it never edits the old one.
- Tombstone blocks new revisions, syncs, and future index selection. Existing revisions remain readable by authorized historical workflows.
- Legal erasure is not ordinary tombstone behavior and is outside P2.1.

## 7. Parser and chunker boundary

Java sends the exact approved multipart request in `ai-worker-internal.v1.yaml` to `/internal/v1/documents/process`. The client verifies request identity, revision identity, digest, processing profile, response limits, response digest, ordinal uniqueness, offset validity, and all anchors before persistence.

Worker implementation rules:

- stateless and deterministic for a declared `processingProfile`;
- text/Markdown: UTF-8 validation, deterministic newline handling, line/heading anchors;
- HTML: parse only the captured snapshot, remove executable/non-content elements, preserve heading/paragraph lineage;
- PDF: text extraction with page anchors; no OCR claim;
- DOCX: bounded ZIP/XML inspection, reject macros/encryption/bombs, preserve heading/paragraph anchors;
- bounded parser concurrency, CPU/time, input, decompressed bytes, pages, elements, documents, chunks, and output;
- stable RFC 9457-style worker errors without raw content;
- no database/network/secret/control-plane callback access.

Algorithm identity is semantic and immutable, for example `apvero-text@1.0.0` and `apvero-boundary@1.0.0`. Any output-affecting behavior change requires a new version. Chunking uses deterministic boundary priority and deterministic overlap; offsets always refer to the normalized document text declared by the profile.

Parser libraries and exact limits are not selected by guesswork. The first implementation slice must build a versioned benign/adversarial corpus, benchmark candidate parsers, run dependency/security review, and record the chosen libraries and limits in the bilingual verification document before parser endpoints are enabled.

## 8. Persisted job protocol

The modular monolith uses a scheduled PostgreSQL poller. A claim transaction selects a small batch with `FOR UPDATE SKIP LOCKED`, writes a unique runner identity and lease expiry, increments the attempt where appropriate, then commits before file, worker, or network I/O.

Each step follows this protocol:

1. Load the job by full scope and verify lease ownership/version.
2. Read only persisted step inputs.
3. Perform bounded external work without an open database transaction.
4. Validate the complete result.
5. Commit idempotent outputs and next durable step in one short transaction.
6. Clear the lease and emit low-cardinality telemetry.

Lease expiry makes work claimable after a crash. The second runner must observe already-persisted outputs and no-op or compare them; it must not duplicate revisions, chunks, audit mutations, or future billable work.

Retry uses bounded exponential backoff with jitter and stable retryability by error category. Manual retry is accepted only for `FAILED` and retryable jobs. Cancellation is accepted only for `QUEUED` or `RETRY_WAIT`; active external work is not falsely reported as cancelled. Graceful shutdown stops claims, allows a bounded drain, and leaves the persisted lease to expire if interrupted.

P2.1 terminal `READY` means the immutable revision has a validated deterministic Document/Chunk set. It does not mean an index exists.

## 9. Security, errors, audit, and telemetry

Initial authorization maps the existing `read`, `write`, and `admin` API-key scopes to read, mutation, and operational actions. P2.1 must not redesign the P1 identity model. Retry, cancel, and tombstone require write or admin under the current policy; a later resource-policy redesign requires separate approval.

Stable public error families include:

- `APVERO_KNOWLEDGE_DISABLED`;
- scoped base/source/revision/job not found;
- source tombstoned or operation conflict;
- unsupported media, content too large, digest mismatch, encrypted/malformed/archive/page/time/resource limits;
- web URI/destination/redirect/content rejection and bounded fetch failure;
- worker unavailable or invalid response;
- job not retryable/cancellable, lease conflict, attempts exhausted;
- non-deterministic parser/chunker output.

Backend errors expose stable codes and safe structured fields; clients localize user messages. Validation annotations and exception mapping must not leak hard-coded user-facing prose.

Administrative audit covers base/source creation, revision accepted/no-op, web sync requested/completed, tombstone, manual retry, cancellation, and terminal failure. High-frequency step transitions remain typed job state and telemetry rather than audit-ledger spam.

Metrics cover queue wait, step duration, outcome, retries, input/output sizes, document/chunk counts, parser/chunker version, SSRF denials, worker latency, and failures. Tags are low-cardinality (`source_type`, `step`, `outcome`, `error_category`, algorithm version); content, URL, filename, tenant, workspace, source, and job identifiers are not metric labels. Scoped operational inspection comes from authorized database projections, not high-cardinality metrics.

Logs never contain raw source content, unrestricted URLs, multipart bytes, or secrets. The persisted job/revision/document/chunk records are the source of truth.

## 10. Deployment and configuration

P2.1 adds configuration beneath `apvero.knowledge` for enablement, worker base URI, claim batch/lease/polling/backoff, source limits, parser limits, web policy, and graceful drain. Environment names use the `APVERO_KNOWLEDGE_*` prefix.

Exact limits are accepted only after corpus measurement. Every limit must have a startup-visible effective value, a test at the boundary and just beyond it, and a documented resource rationale.

Deployment hardening required before the parser is implemented:

1. remove the worker host port from the default Compose profile;
2. remove the general `/worker/` Nginx proxy;
3. make only worker health reachable from the internal service network as needed;
4. place Platform Server and Worker on a dedicated internal network that is not shared with PostgreSQL or the Console, while Platform Server also joins the normal backend network;
5. run the worker unprivileged with read-only filesystem, bounded temporary storage and memory/CPU limits;
6. give the worker no database or provider credentials;
7. make the platform server depend on worker health only when Knowledge processing is enabled.

## 11. Implementation slices

Slices are merged in order but none is a releasable P2 feature claim.

Expected implementation locations are `modules/knowledge`, migration `V8__p2_1_durable_knowledge_ingestion.sql`, the existing `apps/ai-worker`, Platform Server configuration/exception mapping, and existing Compose/Nginx files. P2.1 must not create a generic `common`, `shared`, or `utils` package. The final filenames may become more specific inside these approved locations, but ownership may not move.

### P2.1a — Module and safety shell

- add Gradle module and server dependency;
- declare Spring Modulith boundary and ArchUnit rules;
- add fail-closed properties and health contribution;
- close public worker exposure;
- apply the approved contract correction before implementation;
- establish parser corpus/benchmark and dependency decision record.

### P2.1b — Scoped immutable persistence

- add the forward migration for the six P2.1 tables;
- add database checks, composite scope keys, indexes and immutability triggers;
- implement scoped repositories and mapping tests;
- verify clean migration and V7-to-new-head upgrade.

### P2.1c — Source command boundary

- implement base creation/listing;
- implement inline/upload snapshots, media detection, digest/no-op behavior and authorized content streaming;
- make source/revision/job/audit creation transactional;
- implement tombstone and cross-workspace failure behavior.

### P2.1d — Safe web capture

- implement pinned-DNS SSRF protection and redirect revalidation;
- implement bounded capture and safe metadata persistence;
- implement changed/unchanged synchronization behavior;
- test IPv4, IPv6, rebinding, redirect, metadata, proxy and timeout bypass cases.

### P2.1e — Worker processing

- implement and validate internal parser/chunker API 1.0 in Python and Java;
- implement all five source types and deterministic anchors;
- persist immutable documents/chunks idempotently;
- test malformed, encrypted, bomb, limit, timeout and non-determinism failures.

### P2.1f — Durable execution and operations

- implement lease claiming, step commits, expiry recovery, retry/backoff, cancellation and graceful shutdown;
- add job/source/revision read APIs and stable error mapping;
- add audit, metrics, structured logs and bilingual operational documentation;
- run Compose security, restart and end-to-end verification.

## 12. Verification matrix

Required evidence before P2.1 can be proposed for acceptance:

| Area | Minimum proof |
|---|---|
| Architecture | Spring Modulith and ArchUnit allowed/forbidden dependency tests |
| Migration | clean install, V7 upgrade, constraints, indexes and forward-only mitigation note |
| Isolation | every repository/API command and query tested across two tenants/workspaces |
| Immutability | update/delete triggers and repeat-processing equality/non-determinism tests |
| Jobs | crash after every step, lease expiry, duplicate claim, retry, exhaustion, cancel and restart |
| Sources | text, Markdown, PDF, DOCX and captured HTML happy/failure paths |
| Upload security | MIME spoof, executable, macro, encryption, malformed ZIP/XML, bomb and size limits |
| SSRF | loopback, RFC1918, link-local, IPv6, metadata, rebinding, redirect and proxy bypass |
| Worker contract | OpenAPI validation in Java and Python, bounded response and digest/ordinal/offset checks |
| API | OpenAPI conformance, auth scopes, stable errors, content streaming and no existence leakage |
| Operations | metrics cardinality, log redaction, audit atomicity, graceful shutdown and health |
| Deployment | no public parser route/host port, non-root/read-only worker, Compose health |
| Internationalization | matching English/Chinese docs and future client keys; no backend user-message dependency |
| End to end | create base -> ingest each source -> READY -> inspect lineage -> retry/resync/tombstone |

Applicable Java, Python, contract, migration, security, dependency, container and Compose checks must pass. TypeScript and Playwright are required only if a frontend file changes; P2.1 is not authorized to make the Knowledge page live.

## 13. Rollout and rollback

- The migration is additive and ignored by the P1 binary.
- `APVERO_KNOWLEDGE_ENABLED=false` remains the default; disabled endpoints fail with `APVERO_KNOWLEDGE_DISABLED` rather than fake success.
- Enabling is an explicit operator action on partial branches and does not change product page status.
- Disabling stops new claims and mutations after bounded drain; persisted work remains inspectable.
- Rollback uses the previous compatible binary and leaves additive tables/data intact.
- No automatic destructive cleanup and no down migration are provided.
- Because P2.1 cannot create a RAG Release, the ADR's later P2-compatible rollback floor is not activated yet.

## 14. Self-critique and rejected shortcuts

1. PostgreSQL `bytea` simplifies self-hosting but constrains file/corpus size. P2.1 must publish measured limits and never claim unlimited ingestion.
2. A stateless Python worker reduces control-plane risk but creates a two-language contract. Bidirectional contract tests are mandatory.
3. PostgreSQL leases are operationally simple but provide at-least-once execution. Idempotent outputs and crash tests are not optional.
4. Parsing PDF/DOCX reliably is security-sensitive and imperfect. P2.1 promises visible bounded extraction and lineage, not OCR or layout fidelity.
5. Web capture is more useful than upload-only ingestion but materially increases SSRF risk. A validator followed by an ordinary unpinned HTTP client is insufficient.
6. Reusing the current generic HTTP-scope authorization is pragmatic but not fine-grained. P2.1 must not pretend it has per-resource ABAC.
7. The current audit interface has limited structured details. P2.1 can record safe action/resource/outcome evidence; expanding its public shape requires a separately reviewed contract change.
8. Existing modules contain some direct workspace-table lookup patterns. Knowledge must follow the approved boundary and use `WorkspaceScopeCatalog`, not copy that debt.
9. Persisting only the latest job error limits attempt-by-attempt forensic history. P2.1 retains current step, attempt count, stable terminal evidence, audit mutations and telemetry; an append-only job event ledger should be added only if acceptance testing proves this is insufficient, with authority files reviewed first.
10. Making P2.1 APIs technically callable while the product remains disabled can confuse contributors. Contract status, feature flag and docs must consistently state that this is an internal milestone, not finished RAG.

## 15. Acceptance gate

P2.1 is ready for maintainer acceptance only when all six slices and the verification matrix pass and the following statement is true without qualification:

> From an authorized workspace, Apvero can capture a supported source safely, preserve an immutable revision, resume its persisted processing after failure, create deterministic traceable chunks exactly once, and expose honest inspection/retry/resync/tombstone behavior without adding infrastructure or making incomplete RAG claims.

Acceptance updates the P2.1 milestone evidence only. P2 remains `in-progress`, Knowledge remains disabled by default, and the next milestone is P2.2 immutable index and Retrieval Lab.
