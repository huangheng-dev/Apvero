# ADR 0006: P2 grounded Knowledge/RAG implementation baseline / P2 可信 Knowledge/RAG 实施基线

- Status / 状态: Accepted / 已接受
- Date / 日期: 2026-07-22
- Decision owner / 决策人: Maintainer
- Target stage / 目标阶段: P2
- Replaces / 替代: None / 无
- Approval record / 批准记录: Maintainer approved ADR-0006 on 2026-07-22 / 维护者于 2026-07-22 批准 ADR-0006

## Decision summary / 决策摘要

P2 will deliver one closed, reproducible workflow inside the existing modular monolith:

P2 将在现有模块化单体内交付一条完整且可复现的闭环：

```text
Source snapshot
  -> persisted ingestion job
  -> parse with lineage
  -> deterministic chunk
  -> governed embedding
  -> atomic immutable index version
  -> workspace-scoped retrieval inspection
  -> Application draft binding
  -> immutable ReleaseBundle 1.1
  -> grounded Run
  -> validated citations and retained retrieval evidence
```

The default deployment remains PostgreSQL 18 with pgvector as the only mandatory stateful dependency. The existing Java control plane remains the source of truth. The existing Python AI worker remains stateless and performs bounded parsing/chunking only. P2 adds no queue, object store, vector database, cache, or deployable.

默认部署继续只强制依赖带 pgvector 的 PostgreSQL 18。现有 Java 控制平面继续作为唯一事实来源；现有 Python AI Worker 保持无状态，只执行有边界的解析和切块。P2 不新增队列、对象存储、向量数据库、缓存或部署单元。

This ADR authorizes only the protected architecture and contract changes listed in the approved scope. P2.0 establishes authority and contract baselines; it does not add migrations, business implementation, or make a prototype page live.

本 ADR 只授权“已批准范围”中列出的受保护架构与契约变化。P2.0 只建立权威文件和契约基线，不增加迁移、业务实现，也不把原型页面标记为真实功能。

## Context and current gaps / 背景与当前缺口

P1 closed secure model execution, but the present repository cannot honestly claim grounded RAG:

P1 已经完成安全模型执行闭环，但当前仓库还不能真实宣称具备可信 RAG：

1. `knowledge` is declared as planned but has no physical Java module or public API.
2. The worker implements deterministic text chunking only; it has no document lineage, parser contract, persisted execution state, or index publication protocol.
3. The Knowledge page is a labeled product prototype rather than live server data.
4. Runtime resolves model and Prompt pins only; it does not retrieve evidence or validate citations.
5. Release schema 1.0 and current generated manifests are inconsistent: live code emits shorthand references and incomplete runtime parameters that the published JSON Schema does not fully describe.
6. Seeded legacy releases contain placeholder `knowledge@1.0.0` references. They are not proof of a real index and must never silently activate RAG.
7. P1 governance reservations assume one Application/model-route pair. P2 can create multiple billable components: ingestion embeddings, query embeddings, and chat generation.
8. Nginx currently exposes the worker proxy broadly. Future parser endpoints must not become an unauthenticated public document-processing surface.
9. No policy defines source revision immutability, resynchronization, tombstones, old-release behavior, or verifiable citation identity.

These are architecture, contract, release, security, and governance changes. They require an ADR before implementation under `AGENTS.md`.

这些问题涉及架构边界、公开契约、发布语义、安全与治理。按照 `AGENTS.md`，必须先由 ADR 批准，才能进入实现。

## Product boundary / 产品边界

### Root ownership / 根对象归属

`AI Application` remains the root product entity. Knowledge is a versioned Application dependency, not a competing root application type. Agent and Workflow remain later runtime modes.

`AI Application` 继续是产品根实体。Knowledge 是 Application 的版本化依赖，不是与其竞争的另一种应用根对象；Agent 与 Workflow 仍属于后续运行模式。

```text
AI Application
├─ mutable Draft
│  ├─ ModelRoute reference
│  ├─ PromptVersion reference
│  ├─ KnowledgeIndexVersion binding (P2)
│  └─ RetrievalPolicyVersion binding (P2)
├─ Preview Bundle (immutable)
├─ Production ReleaseBundle (immutable)
│  └─ exact Knowledge and retrieval-policy pins
└─ Run
   ├─ model evidence
   ├─ retrieval evidence
   └─ validated citations
```

### P2 live product surface / P2 真实产品界面

P2 closes these user tasks, not a list of disconnected CRUD screens:

P2 要完成的是以下用户任务，而不是一组互不相连的 CRUD 页面：

1. Create a Knowledge Base in a workspace.
2. Add an uploaded file, Markdown text, or public web source.
3. Inspect immutable source revisions and ingestion progress.
4. Inspect documents, chunks, anchors, failures, and retry history.
5. Build and atomically publish an immutable index version.
6. Run retrieval tests showing scores and source lineage.
7. Bind an exact index and retrieval policy to an Application draft.
8. Preview, release, and run that Application.
9. Inspect the answer, citations, retrieved chunks, policy decisions, Token usage, and cost.
10. Resynchronize or tombstone a source without mutating old releases.

Until every item above passes the universal P2 gate, partial pages remain visibly `demo`, `planned`, or feature-disabled. No intermediate slice may display a fake server-confirmed success.

