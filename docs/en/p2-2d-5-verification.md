# P2.2d-5 Operations and Final Verification

Status: implementation checkpoint candidate; maintainer acceptance required

## Identity and evidence boundary

- Source baseline: `ba488407c0a01406a204cd8b1d067ac399867fb0`
- Verified implementation commit: `485dd4926d557e4dd0f26251bf8dd7f09486a85a`
- Local platform image:
  `sha256:c7f2614961989aa88ab29a618f72aa92611bb3df6451fde9df3606e68444f41f`
- Local AI worker image:
  `sha256:84b4ce3bb3710db35fbe1e2cc0d5ca0abe55c9dad31f72c59ba8a468d289c440`

The image identities prove the local Compose run only. The implementation identity pins the tested
code and acceptance assets; the follow-up evidence commit changes documentation only. GitHub CI
must still confirm the complete pushed candidate before this becomes release evidence.

## Delivered verification surface

P2.2d-5 adds no production domain behavior. It adds:

- explicit fail-closed Build runner variables to base Compose;
- deterministic two-tenant/two-workspace Knowledge, Route and Index fixtures;
- a standard-library acceptance driver that requests Builds only through accepted REST APIs;
- disabled-runner, automatic READY, replay, isolation, telemetry and crash-recovery assertions;
- reference scheduler tests for 100 Builds across 20 workspaces at concurrency 1, 4 and 8;
- PostgreSQL validation and atomic publication tests at 1, 100 and 1,000 Entries;
- a metric-cardinality regression at 100 repeated Build observations;
- the same acceptance flow in the `knowledge-compose` GitHub Actions job.

Indexes and Embedding Routes still have no accepted creation API in this stage. The acceptance
fixture therefore loads only those deterministic prerequisites and ingestion evidence through
SQL. It never inserts a Build, READY Version, vector Entry or publication audit event. Builds are
requested through the public API and the real runner creates all runtime and publication state.

## Local Compose environment

- Host: Windows, PowerShell, Docker Desktop
- Isolated Compose project: `apvero-d5-verification-local`
- PostgreSQL image: `pgvector/pgvector:pg18`
- Published ports: platform `127.0.0.1:18080`, PostgreSQL `127.0.0.1:15432`
- Build runner: concurrency `1`, claim batch `1`, lease `6s`, external timeout `2s`,
  commit margin `1s`, poll `200ms`, retry backoff `200ms..2s`, drain `2s`
- Provider: deterministic local Embedding adapter, 256 dimensions, no paid key

The isolated containers, networks and volume were removed after evidence capture. The images were
retained as local build cache and are not published artifacts.

## Compose results

| Proof | Result |
| --- | --- |
| Runner disabled before fixture Build requests | PASS; both Builds remained `QUEUED`, attempt `0` |
| Disabled health contract | PASS; `UP`, `runnerEnabled=false`, lifecycle and scan `disabled` |
| Two-workspace automatic execution | PASS; both Builds reached immutable `READY` |
| Equal request replay | PASS; returned the same Build and created no duplicate Version |
| Publication persistence | PASS; primary Version/Entry/audit counts were `1:1:1` |
| Cross-workspace read and cancel | PASS; both failed closed with `404` |
| Health detail vocabulary | PASS; all required bounded fields were present |
| Metric names and tag keys | PASS; exercised families were present with bounded tags; unit tests enumerate all required families |
| Metric redaction | PASS; no tenant, workspace, Build, endpoint, content or lease owner appeared |
| Platform stop during in-flight work | PASS |
| Expired-lease recovery after restart | PASS; Build reached `READY` |
| Recovery attempt semantics | PASS; resumed the same durable attempt with `attemptCount=1` |
| Recovery persistence | PASS; Index/Version/new Entry counts were `2:2:1` |

The recovered Build was `8f80f853-9ed3-495c-9373-648cfcb429a9`; its immutable Version was
`53cd27d1-3a58-5efd-9fd4-407413140435`. These are fixture-run identities, not metric tags or
product defaults.

## Reference envelope observations

These are local observations, not universal latency objectives.

### Scheduler fairness

| Eligible Builds | Workspaces | Concurrency | Observed completion |
| ---: | ---: | ---: | ---: |
| 100 | 20 | 1 | 1,870 ms |
| 100 | 20 | 4 | 370 ms |
| 100 | 20 | 8 | 181 ms |

Every workspace received a claim, local capacity was not exceeded, and the tests completed under
their explicit 30-second timeout.

### PostgreSQL validation and publication

| Entries | Queue through embedding/indexing | Validation | Atomic publication |
| ---: | ---: | ---: | ---: |
| 1 | 3,133 ms | 809 ms | 495 ms |
| 100 | 3,586 ms | 549 ms | 192 ms |
| 1,000 | 72,248 ms | 2,324 ms | 717 ms |

The 1,000-Entry case retained one atomic Version and completed under its 120-second timeout. The
embedding phase dominates this local test because it persists 256-dimensional vectors in bounded
batches. The result supports the approved reference envelope but does not establish a production
SLA or a scale claim beyond 1,000 Entries.

