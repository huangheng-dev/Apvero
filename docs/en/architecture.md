# Apvero architecture

## Product boundary

Apvero is an open-source, self-hosted **AI Application Engineering Platform**. The root object is an `AI Application`; chat, RAG, structured output, tool use, agents, and workflows are runtime modes. This prevents the product from collapsing into an agent builder or a collection of unrelated administration screens.

The closed loop is:

```text
Design -> Test -> Evaluate -> Release -> Run -> Observe -> Feedback -> Improve
   ^                                                                    |
   +--------------------------------------------------------------------+
```

The dependency-ordered implementation plan and exit gates are defined in the [delivery roadmap](roadmap.md). Architecture describes the complete target; the roadmap controls when each capability may become live.

## Complete capability tree

```text
Apvero
├─ Experience plane
│  ├─ Web Console (English source locale, Simplified Chinese required)
│  ├─ Public REST API / OpenAPI 3.1
│  ├─ SDKs and CLI [planned]
│  └─ Playground [baseline]; retrieval/evaluation inspectors [planned]
│
├─ Identity and tenancy [baseline]
│  ├─ Tenant -> Workspace -> Environment
│  ├─ Human identity: OIDC, OAuth2, LDAP/SCIM adapters
│  ├─ Machine identity: one-time API keys [baseline]; workload identities [planned]
│  ├─ Coarse API roles [baseline]; resource/action policy decisions [planned]
│  └─ Tenant scope in SQL, vector filters, object paths, events and telemetry
│
├─ AI Application lifecycle (control plane)
│  ├─ Application Center [implemented baseline]
│  │  ├─ runtime mode and workspace ownership [implemented]
│  │  ├─ basic lifecycle metadata [baseline]
│  │  └─ versioned drafts and typed input/output contracts [planned]
│  ├─ Build [baseline]
│  │  ├─ Prompts: templates, declared variables and immutable versions [baseline]; diff/rollback [planned]
│  │  ├─ Model Route: versioned model binding and timeout [baseline]; weights/fallback [planned]
│  │  ├─ Knowledge binding: source/index/retrieval policy versions
│  │  ├─ Capability binding: tools, MCP, memory, evaluators, guardrails
│  │  └─ Policy and runtime parameter versions
│  ├─ Test [preview baseline]
│  │  ├─ deterministic preview and opt-in external-provider preview [baseline]
│  │  ├─ tool/MCP call inspector
│  │  ├─ retrieved chunk and score inspector
│  │  └─ captured test case promotion into datasets
│  ├─ Evaluate [planned]
│  │  ├─ versioned datasets, cases and expected behavior
│  │  ├─ deterministic, model-graded and human-review evaluators
│  │  ├─ regression comparison, A/B experiment and threshold gates
│  │  └─ signed evaluation report referenced by a release
│  ├─ Release [implemented baseline]
│  │  ├─ immutable ReleaseBundle
│  │  ├─ canonical JSON + SHA-256 artifact identity
│  │  ├─ manifest contract can pin model route, prompt, schema, knowledge, capability,
│  │  │  policy, memory, evaluation and runtime parameter versions
│  │  └─ environment promotion and rollback by pointer [planned]
│  └─ Application API identity
│
├─ Runtime data plane
│  ├─ AI Gateway [planned]
│  │  ├─ authenticate, authorize, rate limit, quota and budget
│  │  ├─ validate input, mask sensitive data and apply guardrails
│  │  ├─ semantic/exact cache behind policy
│  │  ├─ model routing, circuit breaking, fallback and retry budget
│  │  └─ streaming, usage normalization and idempotency
│  ├─ Runtime orchestrator [execution baseline implemented]
│  │  ├─ Chat, RAG, Structured, Tool, Agentic and Workflow modes [contract only]
│  │  ├─ Spring AI as the sole core Java abstraction
│  │  └─ provider-neutral RuntimeProvider SPI
│  ├─ Capability execution [planned]
│  │  ├─ typed input/output JSON Schemas
│  │  ├─ method-level permissions and deny-by-default policy
│  │  ├─ timeouts, quotas, idempotency and audit events
│  │  └─ isolated tool, MCP and plugin runners
│  └─ Run ledger [implemented baseline]
│     ├─ application and immutable release identity
│     ├─ input/output, provider, usage, cost and latency
│     └─ trace identity and status
│
├─ Knowledge data plane [planned; stateless worker utilities implemented]
│  ├─ deterministic chunking and exact-match evaluation utilities [implemented]
│  ├─ sources: files, web, Git, database and enterprise adapters [planned]
│  ├─ ingestion job: parse -> OCR -> normalize -> chunk -> enrich -> embed -> index
│  ├─ immutable index versions and tenant-scoped pgvector retrieval
│  ├─ incremental synchronization, deletion propagation and lineage
│  └─ retrieval evaluation and grounded citation checks
│
├─ Observe, govern and improve [planned beyond current run ledger]
│  ├─ OpenTelemetry trace: gateway -> runtime -> model/tool/retrieval [planned]
│  ├─ token, cost, latency, cache, quality and policy metrics
│  ├─ budgets by tenant/workspace/application/release/capability
│  ├─ append-only audit events and configurable retention/masking
│  ├─ explicit user feedback and reviewed trace curation
│  └─ feedback -> dataset version -> candidate evaluation -> release gate
│
└─ Platform and extension plane
   ├─ PostgreSQL 18 + pgvector is the only mandatory stateful dependency for the default self-hosted deployment
   ├─ Redis, MinIO, Kafka and ClickHouse are optional adapters
   ├─ transactional outbox and idempotent background jobs [planned]
   ├─ secret references; plaintext is never returned or persisted
   ├─ signed plugin manifest, compatibility and permissions
   └─ plugins execute out-of-process; no arbitrary JAR execution in control plane
```