在上述闭环全部通过 P2 通用门禁前，局部页面必须继续明确标注为 `demo`、`planned` 或功能禁用，任何中间切片都不能伪造服务端成功状态。

## Module architecture / 模块架构

### Module ownership / 模块所有权

| Module | P2 ownership | Does not own |
|---|---|---|
| `identity` | Organization/workspace identity and workspace authorization | Knowledge content or vectors |
| `capability-registry` | Provider-neutral embedding model/route metadata and embedding SPI adapter resolution | Knowledge jobs, chunks, or indices |
| `knowledge` | Bases, sources, revisions, documents, chunks, retrieval policies, index builds, immutable index versions, retrieval service | Application drafts, releases, runs, or cost ledgers |
| `application` | Opaque draft bindings to exact Knowledge/index/policy identifiers | Knowledge validation or vector access |
| `release` | Validates public Knowledge references and pins them in an immutable manifest | Retrieval execution |
| `runtime` | Orchestrates pinned retrieval and generation; owns Run retrieval evidence and citations | Knowledge tables or provider SDK types |
| `governance` | Admission, reservation, settlement, limits, audit, retention/masking policy | Vector search and answer composition |

No module reads another module's tables. Cross-module calls use public Java interfaces and provider-neutral records only.

任何模块都不得读取其他模块的数据表。跨模块同步调用只能使用公开 Java 接口和厂商无关的数据结构。

On approval, the planned `knowledge-source` ownership currently listed under `capability-registry` moves to `knowledge`; it must not remain ambiguously owned by two modules. `capability-registry` owns embedding capability inventory, not Knowledge sources.

本 ADR 获批后，当前列在 `capability-registry` 下的规划项 `knowledge-source` 必须迁移到 `knowledge`，不能让两个模块产生模糊的重复所有权。`capability-registry` 负责 Embedding 能力清单，不负责 Knowledge Source。

### Proposed allowed dependencies / 拟议允许依赖

```text
knowledge            -> identity, capability-registry, governance
application          -> none (stores opaque draft references)
release              -> application, capability-registry, knowledge
runtime              -> application, release, capability-registry, knowledge
capability-registry  -> identity, governance
```

The exact diff to `architecture/dependency-rules.yaml` and `architecture/modules.yaml` is allowed only after approval. No reverse dependency from Knowledge to Application, Release, or Runtime is permitted.

只有本 ADR 获批后，才能按上述范围修改 `architecture/dependency-rules.yaml` 与 `architecture/modules.yaml`。Knowledge 不得反向依赖 Application、Release 或 Runtime。

Runtime continues to reach governance through the existing provider-neutral execution-governance facade owned by `capability-registry`; P2 does not add a direct Runtime → Governance dependency. This preserves the accepted P1 boundary while allowing the facade contract to carry multiple reservation components.

Runtime 继续通过 `capability-registry` 已有的厂商无关 Execution Governance Facade 使用治理能力；P2 不新增 Runtime → Governance 直接依赖。这样既保持 P1 已批准边界，也允许该 Facade 契约承载多个费用预留组件。

## Domain model / 领域模型

```text
Workspace
└─ KnowledgeBase
   ├─ KnowledgeSource
   │  └─ SourceRevision (immutable source snapshot)
   │     └─ Document
   │        └─ Chunk (immutable content + lineage)
   ├─ RetrievalPolicy
   │  └─ RetrievalPolicyVersion (immutable)
   └─ KnowledgeIndex
      ├─ IndexBuild (mutable execution state)
      │  ├─ pinned SourceRevision set
      │  └─ IndexEntry (chunk + embedding)
      └─ IndexVersion (immutable published identity)
```

### Required invariants / 必须保持的不变量

1. A Source Revision is a byte-for-byte or text-for-text immutable snapshot identified by SHA-256.
2. A Chunk belongs to exactly one Source Revision and records deterministic parser/chunker versions plus stable source anchors.
3. An Index Build is mutable execution state; an Index Version is immutable published state.
4. Retrieval is allowed only against a `READY` published Index Version, never a partial build.
5. Every retrieval query scopes workspace and index version inside the same database query before ranking and limiting.
6. A ReleaseBundle pins exact Index Version and Retrieval Policy Version identifiers; it never references `latest`.
7. Resynchronization creates a new Source Revision and a new Index Version. It cannot modify an old Index Version.
8. Tombstoning a source excludes it from future builds but preserves old release reproducibility.
9. Every returned citation maps to a retrieved Chunk and its immutable Source Revision.
10. An unknown or fabricated citation marker cannot be persisted as a valid citation.

## Persistence baseline / 持久化基线

All P2 state uses additive Flyway migrations after the current migration head. Names below are logical candidates; final DDL is reviewed during implementation.

P2 全部状态通过当前迁移头之后的增量 Flyway 迁移加入。以下是逻辑表名，最终 DDL 在实现阶段再次审查。

