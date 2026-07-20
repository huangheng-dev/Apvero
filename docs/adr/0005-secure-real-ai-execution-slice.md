# ADR 0005: Secure real AI execution slice / 安全的真实 AI 执行闭环

- Status / 状态: Accepted / 已接受
- Date / 日期: 2026-07-20
- Decision owner / 决策人: Maintainer

## Context / 背景

Apvero currently has a real Application → immutable ReleaseBundle → deterministic local Run baseline. Models, Prompts, Secrets, Playground and Usage are product-complete prototypes, but their server data is not implemented. Connecting a real provider is not a page-level CRUD change: it introduces credential handling, provider adapters, versioned configuration, new public APIs, new tables, cross-module validation and runtime-path behavior.

Apvero 当前已经具备真实的 Application → 不可变 ReleaseBundle → 本地确定性 Run 基线。Models、Prompts、Secrets、Playground 与 Usage 已有完整产品原型，但尚未接入服务端数据。连接真实模型并不是普通页面 CRUD：它会引入凭证处理、Provider 适配器、版本化配置、新公开 API、新数据表、跨模块校验以及运行路径变化。

Implementing these concerns without an explicit decision would violate the existing rules for module ownership, provider adapters, public contracts, migrations, release reproducibility and deny-by-default security.

如果没有明确架构决策就直接实现，将违反当前关于模块所有权、Provider 适配器、公开契约、数据库迁移、发布可复现性和默认拒绝安全策略的规定。

## Decision / 决策

Implement one narrow vertical slice in the existing modular monolith. Do not create a new deployable or mandatory stateful dependency.

在现有模块化单体中实现一条窄而完整的纵向链路，不创建新部署单元，也不增加强制状态型依赖。

### 1. Module ownership / 模块所有权

- `identity` becomes baseline and owns organizations, workspaces, principals, roles and hashed workload/API credentials.
- `governance` becomes baseline for secret references. The first implementation stores references to environment variables; it never persists provider secret values.
- `capability-registry` becomes baseline and owns provider-neutral model providers, models, model routes, route targets, Prompt assets and immutable Prompt versions.
- `application` keeps the root AI Application and stores selected draft dependency references as opaque identifiers.
- `release` validates public references and pins exact model-route and Prompt versions in an immutable bundle.
- `runtime` resolves only pinned release dependencies, invokes the provider-neutral runtime port and records the run ledger, trace identity, usage, cost and failure details.

- `identity` 升级为基线模块，负责组织、工作区、主体、角色以及经过哈希处理的工作负载/API 凭证。
- `governance` 升级为 Secret Reference 基线。首版只保存环境变量引用，绝不持久化 Provider Secret 明文。
- `capability-registry` 升级为基线模块，负责厂商无关的模型服务商、模型、模型路由、路由目标、Prompt 资产和不可变 Prompt 版本。
- `application` 继续作为 AI Application 根实体，并以不透明 ID 保存草稿依赖选择。
- `release` 通过公开接口验证引用，并把准确的模型路由版本与 Prompt 版本固定到不可变发布包。
- `runtime` 只解析发布包固定的依赖，调用厂商无关 Runtime Port，并记录运行账本、Trace、用量、成本和失败信息。

`capability-registry` may depend on `identity` and `governance`. `release` may depend on `application` and `capability-registry`. `runtime` may depend on `application`, `release` and `capability-registry`. No module reads another module's tables.

`capability-registry` 可以依赖 `identity` 与 `governance`；`release` 可以依赖 `application` 与 `capability-registry`；`runtime` 可以依赖 `application`、`release` 与 `capability-registry`。任何模块都不得读取其他模块的数据表。

### 2. Authentication and authorization / 认证与授权

- Spring Security is the security boundary for platform APIs.
- Local self-hosting starts with a maintainer-supplied bootstrap administrator secret. There is no production default password.
- Issued API keys are shown once; only a strong verifier, prefix, scope, expiry and audit metadata remain.
- Browser credentials remain in memory or session storage, not local storage.
- Every new endpoint requires an explicit resource/action permission and workspace scope. Missing permission is denied and audited.
- OAuth2/OIDC and LDAP remain adapters for a later approved slice; the local credential flow remains usable without them.

- 平台 API 使用 Spring Security 建立安全边界。
- 本地自托管由维护者提供 Bootstrap 管理员 Secret；生产环境没有默认密码。
- 签发的 API Key 只显示一次；数据库只保留强校验值、前缀、权限范围、过期时间和审计元数据。
- 浏览器凭证只保存在内存或 Session Storage，不进入 Local Storage。
- 每个新端点必须声明资源/动作权限并受工作区约束；缺少权限时默认拒绝并记录审计事件。
- OAuth2/OIDC 与 LDAP 留作后续适配器；没有它们时，本地凭证流程仍可使用。

