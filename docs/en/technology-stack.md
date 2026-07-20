# Technology stack

The baseline is intentionally narrow. A library is adopted only when it owns a clear responsibility.

| Area | Adopted technology | Responsibility |
|---|---|---|
| Java runtime | Java 25 LTS | Platform server runtime and language baseline |
| Application framework | Spring Boot 4.1 | Configuration, HTTP, validation, health and production packaging |
| Module boundaries | Spring Modulith 2.1 | Discoverable modules, allowed dependency verification and module tests |
| AI abstraction | Spring AI 2.0 | The sole core Java AI abstraction |
| Resilience | Resilience4j, planned provider-adapter stage | Timeouts, circuit breakers and bounded retries for external providers |
| Persistence | jOOQ | Explicit SQL and strongly controlled workspace filters |
| Migrations | Flyway | Forward, reviewable database schema evolution |
| Database | PostgreSQL 18 | Transactions and all default self-hosted source-of-truth state |
| Vector search | pgvector | Tenant-scoped vector retrieval without a second mandatory database |
| Observability | Micrometer via Actuator (baseline); OpenTelemetry (planned) | Current health/metrics foundation and future normalized distributed traces |
| Architecture tests | Spring Modulith + ArchUnit | Dependency boundaries and forbidden provider imports |
| Console runtime | Node.js 24 | Frontend toolchain |
| Console UI | React 19.2 | Component model |
| Build | Vite 8 + TypeScript 5.9 strict | Fast build and compile-time contract safety |
| Server state | TanStack Query 5 | API cache, pending/error state and invalidation |
| Validation | Zod 4 | Client-side contract validation as forms expand |
| Internationalization | i18next + react-i18next | English source locale and required Simplified Chinese coverage |
| UI styling | Repository-native CSS | Small, inspectable console without a heavy component lock-in |
| Worker runtime | Python 3.14 | Document and evaluation ecosystem |
| Worker API | FastAPI + Pydantic 2 | Typed, stateless worker contracts |
| Worker environment | uv | Reproducible Python dependency and environment management |
| Front proxy | Nginx | Static console, API proxy and baseline response headers |
| Packaging | Docker + Compose v2 | Reproducible default self-hosted deployment |
| CI | GitHub Actions | Backend, console, worker, contract and Compose configuration checks |
| License | Apache-2.0 | Permissive open-source distribution with patent grant |

## Optional adapters, not default dependencies

- Redis or Valkey: distributed rate limit, ephemeral coordination and cache adapter.
- MinIO or S3: large document and retained artifact objects.
- Kafka: high-volume integration events after an outbox boundary is justified.
- ClickHouse: long-retention analytical trace aggregation.
- External vector stores: only for proven scale or enterprise integration needs.

## Explicitly not adopted in core

- LangChain4j and Spring AI Alibaba: compatibility adapters only after an approved ADR.
- Direct OpenAI, Anthropic or other vendor SDKs: adapter implementation details only.
- Kubernetes, service mesh and many independent databases: no baseline complexity without evidence.
- In-process uploaded JAR plugins: violates the isolation and permission model.