| Owning module | Logical table | Purpose |
|---|---|---|
| Knowledge | `knowledge_base` | Workspace-scoped base metadata |
| Knowledge | `knowledge_source` | Source identity, type, sync and tombstone status |
| Knowledge | `knowledge_source_revision` | Immutable content snapshot, digest and parser input metadata |
| Knowledge | `knowledge_document` | Parser output unit and document-level lineage |
| Knowledge | `knowledge_chunk` | Immutable normalized chunk content and anchors |
| Knowledge | `retrieval_policy_version` | Immutable retrieval parameters |
| Knowledge | `knowledge_index` | Stable index identity |
| Knowledge | `knowledge_index_build` | Persisted build state, lease, retry and failure metadata |
| Knowledge | `knowledge_index_build_revision` | Exact source-revision set used by a build |
| Knowledge | `knowledge_index_entry` | Build-scoped chunk vector and embedding metadata |
| Knowledge | `knowledge_index_version` | Immutable published version pointing to a validated build |
| Application | `application_draft_knowledge_binding` | Exact draft binding without cross-table reads |
| Runtime | `ai_run_retrieval` | Query digest/retention metadata and retrieval outcome |
| Runtime | `ai_run_retrieval_hit` | Ranked Run evidence and citation mapping |
| Governance | reservation-component extension | Estimated and actual cost by billable component |

Tenant and workspace identifiers are repeated where required for composite foreign keys and fail-closed repository queries. UUID identity, optimistic versions, timestamps, uniqueness constraints, and check constraints are mandatory. Published index versions and their entries are protected against update/delete by repository design and database constraints or triggers.

为建立组合外键和默认拒绝的查询，必要表必须重复保存 tenant/workspace 标识。UUID、乐观锁版本、时间戳、唯一约束与检查约束都是必需项。已发布索引版本及其条目必须同时受到仓储设计和数据库约束或触发器保护，禁止更新或删除。

### Source object storage / 源文件存储

The self-hosted baseline stores bounded source snapshots in PostgreSQL (`bytea`/text, using TOAST where applicable). Local disk is never a source of truth. An `ObjectStore` port may be introduced later for S3-compatible storage, but it is optional and cannot make MinIO mandatory without another ADR.

默认自托管版本把受大小限制的源快照存入 PostgreSQL（`bytea`/text，适用时使用 TOAST），本地磁盘绝不作为事实来源。后续可以增加可选的 S3-compatible `ObjectStore` 端口，但未经新 ADR，不得把 MinIO 变成强制依赖。

This is an intentional baseline trade-off: it favors a one-command, PostgreSQL-only deployment over very large document archives. P2 must document and test a supported file-size and corpus envelope rather than claim unlimited scale.

这是有意的基线取舍：优先保证只依赖 PostgreSQL 的一键部署，而不是宣称支持超大文档归档。P2 必须记录并测试受支持的文件大小与语料规模边界，不能宣称无限扩展。

## Source scope and ingestion / 数据源范围与摄取

### Live P2 source types / P2 首批真实数据源

- UTF-8 plain text and Markdown;
- PDF with text extraction and page anchors;
- DOCX with paragraph/heading anchors;
- public HTTP/HTTPS HTML with a captured immutable snapshot.

XLSX, PPTX, image OCR, Git, Notion, Confluence, Jira, database connectors, and authenticated crawling remain `contract-only` or `planned`. They must not appear as live merely because the UI contains a selector.

XLSX、PPTX、图片 OCR、Git、Notion、Confluence、Jira、数据库连接器和需要认证的抓取保持 `contract-only` 或 `planned`。不能因为 UI 存在选项就把它们标记为真实可用。

Exact file/body/page/archive-expansion/time limits will be configuration-backed, documented, and covered by tests. Initial defaults are selected during implementation from measured parser behavior, not guessed in this ADR.

文件大小、响应体、页数、压缩展开量和处理时间的准确上限必须可配置、有文档并由测试覆盖。初始默认值要在实现时根据解析器实测确定，而不是在本 ADR 中拍脑袋。

### Source resynchronization / 数据源重新同步

1. Fetch or upload produces a candidate snapshot.
2. SHA-256 is compared with the latest revision.
3. An unchanged snapshot produces an audited no-op.
4. A changed snapshot creates a new immutable revision and ingestion job.
5. A new index build may select it; old indices and releases remain unchanged.
6. Source tombstone removes it from new builds only.

Permanent legal erasure that intentionally breaks historical reproducibility is outside ordinary delete behavior and requires a separate retention/governance decision with explicit impact reporting.

为了法律合规而执行、并会主动破坏历史可复现性的永久清除，不属于普通删除行为；它需要独立的留存/治理决策和明确影响报告。

## Persisted job and publication protocol / 持久化任务与发布协议

P2 uses a PostgreSQL lease-based job runner. It does not add Kafka or Redis.

P2 使用基于 PostgreSQL 租约的任务执行器，不增加 Kafka 或 Redis。

```text
QUEUED
  -> SNAPSHOTTING
  -> PARSING
  -> CHUNKING
  -> EMBEDDING
  -> INDEXING
  -> VALIDATING
  -> READY

Any active step -> RETRY_WAIT -> same persisted step
Any active step -> FAILED
QUEUED/RETRY_WAIT -> CANCELLED
```

Workers claim small batches in short transactions with `FOR UPDATE SKIP LOCKED`, persist `lease_owner`, `lease_until`, attempt count, next-attempt time, current step and stable error code, and then release the transaction before external work. No database transaction remains open during worker or provider HTTP calls.

