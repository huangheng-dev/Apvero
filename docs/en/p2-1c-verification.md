# P2.1c Source Command Verification

Status: Locally verified implementation checkpoint
Date: 2026-07-22
Stage: P2 / milestone P2.1c

## Delivered boundary

P2.1c closes the first durable Knowledge authoring workflow on top of the P2.1b schema:

`create Base -> add inline or uploaded Source -> persist immutable Revision -> queue ingestion Job -> inspect revisions/content -> add changed Revision or record no-op -> tombstone Source`

The public Java boundary contains command, catalog, receipt, and read-model types. The internal service owns orchestration and uses only the approved Identity workspace-scope API and Governance audit API. The REST adapter implements the approved P2.1 Base, Source, Revision, upload, content, and tombstone routes without changing their contract-only publication status.

Knowledge remains disabled by default. Enabling it activates this source-command boundary only; parsing, chunking, embedding, indexing, retrieval, Application binding, Release pinning, and grounded Run remain unavailable.

## Accepted content and safety envelope

- Inline input accepts `TEXT` and `MARKDOWN`, counts Unicode code points, stores exact UTF-8 bytes, and enforces configurable character and snapshot-byte limits.
- Upload input is classified from captured bytes instead of trusting filename or client media type.
- PDF capture requires a valid header near the beginning and an EOF marker, and rejects encrypted input.
- DOCX capture validates the minimum OpenXML structure, rejects malformed or path-unsafe archives, duplicate entries, macros and embedded objects, and enforces entry and expanded-byte limits.
- Executable signatures, invalid UTF-8 text, unsupported media, empty bodies, and oversized snapshots fail closed with stable error codes.
- Original filenames are reduced to a safe leaf name and bounded without splitting Unicode code points.
- Every accepted snapshot receives a canonical SHA-256 digest. Identical content for the same Source produces an `UNCHANGED` receipt and creates neither a Revision nor a Job.

The default self-hosted limits are configurable through `APVERO_KNOWLEDGE_MAX_INLINE_CHARACTERS`, `APVERO_KNOWLEDGE_MAX_SNAPSHOT_BYTES`, `APVERO_KNOWLEDGE_MAX_DOCX_ENTRIES`, and `APVERO_KNOWLEDGE_MAX_DOCX_EXPANDED_BYTES`.

## Isolation, authorization, audit, and transaction behavior

- Every catalog operation resolves an authorized `WorkspaceScope`; every repository query repeats both tenant and workspace predicates.
- An identifier owned by another workspace is indistinguishable from a missing identifier.
- Existing platform authorization protects read and management routes. The HTTP test proves a read-only API key cannot create a Base and can stream an authorized immutable snapshot.
- Source tombstoning is idempotent and blocks future revisions while retaining immutable history.
- Base creation, Source/Revision mutation, Job creation, and their audit events share one Spring transaction. A forced audit insert failure proves the business mutation rolls back.
- Backend failures expose stable codes for client-side English or Simplified Chinese localization; no provider or parser implementation type enters the public Knowledge API.

## Verification evidence

The checkpoint includes unit tests for exact capture, digesting, Unicode limits, media sniffing, executable rejection, strict UTF-8, PDF safety, DOCX structure, active-content rejection, and bounded input. PostgreSQL Testcontainers integration tests verify:

- the complete inline Source and changed/no-op Revision workflow;
- upload classification from bytes and stable Source type;
- cross-workspace fail-closed behavior;
- business/audit atomic rollback;
- HTTP authorization and immutable content streaming;
- idempotent tombstoning and post-tombstone mutation rejection.

The following repository-wide checks passed locally:

- complete Gradle `test` and Platform Server `bootJar`, including Spring Modulith, ArchUnit, P1 governance, and all P2.1 checkpoints;
- console strict typecheck, five Vitest tests, required-locale key coverage, and production build;
- Worker nine-test suite, Ruff, and `pip-audit` with no known third-party vulnerabilities;
- JSON contract parsing and Redocly validation of both OpenAPI descriptions (two pre-existing health-route warnings remain);
- default and `knowledge`-profile Compose configuration;
- clean Platform Server container build from the repository Dockerfile;
- diff whitespace and credential-signature scans.

## Rollback and mitigation

P2.1c adds no database migration and does not change an approved invariant or public contract. Operational rollback uses the previous P2.1b-compatible binary and keeps `APVERO_KNOWLEDGE_ENABLED=false`. Rows already written through P2.1c remain valid under V8 and are retained for diagnosis or a later forward recovery; rollback must not delete Source Revisions, Jobs, or Audit events.

If source capture needs immediate containment without a binary rollback, disable Knowledge and restart the platform. Existing non-Knowledge P1 workflows remain available.

## Not delivered

P2.1c does not implement web capture (P2.1d), worker parsing/chunking (P2.1e), durable job execution and operations (P2.1f), embedding, indexing, retrieval, Application binding, Release, or grounded Run. The WEB Source route returns a stable not-available error rather than claiming success. Product prototype pages remain explicitly demo-only. P2 and P2.1 remain `in-progress`, and P2 REST contracts remain `contract-only` until the full stage exit gates pass.