## Failure and recovery matrix

Automated unit, PostgreSQL and Compose assertions cover:

- both feature gates, unavailable dispatcher and overlapping poll prevention;
- bounded admission, rejected execution and graceful drain;
- empty workspace scans, rotating fairness and failed operational scans;
- active-lease exclusion, expiry reclaim, renewal and stale-owner fencing;
- crash boundaries around Entry persistence, Governance settlement, Build progress, validation,
  publication and audit;
- retryable, permanent, validation, security, internal and ambiguous normalized failures;
- partial, extra, missing, wrong-lineage, wrong-dimension and wrong-digest artifacts;
- equal replay, concurrent publishers, independent Index publishers and audit rollback;
- automatic `EMBEDDING -> INDEXING -> VALIDATING -> READY`;
- disabled Compose execution, process restart recovery and cross-workspace denial.

## Cumulative gates

The final local run uses:

```text
gradlew clean check bootJar
pnpm typecheck
pnpm test
pnpm i18n:check
pnpm build
uv run pytest -q
uv run ruff check src tests benchmarks
uv run pip-audit
python -m ruff check deploy/compose/verify_index_build.py
git grep -n -I -E '<credential-signature-set>'
docker scout cves <tested-image> --only-severity critical,high
npx --yes @redocly/cli@2.13.0 lint \
  contracts/openapi/platform-api.yaml contracts/openapi/ai-worker-internal.v1.yaml
docker compose --profile knowledge \
  -f deploy/compose/compose.yaml \
  -f deploy/compose/compose.knowledge.yaml config --quiet
git diff --check
```

Recorded local results:

- Java modules: 133 tests passed with no failure or skip; `bootJar` passed;
- platform cumulative run: 88 tests executed, 84 passed and four P2.2d-1 tests could not start
  because PostgreSQL initialization exceeded the Testcontainers log-wait window;
- isolated pre-warmed PostgreSQL rerun: 26 tests passed, including all four P2.2d-1 tests, all 15
  P2.2d-3 tests at the final source state, and seven modular-architecture tests;
- Console: 5 tests passed; strict typecheck, 405-key locale parity and production build passed;
- AI worker: 19 tests passed; Ruff passed; `pip-audit` found no known third-party vulnerability;
- acceptance driver: Ruff, bytecode compilation and live Compose assertions passed;
- contracts: both OpenAPI files valid with two inherited health/info 4xx-response warnings;
- Compose configuration, credential-signature and whitespace checks passed;
- AI worker image scan completed and reported Debian `perl 5.40.1-6` with
  `CVE-2026-12087` (CRITICAL), `CVE-2026-48959` (HIGH) and `CVE-2026-48962` (HIGH);
  Docker Scout reported no fixed version;
- platform image scan did not complete because the Trivy Java database download from GHCR ended
  with `unexpected EOF`; this is an unresolved verification item, not a pass.

GitHub CI remains the authoritative clean-run confirmation after the candidate is committed.

## Security, compatibility and rollback

- No secret, provider key, source content, vector or raw provider payload is added to evidence.
- The fixture credential is the existing non-production bootstrap token used only by the isolated
  development security mode.
- No REST/OpenAPI/JSON Schema, Flyway migration, module, deployable, dependency, locale key,
  release semantic or production default changed.
- Knowledge keeps only its approved public dependencies on Identity, Capability Registry and
  Governance.
- Base Compose and the Knowledge overlay keep the Build runner disabled unless explicitly enabled.

Rollback is configuration-first: set
`APVERO_KNOWLEDGE_INDEX_BUILD_RUNNER_ENABLED=false` and recreate the platform server. Existing
Builds and immutable Versions remain durable. The verification scripts, fixtures and CI step can
then be reverted without a data rollback because this slice adds no migration.

## Limitations and unresolved risks

- The deterministic adapter proves orchestration, not remote-provider latency, quotas or outage
  behavior.
- PostgreSQL polling and O(entries) validation remain the accepted P2.2d design; larger corpora
  require new measurements before support is claimed.
- CI timing varies on shared runners and is evidence, not an SLA.
- The Compose fixture uses SQL for contract-only Index and Route prerequisites until accepted
  creation APIs exist.
- The worker base-image findings require a maintainer-approved remediation or explicit security
  disposition; this slice does not silently change the approved base-image baseline.
- The platform image scan must be retried when the external vulnerability database is reachable.
- The clean Linux Compose run remains a GitHub CI responsibility.
- Windows Docker contention prevented one uninterrupted all-platform invocation from staying
  green; the partitioned rerun closed every failed test, but GitHub CI must still prove one clean
  Linux cumulative run.

## Stage-status proposal

After the candidate commit is recorded, the image-scan findings are dispositioned, and all GitHub
checks pass, the maintainer may accept P2.2d-5 and P2.2d as complete. This document does not change
`architecture/delivery-stages.yaml`; P2.2d remains in progress until that explicit acceptance.
Starting P2.2e before acceptance remains prohibited.