执行器使用 `FOR UPDATE SKIP LOCKED` 在短事务内领取小批量任务，持久化 `lease_owner`、`lease_until`、尝试次数、下次尝试时间、当前步骤和稳定错误码，然后在调用 Worker 或 Provider HTTP 前结束事务。外部调用期间不得保持数据库事务。

Each step is idempotent from persisted inputs. Restart resumes from the last durable step. Duplicate delivery cannot create duplicate chunks, vectors, index versions, usage charges, or audit events.

每一步都必须能根据已持久化输入实现幂等。进程重启从最后一个持久步骤继续；重复领取不能产生重复 Chunk、向量、索引版本、费用或审计事件。

### Atomic publication / 原子发布

Embeddings and entries are written under a mutable build identity. After completeness, dimension, lineage, digest, and source-set validation pass, one short transaction creates the immutable `knowledge_index_version` and marks it `READY`. Retrieval only resolves published versions. Failed or cancelled builds remain inspectable and are never addressable as production indices.

Embedding 和索引条目先写入可变 Build。完整性、维度、血缘、摘要和源集合校验全部通过后，才在一个短事务中创建不可变 `knowledge_index_version` 并标记为 `READY`。检索只能解析已发布版本；失败或取消的 Build 可以检查，但绝不能被生产索引引用。

## Parser and worker boundary / 解析器与 Worker 边界

The existing Python worker remains stateless and internal:

现有 Python Worker 保持无状态且仅供内部使用：

- Java owns authorization, source fetching, snapshot persistence, orchestration, retries, state, and audit.
- The worker accepts bounded content plus declared media type through a versioned internal contract.
- The worker returns normalized documents/chunks with page, heading, paragraph, or line anchors and parser/chunker version metadata.
- The worker never connects to PostgreSQL, fetches arbitrary URLs, resolves secrets, calls back into the control plane, or becomes a second source of truth.
- Worker failure returns stable machine-readable categories; Java persists the user-safe localized error code.
- Nginx exposes worker health only. Parser/chunker operations are not public browser endpoints.

The contract is tested in both Java and Python. Updating parser or chunker behavior creates a new declared algorithm version and therefore a new index; it never mutates old chunks in place.

该内部契约必须同时由 Java 和 Python 测试。解析或切块算法变化必须声明新版本并生成新索引，绝不能原地修改旧 Chunk。

## Embedding and vector baseline / Embedding 与向量基线

### Provider-neutral embedding / 厂商无关 Embedding

`capability-registry` exposes a provider-neutral embedding route with declared `EMBEDDING` capability, dimension, normalized input constraints, readiness, Secret Reference, and cost metadata. Spring AI remains the only Java AI abstraction. Provider SDK types cannot enter Knowledge APIs.

`capability-registry` 通过厂商无关的 Embedding Route 暴露 `EMBEDDING` 能力、维度、标准化输入约束、就绪状态、Secret Reference 和费用元数据。Spring AI 继续是唯一 Java AI 抽象，Provider SDK 类型不得进入 Knowledge API。

The default offline profile includes a clearly labeled deterministic development embedding adapter so Quick Start and CI can complete the entire workflow without paid credentials. It is not advertised as production semantic quality. Real embeddings are explicit opt-in routes.

默认离线配置提供明确标记的确定性开发 Embedding Adapter，使 Quick Start 与 CI 无需付费凭证也能跑完整闭环。它不能被宣传为生产级语义质量；真实 Embedding 必须显式启用。

### pgvector query / pgvector 查询

- Store vectors using pgvector with a checked declared dimension for each build.
- Use exact cosine ranking for the initial supported corpus envelope.
- Apply workspace, published-index-version, authorization, and non-tombstoned predicates before `ORDER BY distance LIMIT top_k` in the same SQL statement.
- Do not add ANN indexes until a measured corpus/performance threshold justifies the accuracy/operations trade-off.
- Do not claim hybrid retrieval in P2. Locale-neutral vector retrieval is the baseline; English/Chinese lexical ranking requires a later measured design.

首版使用精确余弦排序，避免在没有基准数据时过早引入 ANN 召回损失和运维复杂度。P2 也不宣称混合检索；英文/中文词法排序需要后续基于测量结果单独设计。

## Retrieval contract / 检索契约

An immutable `RetrievalPolicyVersion` defines at least:

不可变 `RetrievalPolicyVersion` 至少定义：

- `topK` and maximum context budget;
- minimum normalized similarity score;
- deterministic tie-breaking;
- duplicate/overlap handling;
- empty-evidence behavior;
- content-retention and masking reference.

Knowledge exposes a provider-neutral public Java interface equivalent to:

Knowledge 公开一个等价于下述结构的厂商无关 Java 接口：

```text
retrieve(workspaceId, principal, indexVersionId, policyVersionId, query)
  -> RetrievalResult(
       indexVersionId,
       policyVersionId,
       queryDigest,
       orderedHits[
         chunkId, sourceRevisionId, score,
         boundedContent, contentDigest,
         page, heading, paragraph, lineStart, lineEnd,
         authorizedLocatorMetadata
       ])
```