## Source modules and dependency direction

| Module | Owns | Allowed dependencies | Status |
|---|---|---|---|
| `application` | application root, runtime mode, basic lifecycle metadata | none | Baseline |
| `release` | immutable release artifact and digest | `application`, `capability-registry` | Baseline |
| `runtime` | run ledger, trace identity and provider SPI | `application`, `release`, `capability-registry` | Baseline |
| `identity` | tenants, workspaces, principals, coarse API roles and hashed credentials | none | Baseline |
| `capability-registry` | providers, models, routes and prompts; other capability metadata later | `identity`, `governance` | Baseline |
| `knowledge` | sources, ingestion, chunks and index versions | `capability-registry` | Worker baseline |
| `evaluation` | datasets, evaluation runs, experiments and gates | `application`, `release`, `runtime` | Planned |
| `governance` | environment-backed secret references; budget, audit and retention later | `identity` | Baseline |
| `extension` | plugin compatibility, permissions and signatures | `capability-registry` | Contract only |

No module may query another module's tables. Public interfaces and versioned events are the only collaboration mechanisms. The system starts as a modular monolith; a module becomes a deployable only after an ADR proves an independent scale, isolation, runtime or failure boundary.

## Current deployables

```text
Browser
  -> Console / Nginx :3000
       -> Platform Server :8080
            -> PostgreSQL 18 + pgvector :5432
       -> AI Worker :8090
```

- **Platform Server:** Java 25, Spring Boot 4.1, Spring Modulith 2.1, Spring AI 2.0, jOOQ and Flyway.
- **Console:** Node.js 24, React 19.2, strict TypeScript, Vite 8, TanStack Query and i18next.
- **AI Worker:** Python 3.14, FastAPI and Pydantic. It is stateless and owns no source-of-truth business data.
- **Default data layer:** PostgreSQL 18 with pgvector. Optional infrastructure must not become a hidden requirement.

## Release and run truth

A production run references one immutable release ID. The release manifest is canonicalized, hashed and stored with the SHA-256 digest. Database composite foreign keys prove tenant/workspace/application consistency; a trigger rejects release update and delete operations. Rollback means moving an environment pointer to an older release, never editing a release.

The current run table is the typed operational source of truth for release identity, trace identity, I/O, token usage, cost, latency, provider adapter ID and status. Configurable retention and masking are planned governance capabilities, not current behavior.

## Security boundary of the current executable baseline

Development mode supplies a local administrator and remains loopback-only. Enforced mode authenticates a bootstrap administrator token or a one-time-issued, hashed API credential and rejects cross-workspace use. Fine-grained resource policy, OIDC/LDAP/SCIM and workload identity remain planned. External provider calls and private model endpoints are independent opt-ins; secret values are resolved from the environment and are never returned or persisted.

## Technology policy

Spring AI is the sole core Java AI abstraction. LangChain4j, Spring AI Alibaba and vendor SDKs may only exist in isolated compatibility adapters after an approved ADR. Provider types, names and options cannot enter application, release or runtime domain contracts.