### 3. Secret handling / Secret 处理

- A Secret Reference contains tenant/workspace scope, kind, locator, status, rotation metadata and timestamps—never the secret value.
- The first resolver supports `ENVIRONMENT` only. For example, a reference can point to `OPENAI_API_KEY`.
- Secret values are resolved only during an authorized runtime call, are never returned by read APIs, and are masked from logs, traces and errors.
- Database-backed encrypted secret material, Vault and cloud secret managers require later adapters and do not block the PostgreSQL-only baseline.

- Secret Reference 只包含租户/工作区范围、类型、定位符、状态、轮换元数据和时间戳，绝不包含 Secret 值。
- 首个解析器只支持 `ENVIRONMENT`，例如引用 `OPENAI_API_KEY`。
- Secret 值只在获授权的运行调用中解析，不会由读取 API 返回，也会从日志、Trace 和错误中屏蔽。
- 数据库加密 Secret、Vault 和云 Secret Manager 留给后续适配器，不阻碍只依赖 PostgreSQL 的默认基线。

### 4. Provider abstraction / Provider 抽象

- `RuntimeProvider` remains the provider-neutral domain SPI.
- Spring AI is the only adapter framework in the runtime path.
- The first real adapter implements an OpenAI-compatible chat transport so OpenAI, compatible hosted providers and explicitly configured local endpoints can share one isolated adapter without leaking vendor types.
- Provider-specific options stay inside adapter configuration. Core APIs expose declared capabilities and normalized parameters only.
- `deterministic-local` remains enabled for tests, offline development and guaranteed quick start. It is visibly identified and is never presented as an external model.

- `RuntimeProvider` 继续作为厂商无关的领域 SPI。
- 运行路径只允许 Spring AI 作为适配框架。
- 首个真实适配器实现 OpenAI-compatible Chat 传输，使 OpenAI、兼容服务商以及明确配置的本地端点共用一个隔离适配器，同时不泄漏厂商类型。
- Provider 特有参数只能存在于适配器配置中；核心 API 只暴露声明式能力与标准化参数。
- `deterministic-local` 继续服务于测试、离线开发和可靠 Quick Start，并始终明确标识，不能伪装成外部模型。

### 5. Prompt and release semantics / Prompt 与发布语义

- A Prompt asset is mutable metadata; every Prompt version is immutable after creation.
- Variables use an explicit declared schema. Missing variables fail before provider invocation.
- Applications select draft model-route and Prompt-version references.
- Production execution still requires an immutable `ReleaseBundle` containing exact version identifiers and the canonical artifact digest.
- Playground draft tests create an immutable `PREVIEW` execution bundle with bounded retention. They do not mutate or impersonate a production release, but every recorded run still has a reproducible bundle identity.

- Prompt 资产的元数据可以修改，但每个 Prompt Version 创建后不可变。
- 变量必须使用显式声明的 Schema；缺少变量时在调用 Provider 前失败。
- Application 草稿选择模型路由与 Prompt Version 引用。
- 生产执行仍必须使用包含精确版本标识与规范制品摘要的不可变 `ReleaseBundle`。
- Playground 草稿测试创建有留存上限的不可变 `PREVIEW` 执行包。它不会修改或伪装生产发布，但每条已记录 Run 仍拥有可复现的 Bundle 身份。

### 6. Public workflow / 公开工作流

The implemented workflow is:

```text
Authenticate
  -> register environment Secret Reference
  -> register Provider and Model
  -> publish Model Route version
  -> create Prompt and immutable Prompt version
  -> bind Application draft
  -> execute Playground preview bundle
  -> inspect Run and Trace
  -> create production ReleaseBundle
  -> execute released Application
  -> inspect normalized Usage & Costs
```

All write APIs are idempotent where retries are expected. Streaming uses SSE. Non-streaming execution remains available for automation and tests.

所有可能重试的写入 API 必须具备幂等语义。流式响应使用 SSE，同时保留供自动化和测试使用的非流式执行接口。

## Alternatives / 备选方案