The REST Retrieval Lab exposes the same evidence with content limits and authorization. It never returns object-store paths, filesystem paths, provider credentials, unrestricted source URLs, or cross-workspace existence hints.

REST Retrieval Lab 返回同类证据，但必须受内容长度和授权约束；它不得返回对象存储路径、本地路径、Provider 凭证、无限制源 URL 或任何跨工作区存在性提示。

## ReleaseBundle 1.1 / 发布包 1.1

### Compatibility finding / 兼容性发现

Release manifest 1.0 must remain readable, but current code and the published schema need reconciliation before P2 can safely pin Knowledge. Legacy seeded `knowledge@1.0.0` strings are placeholders and cannot be interpreted as a valid live index.

Release Manifest 1.0 必须继续可读，但当前代码与公开 Schema 在 P2 固定 Knowledge 前必须统一。旧种子数据中的 `knowledge@1.0.0` 只是占位符，不能被解释为真实可用索引。

### New manifest semantics / 新发布语义

ReleaseBundle manifest 1.1 adds an explicit `runtimeMode`:

```text
CHAT | RAG
```

- `CHAT` does not require Knowledge pins.
- `RAG` requires one or more exact, authorized, `READY` Index Version references and exact Retrieval Policy Version references.
- All new references use canonical semantic-versioned identifiers; `latest` is forbidden.
- A complete JSON Schema validator replaces shallow field checks for new writes.
- Manifest 1.0 remains readable during `0.x`; legacy shorthand references are normalized only in memory and old immutable rows are never rewritten.
- Existing 1.0 releases execute with their historical CHAT behavior, even if they contain placeholder Knowledge strings.
- A new Grounded Answer/Citation JSON Schema 1.0 becomes a public contract.

No mutable Application draft configuration is consulted during a production Run.

生产 Run 期间不得读取任何可变 Application Draft 配置。

## Grounded runtime and citations / 可信运行时与引用

```text
Create Run/Trace identity
  -> governance admission and cost reservation
  -> resolve immutable ReleaseBundle
  -> retrieve exact pinned index/policy
  -> persist ordered retrieval evidence
  -> construct bounded untrusted context with [K1], [K2] markers
  -> invoke pinned chat route
  -> parse and validate markers
  -> persist GroundedAnswer + citations + usage/cost
```

Retrieved text is untrusted data. It is placed in a delimited context section, never promoted to the system instruction, and is preceded by a platform grounding rule that source text cannot override system policy or invoke tools.

检索文本是不可信数据，只能放入边界清晰的 Context 区域，绝不能提升为 System Prompt。平台必须先声明：资料内容不能覆盖系统策略，也不能发起工具调用。

Every model-visible item receives a deterministic marker such as `[K1]`. The answer parser accepts only markers present in that Run's retained hit set. A valid citation contains the immutable source revision, chunk identity, content digest, score/rank, available page/heading/line anchors, and an authorized locator generated at read time.

每个交给模型的证据都有确定性标记，如 `[K1]`。答案解析器只接受当前 Run 已保存命中集中的标记。有效引用必须包含不可变 Source Revision、Chunk ID、内容摘要、分数/排名、可用页码/标题/行锚点，以及读取时生成的授权定位信息。

If retrieval returns no acceptable evidence, the runtime returns a typed `NO_EVIDENCE` grounded result. If the model emits an unknown marker or malformed structured answer, the Run fails with a stable citation-validation error. Apvero must never silently strip a fabricated citation and report success.

检索没有合格证据时，运行时返回类型化的 `NO_EVIDENCE` 结果。模型输出未知标记或结构错误时，Run 以稳定的引用校验错误失败；Apvero 绝不能静默删除伪造引用后仍报告成功。

The deterministic local runtime produces a deterministic cited answer for offline E2E verification. It remains visibly labeled as local deterministic behavior.

## Governance extension / 治理扩展

P2 cannot bypass P1 cost and rate controls. Governance reservations evolve from one model route per Application Run to an execution subject plus billable components:

P2 不能绕过 P1 的成本与限流控制。治理预留需要从“每个 Application Run 一个模型路由”扩展为“执行主体 + 多个计费组件”：

```text
Execution subject:
  APPLICATION_RUN | KNOWLEDGE_INGESTION | KNOWLEDGE_QUERY

Reservation components:
  EMBEDDING_INDEX
  EMBEDDING_QUERY
  CHAT_GENERATION
```

Each component records exact route/version, estimated units/cost, actual units/cost, currency, admission decision, settlement status, and idempotency identity. Application budgets apply to Application executions; workspace/route budgets apply to ingestion and all model calls. A reservation must be persisted before any billable call and settled or released after every terminal path.

每个组件记录准确路由/版本、预估与实际用量/成本、币种、准入决定、结算状态和幂等标识。Application 预算适用于应用执行；Workspace/Route 预算适用于摄取和所有模型调用。任何付费调用前必须先持久化预留，所有终止路径都必须结算或释放。

Knowledge requests embedding execution through the public capability facade and uses Governance public APIs for ingestion admission, reservation, settlement, audit, and retention decisions. Runtime continues to use the existing capability/execution facade for query embedding and chat generation. Governance remains the only owner of reservation and settlement tables; neither Knowledge nor Runtime writes them directly.

