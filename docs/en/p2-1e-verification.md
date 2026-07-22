# P2.1e Worker Processing Verification

Status: implementation candidate; maintainer acceptance and merge are still required.

## Delivered boundary

P2.1e implements the approved internal Worker API 1.0 for immutable Source Revision snapshots. The stateless Python Worker accepts bytes supplied by the Java control plane, verifies their SHA-256 identity, parses one of five approved media types, normalizes text, creates deterministic chunks and source anchors, and returns versioned output. It never fetches a URL, accesses PostgreSQL, authorizes a user, resolves a secret, or assigns domain identities.

The Java Knowledge module invokes the Worker outside a database transaction. It bounds the request time and response size, validates every returned identity, version, ordinal, digest, Unicode code-point offset, anchor, and warning code, and then persists the accepted output in a separate transaction.

P2.1e does not claim automatic ingestion. P2.1f remains responsible for leases, durable step transitions, retry scheduling, cancellation, restart recovery, and invoking this processing boundary from queued jobs.

## Deterministic processing

The implemented profile is `apvero-default@1.0.0`; parser and chunker algorithms publish explicit semantic versions. The same immutable bytes and profile produce the same normalized document digests, chunk text, offsets, anchors, and versions.

Supported media types:

- UTF-8 plain text;
- UTF-8 Markdown with heading and paragraph anchors;
- HTML with active/non-content elements removed before extraction;
- PDF with page anchors, encryption rejection, and page limits;
- DOCX with paragraph/heading anchors and archive inspection.

Chunk offsets use Unicode code points rather than UTF-16 units or encoded byte positions. Chunk boundaries are stable, overlap is explicit, and every chunk digest is independently verified by Java.

## Resource and document safety

- Input bytes, normalized output, document count, chunk count, title, anchor, and response sizes are bounded.
- PDF page count is bounded; encrypted or malformed PDFs fail with stable codes.
- DOCX entry count, expanded size, per-entry expansion ratio, encryption, required structure, and macro payloads are checked before parsing.
- The Worker performs cooperative deadline checks during processing. The Java caller also enforces a hard request deadline; an unresponsive call cannot hold a control-plane transaction because no transaction is open during network I/O.
- Worker problems contain stable codes and request identity but no raw document content, parser exception, file name, URL, or secret.

## Idempotent persistence

The completion transaction locks the workspace-scoped immutable Source Revision. Document and Chunk identifiers are derived deterministically from the revision and ordinals. A first accepted result inserts all Documents and Chunks atomically. Replaying an identical result is a no-op. Any changed parser output for an already-completed revision fails with `APVERO_KNOWLEDGE_NON_DETERMINISTIC_OUTPUT`; immutable rows are never overwritten.

Tenant and workspace predicates apply to the revision lock and every read/write. A cross-workspace completion observes the revision as not found. A failure after partial inserts rolls the entire transaction back.

## Configuration

| Environment variable | Default | Purpose |
|---|---:|---|
| `APVERO_KNOWLEDGE_WORKER_BASE_URI` | `http://ai-worker:8090` | Internal Worker origin without path, query, fragment, or credentials |
| `APVERO_KNOWLEDGE_WORKER_READ_TIMEOUT` | `15s` | Java end-to-end request deadline |
| `APVERO_KNOWLEDGE_MAX_WORKER_RESPONSE_BYTES` | `20971520` | Maximum accepted Worker response |
| `APVERO_KNOWLEDGE_MAX_SNAPSHOT_BYTES` | `5242880` | Maximum immutable source snapshot supplied to the Worker |

Knowledge remains fail-closed by default through `APVERO_KNOWLEDGE_ENABLED=false`.

## Verification evidence

The candidate is verified with:

- Python tests for all five media types, repeated deterministic output, Unicode offsets, source anchors, digest mismatch, malformed/encrypted PDF, DOCX expansion limits, invalid profile, and timeout classification;
- Java HTTP tests for multipart identity, response validation, stable Worker problem mapping, and bounded response reads;
- PostgreSQL integration tests for first completion, identical replay, changed-output rejection, workspace isolation, and full rollback after a mid-transaction constraint failure;
- OpenAPI validation for the implemented internal contract;
- full Java module, architecture, migration, frontend, Worker, Compose, and container checks before acceptance.

## Rollback

Disable Knowledge with `APVERO_KNOWLEDGE_ENABLED=false`. Reverting the P2.1e application commit removes Worker processing and completion components without a database rollback because this slice adds no migration. Existing immutable revisions, documents, and chunks remain valid and inert. The Worker endpoint is internal-only and can be removed from the Knowledge Compose profile independently while Knowledge is disabled.