1. **Store provider keys directly in the Models table.** Rejected because it violates secret separation, rotation and least-privilege rules.
2. **Implement Models and Prompts only as CRUD.** Rejected because it creates inventory without closing an executable workflow.
3. **Call provider SDKs directly from controllers.** Rejected because it leaks vendor concerns, bypasses release identity and prevents normalized telemetry.
4. **Add LangChain4j beside Spring AI.** Rejected because it duplicates the adopted abstraction and violates ADR 0003.
5. **Require Redis, Kafka, Vault or a vector database now.** Rejected because none is necessary for this synchronous slice and the PostgreSQL-only invariant must remain true.
6. **Skip authentication until later.** Rejected because provider configuration, secrets and cost-bearing execution cannot be safely exposed without a deny-by-default boundary.
7. **Build the entire target platform in one change.** Rejected because it would weaken reviewability, rollback and failure isolation.

## Compatibility / 兼容性

- Existing Application, Release and Run endpoints remain backward compatible during `0.x`.
- Existing deterministic local releases and seeded runs remain readable and executable.
- New request fields are optional until a draft is bound; production release creation fails with a stable error code when required pinned dependencies are absent.
- Provider-specific SDK types never enter OpenAPI or JSON Schema.
- English remains canonical; Simplified Chinese ships in the same change.

## Migration / 数据迁移

- Add forward-only Flyway migrations for identity, API credentials, secret references, providers, models, routes, Prompt assets/versions, application draft bindings, preview bundle purpose and normalized run failure/usage metadata.
- Every table includes tenant/workspace scope where applicable, foreign keys, unique constraints, optimistic versioning and timestamps.
- Existing release and run rows receive explicit compatible defaults.
- Rollback is operational rather than destructive: disable the real-provider feature flag and keep new tables/columns intact. A down migration is not required because it could destroy user configuration.

## Security / 安全

- Threat model: credential disclosure, cross-tenant access, SSRF through Base URL, unrestricted local-network access, prompt injection into configuration, cost abuse and sensitive-data leakage.
- Base URLs are normalized and validated. Private/link-local destinations are denied by default; explicit local-provider allowlisting is an administrator decision.
- Timeouts, maximum output tokens, request-size limits, rate limits and per-route budgets are mandatory runtime controls.
- Secrets, authorization decisions and configuration changes emit typed audit events.
- Prompt/input/output retention is configurable and masked before diagnostics export.
- Error responses use stable codes and never include provider credentials or raw authorization material.

## Operability / 可运维性

- Add health indicators for route readiness without making billable probe calls by default.
- Record Micrometer metrics for request count, success/failure, latency, normalized token usage, estimated cost and route selection.
- Correlate release, run, route and trace identifiers in structured logs without logging secrets or unmasked content.
- Provider failure does not corrupt release or run state. A failed run is persisted with a normalized failure category.
- Real-provider support is feature-gated and the deterministic provider guarantees offline operation.

## Open-source impact / 开源影响

- The default repository remains runnable without a paid model account.
- Contributors implement new providers through one documented Spring AI adapter boundary.
- The environment-reference secret model is simple to audit and works with Docker, Kubernetes Secrets and external secret injection.
- No commercial service is required for the core workflow.

## Verification / 验证

- Spring Modulith and ArchUnit dependency tests.
- Unit tests for key hashing, scope enforcement, Prompt rendering, route selection, Base URL policy, secret masking and release pin validation.
- PostgreSQL integration and Flyway migration tests, including tenant-isolation failure paths.
- OpenAPI 3.1 and release-schema compatibility checks.
- Adapter contract tests using a local HTTP stub; optional real-provider smoke tests are opt-in and never run with repository secrets.
- Console strict typecheck, unit tests, bilingual coverage and critical lifecycle tests.
- Compose build and health checks with no provider key and with an injected test endpoint.

## Rollback / 回滚

- Disable `APVERO_REAL_PROVIDER_ENABLED` to return execution to `deterministic-local`.
- Revert Console live pages to their labeled demo fixtures without deleting stored configuration.
- Retain additive tables and columns so rollback does not destroy user data.
- Existing Application, Release and Run APIs continue serving the original baseline.

## Approval requested / 请求确认

Approval authorizes the following protected changes only:

1. activate the `identity`, `governance` and `capability-registry` modules inside the modular monolith;
2. extend allowed dependencies exactly as described above;
3. adopt Spring Security and the Spring AI OpenAI-compatible adapter within the existing technology baseline;
4. add additive Flyway migrations and OpenAPI endpoints for the workflow above;
5. add immutable `PREVIEW` bundle purpose without weakening production `ReleaseBundle` rules.

Any new deployable, stateful dependency, provider framework, plaintext-secret storage, cross-module table access or weakening of tenant isolation requires a separate ADR.