Knowledge 通过公开 Capability Facade 请求 Embedding 执行，并通过 Governance 公开 API 完成摄取准入、预留、结算、审计与留存决策；Runtime 继续使用已有 Capability/Execution Facade 处理查询 Embedding 和 Chat Generation。Governance 继续独占预留与结算表，Knowledge 与 Runtime 都不得直接写入这些表。

This is a supporting P2 boundary change, not permission to redesign the whole governance module.

这是 P2 必需的支撑边界变化，不代表可以重构整个 Governance 模块。

## Public contracts / 公开契约

P2 implementation may add only versioned, workspace-scoped contracts in these groups:

P2 实现只能在以下分组中增加版本化、工作区受限的契约：

```text
/api/v1/knowledge-bases
/api/v1/knowledge-bases/{id}/sources
/api/v1/knowledge-sources/{id}/revisions
/api/v1/knowledge-ingestion-jobs
/api/v1/knowledge-indexes
/api/v1/knowledge-index-builds
/api/v1/knowledge-index-versions
/api/v1/retrieval-policy-versions
/api/v1/knowledge-retrieval-tests
/api/v1/applications/{id}/draft/knowledge-bindings
/api/v1/runs/{id}/retrieval
/api/v1/runs/{id}/citations
```

Exact URI and payload design is finalized in OpenAPI before controller implementation. Required public schemas are:

- ReleaseBundle Manifest 1.1;
- Grounded Answer 1.0;
- Citation 1.0;
- source/revision/index/retrieval REST schemas;
- versioned internal parser/chunker schema.

All errors use stable backend codes. English and Simplified Chinese clients localize them from complete matching keys.

所有错误使用稳定后端错误码，英文与简体中文客户端必须同时提供完整语言键。

## Security model / 安全模型

### Upload and parser threats / 上传与解析威胁

- Detect actual media type; never trust filename or request `Content-Type` alone.
- Enforce bounded request, decompressed archive, page, XML expansion, memory, CPU, and parser time limits.
- Reject executable, macro-enabled, encrypted/unsupported, malformed, and decompression-bomb inputs with stable errors.
- Run the worker as an unprivileged container with read-only filesystem, bounded temporary storage, no database credential, and no inbound public parser route.
- Never log raw document content by default.

### Web source SSRF / 网页数据源 SSRF

- Allow HTTP/HTTPS only.
- Resolve and validate every address; deny loopback, private, link-local, multicast, reserved, and cloud metadata ranges by default.
- Revalidate DNS and every redirect target to prevent rebinding and redirect bypass.
- Bound redirects, connect/read timeouts, response size and supported content type.
- Java captures the snapshot; the worker never fetches URLs.

### Tenant isolation / 租户隔离

- Every write validates organization/workspace ownership.
- Every read and pgvector query includes workspace scope before rank/limit.
- Cache keys, future object paths, events, metrics and logs include scoped identifiers without leaking content.
- Cross-workspace identifiers return a stable not-found/denied result without confirming existence.
- Composite keys and integration tests enforce isolation below the controller layer.

### Prompt-injection and citation safety / Prompt 注入与引用安全

- Retrieved content is data, not instruction.
- Source content cannot select capabilities or alter release policy.
- Only returned, authorized, immutable chunks can be cited.
- Citation locators are created through an authorized endpoint and never expose local storage paths.
- Retention/masking policy controls stored query and content excerpts; digests and structural evidence remain where policy permits reproducibility.

## Telemetry, audit, and retention / 遥测、审计与留存

Typed events and metrics cover:

类型化事件和指标覆盖：

- source create, snapshot, no-op sync, tombstone and failure;
- job wait/step/retry/failure duration;
- parser/chunker algorithm and version;
- embedding route, batch count, units, cost and latency;
- index build size, validation, publish and failure;
- retrieval count, latency, hit count, score distribution and empty evidence;
- release knowledge pins;
- grounded Run success, no evidence, invalid citation and provider failure;
- authorization, retention and cost-policy decisions.

Knowledge records auditable source, job, build, and publication mutations through the public Governance audit interface. Command-side security mutations and their audit event share one transaction through public module services; audit failure fails the mutation. High-volume step telemetry remains typed operational evidence rather than flooding the administrative audit ledger.

Knowledge 通过 Governance 公开 Audit 接口记录可审计的数据源、任务、Build 与发布变更。命令侧安全变更及其 Audit Event 通过公开模块服务共享一个事务；Audit 写入失败时，变更也必须失败。高频步骤遥测保留为类型化运维证据，不得淹没管理审计账本。

Audit metadata excludes secrets and masks retained content according to policy. Logs are diagnostic only; typed database Run/job/evidence records are the source of truth.

Audit 元数据不得包含 Secret，并按策略屏蔽留存内容。日志只用于诊断；类型化数据库 Run、Job 与 Evidence 记录才是事实来源。

## Alternatives considered / 已考虑的备选方案

