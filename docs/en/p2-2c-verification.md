# P2.2c Governed Embedding Execution — Verification Evidence

Status: completed and maintainer-approved on 2026-07-25

Target: P2 / P2.2c

Baseline: [P2.2c governed Embedding execution](p2-2c-embedding-execution-baseline.md)

## 1. Accepted scope

P2.2c now provides the internal governed Embedding batch seam required by P2.2d:

- provider-neutral quote, replay-policy and execution contracts;
- deterministic Spring AI Embedding with frozen golden vectors;
- an opt-in OpenAI-compatible adapter with no hidden retries;
- durable Governance admission, dispatch, settlement and reconciliation state;
- deterministic Knowledge batch planning, atomic Entry persistence and crash recovery decisions.

It does not activate a scheduled Build runner, Build API, index publication, Retrieval Lab, frontend
page or production Knowledge claim. No migration, new deployable, stateful dependency or additional
AI abstraction was introduced.

## 2. Accepted checkpoints

| Checkpoint | Durable implementation | Pull request |
|---|---|---|
| P2.2c-1 — deterministic adapter and quote/replay API | `0257574` | [#16](https://github.com/huangheng-dev/Apvero/pull/16) |
| P2.2c-2 — live Governance component lifecycle | `5e7eeff` | [#17](https://github.com/huangheng-dev/Apvero/pull/17) |
| P2.2c-3 — OpenAI-compatible adapter and protocol stub | `3953ea6` | [#18](https://github.com/huangheng-dev/Apvero/pull/18) |
| P2.2c-4 — Knowledge batch primitive and crash matrix | `f7c0ac8` | [#19](https://github.com/huangheng-dev/Apvero/pull/19) |

## 3. Verification-gate evidence

| Gate | Evidence | Result |
|---|---|---|
| Architecture and provider neutrality | `ModularArchitectureTest`, repository architecture tests, and the absence of provider SDK types from public packages | Pass |
| Golden vectors | `DeterministicEmbeddingModelTest` covers the full 256-float vectors, ordering, locale and timezone variants | Pass |
| Estimation and limits | `ConservativeUtf8EmbeddingInputUnitEstimatorTest`, `EmbeddingCostQuoteCalculatorTest`, and `KnowledgeEmbeddingBatchExecutorTest` | Pass |
| Real-adapter protocol | `OpenAiCompatibleEmbeddingAdapterTest` proves request mapping, ordered output mapping, timeout/rejection normalization and zero hidden retries | Pass |
| Fail-closed configuration | Route shape, exact route reference, readiness, endpoint, Secret and profile checks are covered by capability and platform integration tests | Pass |
| Tenant isolation | Governance component and Knowledge persistence integration tests deny cross-workspace lookup, lock, Chunk and Entry access | Pass |
| Pre-dispatch denial | `ExecutionGovernanceCompatibilityTest` and component persistence integration tests prove denial before billable invocation | Pass |
| Crash matrix | `KnowledgeEmbeddingRecoveryDeciderTest` covers all eight approved recovery rows | Pass |
| Idempotency and conflicts | Governance compatibility tests plus Knowledge executor, writer and concurrent PostgreSQL integration tests cover equal retries, partial batches and conflicts | Pass |
| P1 compatibility | The complete P1 integration and budget suite remains green in the cumulative build | Pass |
| Build, contracts and deployment | Java, `bootJar`, OpenAPI, Compose configuration, both container builds and Knowledge Compose health/restart jobs pass | Pass |
| Internationalization | This document and its Simplified Chinese peer have matching sections; console validation reports 405 keys in each required locale | Pass |

## 4. Reproducible command evidence

Local verification on 2026-07-25:

- `./gradlew test bootJar --no-daemon`: 44 suites, 129 tests, no failures, errors or skips;
- `pnpm typecheck`, `pnpm test`, `pnpm i18n:check`, `pnpm build`: pass; 5 tests and 405
  leaf keys per required locale;
- `uv run pytest -q`: 19 passed;
- `uv run ruff check src tests benchmarks`: pass;
- `uv run pip-audit`: no known third-party dependency vulnerabilities; the unpublished local
  `apvero-ai-worker` package is correctly excluded from the PyPI lookup;
- both Compose configuration modes: pass.

The cumulative [PR #19 CI run](https://github.com/huangheng-dev/Apvero/actions/runs/30140915816)
passed all seven jobs: `backend`, `console`, `worker`, `contracts`, `compose-config`, `containers`
and `knowledge-compose`. It validates the exact accepted tree `47da0fed8fe1402535ced7be80bc2cd702f9474c`
at head `f7c0ac8db40ab3600c5feaced7c2b9cf837e15ae`.

On the local Windows host, Redocly 2.13 validated both OpenAPI descriptions and then hit a libuv
process-cleanup assertion. The same pinned command completed successfully in the Linux `contracts`
CI job. This is recorded as a host-tool exit defect, not hidden as a contract failure and not used
to change the approved contract or technology baseline.

## 5. Security, operability and rollback

- provider keys remain Secret references; normal APIs never return plaintext;
- real Embedding remains opt-in and deterministic local execution remains the default proof path;
- dispatch ambiguity never becomes an automatic unsafe replay;
- complete Entry batches commit atomically and equal replays do not create a second artifact;
- stable `APVERO_*` errors avoid provider body, text, vector, URL, Secret and cross-scope leakage;
- PostgreSQL remains the only mandatory stateful dependency;
- rollback deploys the prior binary, stops and boundedly drains new calls, and retains V9/V10
  evidence without rewriting terminal components or immutable entries.

## 6. Honest completion boundary

P2.2c is complete as an internal execution primitive, not as an end-user Knowledge workflow.
P2.2d must still implement leased Build claiming, durable step transitions, retry/cancel commands
and atomic immutable index publication. P2.2e must still implement exact Retrieval Lab behavior.
Knowledge therefore remains disabled by default and P2.2 remains `in-progress`.
