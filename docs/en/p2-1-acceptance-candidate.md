# P2.1 Acceptance Candidate

Status: verification hardening implemented; remote CI and maintainer acceptance are still required.

Stage state is intentionally unchanged: P2 and P2.1 remain `in-progress`, Knowledge remains
disabled by default, and every P2 REST operation remains `contract-only`.

## Closed audit gaps

| Acceptance area | Reproducible evidence |
|---|---|
| Crash and restart | PostgreSQL integration covers a new runner reclaiming the same job after crashes at `SNAPSHOTTING`, `PARSING`, and `CHUNKING`; Compose restarts Platform Server while a retry is persisted |
| Automatic retry | A transient failure persists `RETRY_WAIT` and bounded backoff, then a new runner identity claims it |
| Five-source end to end | Enabled Compose processes text, Markdown, PDF, DOCX, and captured public HTML through the real Worker to `READY` |
| Source operations | Compose inspects revisions/content, proves unchanged web resync, tombstones a source, and verifies post-tombstone mutation rejection |
| Worker contract | Python loads the committed OpenAPI 3.1 file and validates runtime multipart fields plus success/problem responses |
| Platform contract | Java reflection compares every implemented P2.1 controller method/path with the committed contract-only OpenAPI subset |
| Isolation | Cross-workspace tests cover Base listing, inline/upload creation, revision listing/creation/upload, web sync, content, tombstone, Job reads and Job commands |
| Operations | A Logback capture test proves raw exception content, URL credentials, and document text do not enter runner logs |
| Deployment | The Knowledge Compose overlay makes Platform Server depend on Worker health; CI starts the stack with `--wait`, checks recovery, prints evidence, and removes volumes |

## Required acceptance evidence

Before changing `architecture/delivery-stages.yaml`, all jobs in the candidate pull request and
the resulting `main` run must be green, including the new `knowledge-compose` job. The maintainer
then reviews this evidence and explicitly approves the P2.1 transition.

Local isolated Compose verification completed successfully without touching the maintainer's
default Apvero stack or volume. It processed text, Markdown, PDF, DOCX, and public HTML through
the real Platform Server and Worker to `READY`. A separate recovery run stopped the Worker,
observed attempt 1 in persisted `RETRY_WAIT`, restarted Platform Server, restored the Worker, and
observed the same job complete automatically on attempt 2. This local result is supporting
evidence; it does not replace the required candidate-PR and post-merge `main` CI evidence.

The real integration run also found and closed two defects that unit-only verification had not
exposed: Java Worker calls now force HTTP/1.1 for Uvicorn compatibility, and persisted media
types use canonical contract values while preserving the declared content type in capture
metadata.

## Rollback

This hardening adds tests, CI, an opt-in Compose overlay, and documentation. It adds no migration,
domain state, public operation, or new deployable. Rollback removes these files and checks; the
default Compose profile and persisted V8 data remain unchanged.