1. **Add Milvus immediately.** Rejected: pgvector already closes the P2 workflow and Milvus would violate the PostgreSQL-only baseline without measured need.
2. **Add Kafka for ingestion.** Rejected: PostgreSQL leases provide durable, resumable P2 jobs with lower self-hosting cost. A queue requires measured throughput/failure evidence and another ADR.
3. **Add MinIO as mandatory storage.** Rejected: bounded PostgreSQL snapshots keep Quick Start coherent. Optional object storage can be added behind a port later.
4. **Let the Python worker own ingestion state and vectors.** Rejected: it creates a second source of truth and weakens Java module, migration, authorization, and audit guarantees.
5. **Expose worker parser endpoints to the browser.** Rejected: it bypasses authentication, workspace policy, retention, SSRF and audit.
6. **Use a fixed embedding dimension globally.** Rejected: dimensions are route/build metadata and releases must stay portable across approved providers.
7. **Publish entries incrementally into the live index.** Rejected: production could observe partial indices and unreproducible retrieval.
8. **Always resolve the newest index at Run time.** Rejected: it violates immutable ReleaseBundle semantics.
9. **Treat model citation text as trusted.** Rejected: models can fabricate references; citations must be validated against retained Run evidence.
10. **Ship all source connectors in P2.** Rejected: breadth would delay and weaken the closed workflow, security review, and parser quality.
11. **Implement hybrid/ANN retrieval now.** Rejected: without measured corpus and multilingual relevance evidence, this adds complexity without a defensible quality gain.
12. **Silently fall back from RAG to ordinary chat.** Rejected: it would present an ungrounded answer as grounded success.

## Internal delivery slices / 内部交付切片

These slices are implementation order, not independently releasable feature claims:

这些切片只是内部实现顺序，不代表可以独立宣称已交付功能：

### P2.0 — Accepted decision and contracts / 决策与契约

- approve this ADR;
- update module/dependency authority files exactly within the approved diff;
- reconcile Manifest 1.0 behavior and publish Manifest 1.1 plus citation schemas;
- define OpenAPI and worker contract before implementation.

### P2.1 — Durable ingestion spine / 可恢复摄取主干

- physical Knowledge module and ArchUnit/Modulith rules;
- additive migrations, source snapshots, revisions and persisted jobs;
- bounded file/web security controls;
- stateless worker parser/chunker contract;
- retry, restart, idempotency and failure evidence.

### P2.2 — Immutable index and Retrieval Lab / 不可变索引与检索实验室

- embedding route and governance components;
- pgvector entries and exact scoped ranking;
- validation and atomic publication;
- Retrieval Lab with score, source and lineage;
- offline deterministic and opt-in real embedding verification.

### P2.3 — Application-to-cited-Run closure / Application 到引用 Run 闭环

- Application draft Knowledge binding;
- ReleaseBundle 1.1 validation and exact pins;
- runtime retrieval, bounded context and citation validation;
- Run retrieval evidence, usage, cost and failure outcomes;
- source resync/tombstone behavior against old and new releases.

### P2.4 — Product and operations gate / 产品与运维门禁

- live Knowledge, Studio, Release, Playground and Run projections;
- English and Simplified Chinese UI/docs/error coverage;
- telemetry, audit, retention and System Health;
- Compose E2E, security, migration, isolation and performance-envelope evidence;
- maintainer P2 acceptance and stage transition record.

`APVERO_KNOWLEDGE_ENABLED=false` is the fail-closed default on partial implementation branches. It may become enabled by default only in the P2 acceptance change after the full runtime closure is verified. Partial implementation remains hidden or explicitly non-live.

在局部实现分支中，`APVERO_KNOWLEDGE_ENABLED=false` 是默认拒绝开关。只有完整运行闭环通过验证并进入 P2 验收变更时，才能改为默认启用；局部实现必须隐藏或明确标注为非真实功能。

## Verification baseline / 验证基线

Approval requires implementation evidence for at least:

获批后，实现必须至少提供以下证据：

1. Spring Modulith and ArchUnit allowed/forbidden dependency tests.
2. Flyway migration and clean-upgrade tests from the P1 baseline.
3. PostgreSQL Testcontainers tests for jobs, pgvector, atomic publication, immutability and composite workspace isolation.
4. Parser contract tests in Java and Python for Markdown, PDF, DOCX and HTML snapshots, including malformed/bomb/timeout cases.
5. Job crash, lease expiry, duplicate claim, retry, cancellation and restart tests.
6. Embedding dimension, batching, provider failure, idempotent charge and settlement tests.
7. Retrieval ranking, deterministic tie, threshold, empty result, source lineage and cross-workspace fail-closed tests.
8. Manifest 1.0 compatibility and Manifest 1.1 RAG validation tests.
9. Prompt-injection boundary, unknown citation, malformed answer, masked retention and authorized locator tests.
10. End-to-end offline flow: upload Markdown/PDF → ingest → publish index → retrieve → bind → preview → release → run → inspect verified citations.
11. End-to-end real-adapter tests against a local HTTP stub; paid-provider smoke tests remain opt-in and secret-free in CI.
12. TypeScript strict typecheck, frontend unit tests, Playwright critical paths and complete EN/zh-CN i18n validation.
13. OpenAPI/JSON Schema compatibility, secret/dependency/image/source scans, container build and Compose health.
14. A documented tested corpus envelope and gateway/runtime latency evidence; no unsupported scale claim.

## Migration and compatibility / 迁移与兼容

