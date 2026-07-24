# P2.1d Safe Web Capture Verification

Status: merged implementation checkpoint; included in the accepted P2.1 milestone.

## Delivered boundary

P2.1d adds the approved public-web Source path without introducing another deployable, framework, queue, or stateful dependency. A web Source stores its canonical locator as protected write-only metadata and creates a persisted `SNAPSHOTTING` ingestion job with no revision. A resynchronization request creates another persisted snapshot job.

The Java Knowledge module owns fetching. The Python Worker never receives or resolves a URL.
P2.1f owns leasing, retries, cancellation, restart recovery, and automatic invocation of this
handler.

## Network safety

- Only canonical HTTP and HTTPS URIs without user information or fragments are accepted.
- Internationalized host names are converted to their ASCII IDN form.
- Every request and redirect performs a new DNS resolution.
- Every returned address must be public; mixed public/private answers fail closed.
- Loopback, private, carrier-grade NAT, link-local, multicast, documentation, benchmarking, metadata, IPv6 unique-local, transition, and reserved destinations are denied.
- The socket connects directly to the validated `InetAddress`; no proxy selector or second DNS lookup is involved.
- HTTPS retains the canonical host for certificate endpoint identification and SNI while connecting to the pinned address.
- HTTPS-to-HTTP redirect downgrade is denied.
- Redirect count, headers, body, connect time, and read time are bounded.
- Compression is not requested and non-identity content encoding is rejected.
- Only bounded HTML, plain text, and Markdown responses are accepted.

## Persistence and synchronization

A changed snapshot is stored as an immutable Source Revision with its SHA-256 digest and safe capture metadata. The Source pointer advances atomically and the ingestion job moves to `QUEUED/PARSING` for P2.1e.

If the digest matches the latest immutable revision, no duplicate revision is created. The synchronization job completes as `READY/COMPLETE` with outcome `UNCHANGED` and references the retained revision.

Raw URLs and response content are excluded from metrics and audit metadata. The low-cardinality capture metric records only `captured` or `ssrf_denied` outcome tags.

## Configuration

| Environment variable | Default | Purpose |
|---|---:|---|
| `APVERO_KNOWLEDGE_WEB_MAX_REDIRECTS` | `5` | Maximum validated redirect hops |
| `APVERO_KNOWLEDGE_WEB_MAX_HEADER_BYTES` | `65536` | Maximum response-header bytes |
| `APVERO_KNOWLEDGE_WEB_CONNECT_TIMEOUT` | `2s` | Direct socket connection timeout |
| `APVERO_KNOWLEDGE_WEB_READ_TIMEOUT` | `5s` | TLS handshake and response read timeout |

The existing `APVERO_KNOWLEDGE_MAX_SNAPSHOT_BYTES` remains the response-body limit.

## Verification evidence

- Java compilation for Knowledge and Platform Server.
- Unit coverage for canonicalization, IPv4, IPv6, metadata endpoints, DNS rebinding, redirect revalidation, HTTPS downgrade, pinned address use, response size, compression, and timeouts.
- PostgreSQL integration coverage for web Source creation, protected canonical locator persistence, snapshot-job creation, safe capture metadata, changed revision creation, and unchanged no-op synchronization.
- HTTP coverage confirms a write-only web locator is not returned in the response.

Recorded local verification on 2026-07-23:

- `gradlew test`: passed, including Spring Modulith/ArchUnit and PostgreSQL Testcontainers coverage;
- Console strict typecheck, Vitest, required-locale coverage, and production build: passed;
- Worker pytest, Ruff, and dependency audit: passed (the local project package is intentionally not published on PyPI);
- JSON parsing and Redocly OpenAPI validation: passed with two pre-existing 4XX recommendation warnings;
- default and Knowledge-profile Compose validation: passed;
- Platform Server container build: passed;
- diff whitespace and changed-file credential-signature checks: passed.

## Rollback

Disable Knowledge with `APVERO_KNOWLEDGE_ENABLED=false` to fail closed. Reverting the P2.1d application commit removes the web command and capture handler without a database rollback because P2.1d adds no migration. Existing web Source and job rows remain valid under the already-approved V8 schema and are inert while Knowledge is disabled.
