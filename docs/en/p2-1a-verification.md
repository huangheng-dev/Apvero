# P2.1a Module and Safety Shell Verification

Status: Locally verified implementation checkpoint
Date: 2026-07-22
Stage: P2 / milestone P2.1a

## Delivered boundary

- Added the physical `modules/knowledge` Gradle module and Platform Server dependency.
- Declared the Spring Modulith module ID and the approved `identity`, `capability-registry`, and `governance` dependencies.
- Added ArchUnit checks for provider-neutral core code, approved Knowledge dependencies, and private Knowledge internals.
- Added `APVERO_KNOWLEDGE_ENABLED=false` as the default, a public fail-closed availability gate, stable `APVERO_KNOWLEDGE_DISABLED`, and a Worker-aware Actuator health contributor.
- Kept all Knowledge REST operations contract-only; no P2 migration, repository, business endpoint, live product page, index, or RAG claim was added.
- Removed the Worker host port and general Nginx proxy, put it behind the `knowledge` Compose profile on an internal-only network, and applied non-root/read-only/capability/resource restrictions.
- Established the versioned parser candidate corpus, deterministic digest baseline, executable benchmark, English/Chinese dependency decision, and CI lint coverage.
- Updated FastAPI, Starlette, pytest, and the test client to patched compatible versions. Added a pinned `pip-audit` CI gate.

## Verification evidence

| Area | Command/evidence | Result |
|---|---|---|
| Java | `gradle test --no-daemon` | Passed |
| Knowledge boundary | Modulith + ArchUnit + Knowledge unit/deployment tests | Passed |
| Packaging | `gradle :apps:platform-server:bootJar --no-daemon` | Passed |
| Worker | `uv run pytest -q` | 9 passed |
| Python lint | `uv run ruff check src tests benchmarks` | Passed |
| Dependency lock | `uv lock --check` | Passed |
| Dependency security | `uv run pip-audit` | No known vulnerabilities; local unpublished package skipped as expected |
| Parser smoke benchmark | 25 iterations for all five declared media types | Stable output digests |
| Contracts | JSON parse + Redocly lint for both OpenAPI files | Valid; two pre-existing health/info 4xx warnings |
| Compose | Default and `knowledge` profile config validation | Passed |
| Exposure | Rendered Compose + Java deployment policy tests | Default Worker absent; no host port; no Console dependency/proxy |
| Local runtime migration | Stopped legacy `apvero-ai-worker-1`; verified port 8090 closed | Passed; Console, Platform Server, and PostgreSQL remained healthy |
| Container images | Compose build for Worker and Platform Server | Passed |
| Worker container | Independent read-only/no-network/no-port runtime probe | Health passed as UID 10002 with all capabilities dropped |
| Platform container | Independent read-only/no-port runtime probe against PostgreSQL | Health passed as UID 10001; Knowledge reported `enabled=false` |

Direct Docker Hub authentication timed out. The missing official Docker Hub base images were populated through Google Artifact Registry's documented, synchronized `mirror.gcr.io` cache and tagged locally with their original names; the repository Dockerfiles and image references were not changed. Both original Dockerfiles then built successfully. CI also has an explicit two-image build job so the remote checkpoint re-verifies the canonical build independently.

## Security interpretation

When Knowledge is disabled, no Worker request occurs and the Platform remains healthy with explicit `enabled=false` detail. When Knowledge is enabled, Worker unavailability makes the Knowledge health contributor `DOWN`; commands must also call the public availability gate before work. Worker health is externally observable only through the Platform Actuator aggregation allowed by ADR-0006, never through a direct Worker browser route.

Existing checkouts that still run the old published `8090` container must stop or recreate that exact service. The local legacy Worker was stopped, not deleted; it is recoverable by explicitly starting the rebuilt `knowledge` profile.

## Not delivered

P2.1a does not deliver the P2.1b migration and scoped repositories, P2.1c source commands, P2.1d web capture, P2.1e production parser endpoint, or P2.1f durable runner. Knowledge stays non-live and P2 stays `in-progress`.

## Rollback

Revert the feature branch and run the previous Platform binary. There is no database migration or persisted P2.1 state. Keep the old Worker stopped if it publishes a host port; rollback must not reintroduce an unnecessary public processing surface.