- Migrations are forward-only and additive.
- Existing P1 CHAT releases and runs remain readable and executable.
- Existing immutable release rows are never rewritten to manufacture valid Knowledge pins.
- New RAG releases require Manifest 1.1 and cannot execute on a P1-only runtime.
- Public contract changes are additive during `0.x`; incompatible removal still requires a deprecation and migration plan.
- Demo fixtures remain available only in explicit Demo mode and cannot be mixed with live P2 records without a visible mode label.

## Rollback and compatibility floor / 回滚与兼容下限

Before the first RAG release is created, rollback to the P1 binary is allowed because migrations are additive and ignored by P1.

在创建第一个 RAG Release 之前，可以回滚到 P1 二进制；增量表由 P1 忽略。

After any RAG release exists, the safe rollback floor is a P2-compatible binary that understands Manifest 1.1. Operators may disable Knowledge execution, but a pinned RAG release must then fail with a stable `KNOWLEDGE_DISABLED` outcome. It must never fall back to CHAT. Rolling back to a P1 binary after this point is unsupported because it could misinterpret release semantics.

一旦存在 RAG Release，安全回滚下限就是能理解 Manifest 1.1 的 P2-compatible 二进制。运维人员可以关闭 Knowledge 执行，但已固定的 RAG Release 必须稳定返回 `KNOWLEDGE_DISABLED`，绝不能回退为 CHAT。此后回滚到 P1 二进制不受支持，因为它可能误解发布语义。

Failed builds and additive tables are retained for diagnosis and later cleanup policy. No destructive down migration is provided. Operational rollback does not delete source snapshots, index versions, releases, runs, citations, usage, or audit evidence.

失败 Build 和增量表会保留用于诊断和后续清理策略。本阶段不提供破坏性 Down Migration；运维回滚不得删除源快照、索引版本、Release、Run、引用、用量或审计证据。

## Known limitations and self-critique / 已知限制与自我批判

1. PostgreSQL `bytea` keeps deployment simple but is not the ideal archive for very large files. P2 must publish honest limits and an optional storage-port path instead of hiding this trade-off.
2. Exact pgvector search is operationally simple and predictable but will not serve arbitrary corpus sizes. An ANN decision must be driven by benchmark evidence.
3. Vector-only retrieval avoids fragile language-specific tokenization, but may underperform hybrid search on exact codes/names. P2 must not market it as best-in-class retrieval.
4. DOCX/PDF extraction quality varies. P2 guarantees traceable extraction and visible failure, not perfect layout understanding or OCR.
5. A deterministic offline embedding proves the platform workflow, not semantic quality. The UI and documentation must state that clearly.
6. PostgreSQL polling is sufficient for the first bounded workload, but not necessarily for very high ingestion throughput. Queue extraction is a later evidence-based boundary.
7. Preserving immutable old releases conflicts with some legal-erasure requirements. Legal purge needs an explicit policy that reports lost reproducibility.
8. Manifest compatibility is already imperfect. It must be corrected as a P2 prerequisite instead of being hidden behind new Knowledge features.
9. Multiple model calls expose a limitation in the P1 reservation shape. The narrow component extension is required before real paid P2 calls.
10. Complete P2 is substantial. The feature flag and non-live labeling prevent a long partial implementation from misleading users, but they also mean no slice is considered product-complete until the whole cited-answer loop closes.

These limitations are accepted because they preserve a useful, self-hostable, auditable closed loop without pretending to solve every document, scale, or retrieval problem in one stage.

接受这些限制，是为了得到一个真正可用、可自托管、可审计的闭环，而不是在一个阶段内假装解决所有文档、规模和检索问题。

## Approved scope / 已批准范围

Maintainer approval authorizes only the following protected changes:

维护者批准后，只授权以下受保护变化：

1. activate a physical `knowledge` module inside the existing modular monolith;
2. change allowed dependencies exactly as listed in this ADR;
3. add additive PostgreSQL/pgvector persistence and a PostgreSQL lease-based job runner;
4. extend the existing stateless Python worker with versioned bounded parser/chunker contracts;
5. add provider-neutral embedding support through Spring AI only;
6. publish ReleaseBundle Manifest 1.1 and Grounded Answer/Citation 1.0 contracts while retaining Manifest 1.0 reads;
7. extend P1 governance reservations narrowly for multiple billable P2 components;
8. implement the P2 source → cited-answer workflow and its bilingual product surfaces;
9. establish the P2-compatible rollback floor after the first RAG release exists.

Approval does **not** authorize Kafka, Redis, MinIO, Milvus, Elasticsearch, another AI abstraction, a new deployable, browser-accessible worker parser endpoints, ANN/hybrid retrieval, live OCR/connectors, cross-module table access, plaintext secrets, mutable published indices, or fallback from failed RAG to ungrounded chat. Any such change requires separate review and, where protected, another ADR.

批准**不代表**允许引入 Kafka、Redis、MinIO、Milvus、Elasticsearch、第二套 AI 抽象、新部署单元、浏览器可访问的 Worker 解析接口、ANN/混合检索、真实 OCR/连接器、跨模块读表、明文 Secret、可变已发布索引，或从失败 RAG 回退到无依据 CHAT。任何此类变化都必须重新审查；若属于受保护领域，还需要新的 ADR。
