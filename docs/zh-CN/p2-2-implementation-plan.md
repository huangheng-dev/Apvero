# P2.2 不可变索引与检索实验室——实施计划

状态：维护者已批准设计；第 3 节勘误于 2026-07-24 获批；尚未开始业务实施

目标阶段：P2，里程碑 P2.2

决策基线：ADR-0006（已接受）

本计划使用的推理程度：高

功能开关：完整 P2 验收前保持 `APVERO_KNOWLEDGE_ENABLED=false`

## 1. 目标结果

P2.2 要建立一条真实、可重启恢复的索引与检索工作流：

```text
已授权 Workspace
  -> 精确的 READY Source Revision 集合
  -> 不可变 Embedding Route 版本
  -> 受治理的 Index Build
  -> 持久化 Embedding Batch 与 pgvector Entry
  -> 完整性、维度、血缘、摘要校验
  -> 原子发布不可变 Index Version
  -> 执行有完整 Scope 的精确 Retrieval Lab 查询
  -> 返回排序分数、内容摘要与来源血缘
  -> MATCHES 或类型化 NO_EVIDENCE
```

P2.2 不负责把 Knowledge 绑定到 Application，不写入 ReleaseBundle 1.1，不执行 Grounded
Run，也不会把 Knowledge 产品页面改为 Live。这些属于 P2.3 和 P2.4。所有局部页面仍必须
保持 Disabled、Hidden，或明确标记为 Contract-only/Demo。

仅仅做到“能插入向量、能查询向量”不算完成。此里程碑必须证明：发布后不可变、重启安全、
预算准入、Workspace 隔离默认拒绝、排序可复现，以及失败可检查。

## 2. 必须声明的变更范围

| 项目 | P2.2 计划 |
|---|---|
| 阶段 | P2 / P2.2，当前为 `planned` |
| 主模块 | `knowledge` |
| 支撑模块 | 仅通过公开 API 使用 `capability-registry`、`governance` 和 `identity` |
| 允许依赖 | 仅 `knowledge -> identity, capability-registry, governance` |
| 禁止依赖 | Knowledge 不依赖 Application、Release、Runtime 或 Provider SDK 类型 |
| 公开契约 | Embedding Model Route、Knowledge Index/Build/Version、Retrieval Policy Version、Retrieval Lab |
| 数据迁移 | V8 之后仅增加向前迁移 |
| 新有状态依赖 | 无；PostgreSQL 18 + pgvector 仍是唯一必需的有状态依赖 |
| 新部署单元 | 无 |
| AI 抽象 | Spring AI 2.0 继续是唯一 Java AI 抽象 |
| 产品暴露 | P2.4 前保持 Disabled/Non-live |
| 前端工作 | P2.2 实施切片不改前端 |

预期依赖用途：

```text
knowledge
  -> identity 公开 Workspace Scope
  -> capability-registry 公开 Embedding 执行门面
  -> governance 公开准入、预留、结算、留存与审计 API

capability-registry
  -> identity
  -> governance
  -> 内部 Spring AI Adapter

governance
  -> identity
```

任何切片都不得引入 Kafka、Redis、MinIO、Milvus、Elasticsearch、ANN Index、Hybrid
Retrieval、第二套 AI Framework、通用 Plugin Runner 或新的部署单元。

## 3. 实施必须遵守的已批准受保护勘误

对仓库与官方能力边界的审查发现了四处不能被静默编码的冲突。这些属于需维护者明确批准的
ADR-0006 澄清与配套契约勘误；维护者已于 2026-07-24 完成批准，所有实施切片必须遵守
修正后的权威文件。

### 3.1 Embedding Route 引用语义

当前 Java 与数据库中的 Model Route 使用不可变的单调整数版本，规范引用为 `name@N`。
OpenAPI 的 `EmbeddingModelRoute` 同样暴露整数 `version`，但
`KnowledgeIndexVersion.embeddingRouteVersion` 却强制要求 `name@semver`。二者不能同时成立。

已批准勘误：

- Model 与 Embedding Route 继续使用 `name@正整数`；
- Knowledge Index Version 与 Retrieval Policy Version 继续使用语义版本；
- Index Version 固定准确 Route ID 与规范 `name@N` 引用；
- 不为同一 Route 再创造一套平行的语义版本字段。

这样可以保留已实现的 P1 Route 血缘，而不是引入第二套版本体系。

### 3.2 pgvector 维度上限

当前契约允许 Embedding 维度达到 65,535，但 pgvector 的 `vector` 存储类型最多支持 16,000
维。P2.2 使用 `vector`，不使用有损或二进制替代品。

已批准勘误：

- 所有 P2 `vectorDimension`/Embedding Dimension 最大值从 65,535 改为 16,000；
- 拒绝零范数向量、非有限数、返回维度不匹配，以及超出存储范围的 Route；
- 不混淆 ANN Index 的维度上限与存储上限。P2.2 不创建 ANN Index。

### 3.3 已发布 Index 中的 Tombstone 行为

ADR-0006 一处规定 Tombstone 只影响未来 Build，旧 Index/Release 保持可复现；后面的查询
规则又要求每次 Retrieval 增加 Non-tombstoned Predicate。如果执行后者，已发布 Index
Version 的可观察内容会随后续 Source 状态改变。

已批准澄清：

- 创建精确 Build Source Set 时检查 Source 状态；
- Tombstoned Source 不能进入新 Build；
- 从已发布 Index Version 检索时，不根据 Source 当前 Tombstone 状态过滤；
- 读取时仍执行当前授权与当前 Retention/Masking Policy；
- 法律永久清除属于独立的破坏性治理流程，必须明确报告可复现性已被破坏。

### 3.4 可复现 Retrieval Policy 的算法标识

ADR-0006 要求确定性 Tie-break、Overlap Handling、Context Budget 和 Retention/Masking
Reference；当前 OpenAPI Policy 没有算法、Token Estimator 和 Retention Policy 血缘。
因此代码升级可能在 Policy ID 不变的情况下改变“不可变 Policy”的实际结果。

已批准在 Published Policy Projection 增加：

- `retrievalAlgorithmVersion`；
- `tokenEstimatorVersion`；
- `retentionPolicyVersionAtPublish`；
- `policyDigest`。

算法标识由平台从受支持实现中分配，客户端不能提交任意可执行算法名。当前
Retention/Masking 规则只能减少披露；旧 Policy 永远不能绕过更新、更严格的留存决定。

## 4. 架构与事实边界

```text
REST / 内部调用方
        |
        v
Knowledge 公开 API
  |         |          |
  |         |          +--> Governance 公开 API
  |         |                 Reservation + Component Ledger
  |         |
  |         +--> Capability Registry 公开 Embedding Facade
  |                    |
  |                    +--> 确定性 Spring AI EmbeddingModel
  |                    +--> 显式启用的 Spring AI Provider Adapter
  |
  +--> Knowledge Repository
           |
           +--> PostgreSQL Table
           +--> pgvector 精确余弦算子
```

事实归属：

- Knowledge Build Row 是工作流状态、Source Set、进度、校验和发布的事实来源；
- Capability Registry 独占 Route 形状、Readiness、Secret 解析边界和 Provider Adapter 选择；
- Governance 独占准入、预算/限流决策、Reservation Component 和 Settlement；
- Spring AI Response 是外部结果，不是工作流事实来源；
- Log 与 Metric 只用于诊断，不能替代 Build、Version、Entry、Reservation 或 Audit 记录。

Python Worker 不参与 P2.2；它继续严格限制在已批准的 Parser/Chunker 契约内。

## 5. Capability Registry 扩展

### 5.1 Route 形状

现有 `model_route` 表变为带判别字段的不可变 Route：

```text
route_capability = CHAT | EMBEDDING
```

现有行回填为 `CHAT`。Route Shape Constraint 要求：

- CHAT：必须存在 `max_output_tokens`，`temperature` 继续遵守现有规则；
- EMBEDDING：必须存在不可变 `dimension`、`maximum_input_tokens`、
  `maximum_batch_size`、`normalization`，Chat-only 字段为空；
- 被引用 Model Definition 声明匹配的 Capability；
- 已发布 Route Row 继续不可变。

Route Profile 会被复制进每个 Build。以后 Route Deprecation 或元数据变化不能改变已有
Build 或 Index Version。Deprecation 只阻止新 Build，不能改写或静默重路由已发布 Index
Version。历史固定 Route 只有在准确 Provider/Model/Secret 配置仍可用时才能执行；否则
Retrieval 返回稳定 Unavailable Outcome，绝不回退到其他 Embedding Space。

### 5.2 公开的厂商无关 Java 边界

公开 API 只使用 JDK/厂商无关 Record，等价于：

```text
EmbeddingRouteSnapshot resolveEmbeddingRoute(workspaceId, routeId)

EmbeddingExecutionResult embed(
  workspaceId,
  exactRouteReference,
  executionIdentity,
  orderedInputs[chunkId, contentDigest, boundedText]
)
```

Result 包含按输入顺序排列的 Float Vector、准确 Model/Route Identity、可获得时的实际输入
Unit、安全的 Provider Request Identity、Latency 与厂商无关 Cost Metadata。

规则：

1. Spring AI 或 Provider SDK 类型不得穿过公开模块 API；
2. 必须校验 Input Order 与 Output Index Mapping；缺失、重复或乱序输出直接失败；
3. 每一项返回维度必须等于固定 Route Dimension；
4. 每一个向量值必须是有限数，且用于余弦排序的向量必须具有非零范数；
5. 付费 Build 中不能调用 `EmbeddingModel.dimensions()` 做发现，因为 Spring AI 默认实现
   可能实际访问 Provider；声明 Profile 通过真实响应再次验证；
6. Provider Content 与无限制错误响应不得进入 Log 或持久化。

### 5.3 Adapter

P2.2 实现：

- `apvero-deterministic-embedding@1.0.0`：仅用于 Quick Start、CI 与工作流验证的确定性
  离线 Spring AI `EmbeddingModel`；
- 受显式 Route 与 Secret Reference 控制的 OpenAI-compatible Spring AI Embedding Adapter；
- 不使用付费凭证的本地 HTTP Stub 协议测试。

确定性 Adapter 必须跨 JVM Process、Locale、Timezone 和机器重启保持稳定。它的 Dimension、
Normalization、Hash/Canonicalization 和算法版本通过 Golden Vector 冻结，并明确标记为
“非生产语义质量”。

Provider 特有能力（如 Dimension Override）只能存在 Adapter 内部，且必须与不可变 Route
Profile 一致，不能泄漏到 Knowledge。

## 6. Governance Component 扩展

P2.2 只使用 ADR-0006 已授权的窄扩展，不重新设计 Budget。

Execution Subject：

```text
APPLICATION_RUN
KNOWLEDGE_INGESTION
KNOWLEDGE_QUERY
```

P2.2 使用的 Reservation Component：

```text
EMBEDDING_INDEX
EMBEDDING_QUERY
```

现有 P1 API 通过适配方法保持源码兼容。新 Reservation 记录：

- Subject Type 与不透明 Subject ID；
- 准确 Route ID/Reference；
- Component Type 与确定性 Idempotency Identity；
- Estimated/Actual Unit、Cost、Currency；
- Admission、Dispatch 与 Settlement State；
- 安全且可用时的 Provider Request Identity；
- Timestamp 与稳定 Failure/Reconciliation Code。

仅对非 Application Subject 放宽 Application ID 为空；旧数据回填为 `APPLICATION_RUN`，
现有 Runtime 行为与预算匹配保持不变。

Workspace Policy 适用于每个 P2.2 调用；Model Route Policy 匹配 Component 的准确 Route；
Application Policy 只对 `APPLICATION_RUN` 求值，对 Knowledge Subject 直接跳过，不执行空值比较。

Reservation 和 Component 表继续由 Governance 独占，Knowledge 不直接读写。

### 外部调用结果不确定

本地数据库事务无法让外部 Provider 调用天然 Exactly-once。本计划拒绝虚假的
Exactly-once 宣称：

1. Dispatch 前持久化 Reservation/Component；
2. HTTP 调用前把 Component 标记为 Dispatched；
3. Adapter/Provider 支持时复用同一个确定性 Provider Idempotency Identity；
4. 最终 Settlement 前先持久化校验通过的 Entry；
5. Entry 已存在但 Settlement 未完成时，只恢复 Settlement，不再次调用 Provider；
6. Dispatch 已发生但 Response 未持久化时，仅在 Adapter 声明 Provider-side Idempotency
   的情况下自动重试；
7. 否则以 `APVERO_EMBEDDING_OUTCOME_AMBIGUOUS` 停止，标记需要 Reconciliation，绝不冒险
   再发一次付费调用。

Governance Ledger 本身保持幂等：相同的重复 Settlement 是 No-op；冲突 Settlement 默认
拒绝。过期的 Dispatched Component 不得静默按零费用结算。

## 7. 持久化设计

P2.2 使用已批准的六张 Knowledge 表与一张 Governance Component 表，不增加未获批准的
Knowledge Batch 表。

### `retrieval_policy_version`

Insert-only 不可变 Policy，包含完整 Scope、Slug、Semantic Version、Algorithm/Estimator
Version、`top_k`、最大 Context Budget、Minimum Score、Overlap Behavior、`NO_EVIDENCE`、
Retention Provenance、Canonical Policy Digest 与创建证据。

唯一标识：

- `(workspace_id, slug, version)`；
- `(workspace_id, policy_digest)`；
- 完整 Composite Scope Key。

### `knowledge_index`

绑定到一个 Knowledge Base 的稳定 Index Identity，包含 Slug、Name、Status、乐观元数据版本、
Version Count、可空 Latest READY Version ID 与时间戳。

`latest_ready_version_id` 只用于展示。Retrieval 与后续 Release Binding 始终使用准确
Version ID/Reference。

### `knowledge_index_build`

持久化的可变工作流 Row，包含：

- 完整 Scope、Index ID 与请求的 Semantic Version；
- 准确 Route ID/Reference 与复制的 Embedding Profile；
- Canonical Request Digest 与准确 Source/Chunk Count；
- Status/Current Step、Attempt/Maximum Attempt、Retryability、Next-attempt Time；
- Lease Owner/Until 与 Optimistic Lock Version；
- Cancellation Request；
- Embedded/Validated Entry Count 与最后持久化 Chunk Ordinal；
- Validation/Artifact Digest 与 Published Version ID；
- 稳定、安全的 Error/Reconciliation Metadata 与时间戳。

`(index_id, version)` 是公开 Create 的 Idempotency Identity。相同 Canonical Request 重复
提交时返回现有 Build；同一 Version 换 Route 或 Source Set 时返回稳定 Conflict。

### `knowledge_index_build_revision`

精确 Source Revision Set 的不可变有序快照，重复保存 Scope、Build、Source、Revision、
Content Digest、Parser/Chunker Version 与 Source-set Ordinal。

创建时在同一事务验证：

- Index、Base、Source、Revision 位于相同 Workspace 与 Base；
- 每个 Revision 都有 READY Ingestion 结果且至少一个 Chunk；
- Build 创建时每个 Source 都是 Active；
- ID 唯一，并按规范顺序保存。

以后禁止再查“Latest Revision”替换它。

### `knowledge_index_entry`

Build-scoped Entry，包含完整 Scope、Build、Chunk/Document/Revision/Source 血缘、确定性 Entry
Ordinal、`embedding vector`、重复 Vector Dimension、Vector Digest、Normalized-input Digest、
Batch Ordinal、准确 Route Reference 与创建时间。

Column 使用不限制维度的 `vector`，因为不同 Build 可以使用不同 Dimension。Row Check 保证
`vector_dims(embedding) = vector_dimension` 且 `vector_norm(embedding) > 0`；Composite
Foreign Key 保证 Entry Dimension 等于固定的 Build Dimension。pgvector 会拒绝非有限数。

P2.2 不创建 HNSW 或 IVFFlat Index。普通 B-tree Index 用于 Scope/Version Join 与确定性
Lineage 访问。

### `knowledge_index_version`

Insert-only 不可变 Published Version，包含完整 Scope、Index/Build Identity、Semantic
Version/Reference、准确 Route ID/Reference、Dimension、Source/Chunk Count、Artifact
Digest、固定 `READY` 状态与发布时间。

### `execution_reservation_component`

Governance 独占的 Append/Transition Ledger，结构遵守第 6 节，唯一键为
`(reservation_id, idempotency_identity)`。

## 8. 数据库不可变保护与原子发布

Build Source Row 与 Entry Row 从创建起就是 Insert-only。只有 Build 未发布时才能增加新
Entry。发布后：

- `knowledge_index_version` 拒绝 Update/Delete；
- 对应 Build 拒绝 Update/Delete；
- 所有 Build Revision 与 Entry 拒绝 Update/Delete；
- 已发布 Build 拒绝插入新 Entry；
- 拒绝普通删除已发布 Artifact。

发布在一个短事务中完成：

1. 锁定 Build 与稳定 Index Identity；
2. 要求 Build Status 为 `VALIDATING`，并持有未过期 Lease；
3. 校验准确 Source Set Cardinality 与 Digest；
4. 校验每个选中 Chunk 恰好一个 Entry；
5. 校验没有未选中的 Chunk/Revision 混入；
6. 校验 Route/Reference、Dimension、Finite Non-zero Vector、Lineage 与全部 Entry Digest；
7. 计算 Canonical Artifact Digest；
8. 插入且只插入一个不可变 Index Version；
9. 把 Build 设为 `READY`、关联 Published Version、清除 Lease；
10. 更新 Index 展示元数据；
11. 追加 Publication Audit Event；
12. Commit。

任何失败都回滚全部发布操作。Retrieval 不能接受 Build ID，只能解析 READY Index Version。

Artifact Digest 使用 SHA-256，对稳定 Ordinal 排序后的长度前缀二进制字段计算，至少包含：
Source Revision Identity/Digest、Parser/Chunker Version、Chunk Identity/Content Digest、准确
Route Reference/Profile、Vector Dimension，以及返回的 IEEE-754 Float32 Byte 的 SHA-256
Digest。它不能依赖 JSON Object 顺序或 Locale-sensitive Number Formatting。

## 9. Index Build 状态机与执行

```text
QUEUED
  -> EMBEDDING
  -> INDEXING
  -> VALIDATING
  -> READY

Active Step -> RETRY_WAIT -> 相同持久步骤
Active Step -> FAILED
QUEUED 或 RETRY_WAIT -> CANCELLED
付费 Dispatch 结果不确定 -> FAILED / 需要 Reconciliation
```

步骤含义：

- `EMBEDDING`：确定性选择缺失 Entry Batch，执行 Reserve、Dispatch、Validate、Persist；
- `INDEXING`：校验完整 Entry Set，计算 Canonical Entry/Source Manifest；
- `VALIDATING`：执行全部 Publication Gate 并原子发布；
- `READY`：不可变 Version 已存在，不能只根据 Entry Count 推断。

Runner 复用 P2.1 PostgreSQL Lease 模式：

1. 使用 `FOR UPDATE SKIP LOCKED` 领取小批任务；
2. Provider I/O 前提交 Lease/Attempt State；
3. 只使用已持久化输入；
4. 不持有数据库事务执行有边界 I/O；
5. 完整校验输出；
6. 幂等提交 Result 与 Next Step；
7. 使用 Owner/Version Check 清理或续租 Lease。

Batch Membership 根据排序后的缺失 Chunk Ordinal 与固定 Route Limit 确定生成。内存 Queue
不是事实来源。Entry Unique Constraint 阻止重复 Vector；Governance Component Identity
阻止重复 Ledger Charge。

Cancellation 只在 `QUEUED` 或 `RETRY_WAIT` 接受；活动 Provider Call 不能被虚假报告为
Cancelled。Graceful Shutdown 停止新 Claim 并执行有上限 Drain；Lease 到期后恢复遗留工作。

## 10. Batching 与 Token/Unit 规则

`maximumInputTokens` 定义为一次 Provider Request 的最大“估算总输入 Unit”，
`maximumBatchSize` 定义最大 Item Count。每个单独 Chunk 也必须能独立放入该总量限制。

初始确定性 Estimator 要有版本，并对中英文采取保守行为。它不宣称等于 Provider 精确
计费 Unit。Provider 返回实际 Usage Metadata 时用它结算；否则保存版本化 Estimate，并标记
`ESTIMATED` Quality。

Batch 顺序固定为：

```text
ORDER BY source-set ordinal, document ordinal, chunk ordinal, chunk ID
```

达到 Item Count 或 Input Unit 上限前停止。单项超限时使用稳定、不可重试错误结束 Build，
不得静默截断 Source Text。

Estimator 算法与确定性 Adapter Dimension 只有在 P2.2a 双语 Corpus Benchmark 后才能冻结。
Decision Record 必须覆盖英文、简体中文、混合语言、Long-token、Empty/Whitespace 与对抗
Unicode。

## 11. 精确 Retrieval Lab

### 11.1 Query Embedding

Retrieval 先解析准确 READY Index Version，再使用其中固定的 Embedding Route Version 处理
Query。真实付费调用前创建 `KNOWLEDGE_QUERY / EMBEDDING_QUERY` Reservation。Query Vector
必须与 Index Dimension 一致且具有非零范数。

Raw Query 在公开 API 中是 Write-only。P2.2 默认只持久化 SHA-256 Query Digest、Route、
Usage/Cost、Outcome 与 Latency，不增加 Raw Query 持久化。

### 11.2 一条有 Scope 的 Ranking SQL

Repository 使用一条等价于下述结构的 SQL：

```sql
SELECT ...
FROM knowledge_index_version version
JOIN knowledge_index_build build ON ...
JOIN knowledge_index_entry entry ON ...
JOIN knowledge_chunk chunk ON ...
JOIN knowledge_document document ON ...
JOIN knowledge_source_revision revision ON ...
JOIN knowledge_source source ON ...
WHERE version.tenant_id = :tenant_id
  AND version.workspace_id = :workspace_id
  AND version.id = :index_version_id
  AND version.status = 'READY'
  AND vector_dims(:query_vector::vector) = version.vector_dimension
  AND 1 - (entry.embedding <=> :query_vector::vector) >= :minimum_score
ORDER BY entry.embedding <=> :query_vector::vector ASC, entry.chunk_id ASC
LIMIT :top_k
```

Scope 与 READY Version Filter 必须和 Ranking 在同一 Statement，并在 Order/Limit 之前生效。
Repository 禁止先全局查候选向量，再在 Java 中过滤 Workspace。

根据第 3.3 节已批准澄清，SQL 不检查 Source 当前 Tombstone；Published Source Set 保持不可变。
当前 Authorization 与 Disclosure Policy 仍然执行。

公开 Cosine Similarity 限制在 `[0, 1]`，数据库 Distance 是排序事实来源。Distance 相同时，
按不可变 Chunk UUID 升序 Tie-break。

### 11.3 Policy 应用

SQL 排序结果返回后：

- `KEEP` 保留全部合格 Hit；
- `COLLAPSE_ADJACENT` 对同一 Document 中相邻且重叠的 Chunk，确定性保留排名更高者；
- 冻结的 Estimator 按 Rank Order 应用 Maximum Context Budget；
- 后面的 Hit 不能被提升到前面已接受 Hit 之前；
- 最终为空时返回 `NO_EVIDENCE`，绝不回退到 Ungrounded Chat。

`topK` 是最大值；Overlap 或 Context Filter 后不承诺补满。

### 11.4 暴露内容

每个 Hit 返回 Rank、Normalized Score、不可变 Source/Revision/Document/Chunk Identity、
Content Digest、Policy 允许时的 Bounded Content、Source Type/Title，以及可用的
Page/Heading/Paragraph/Line Anchor。

禁止返回 Snapshot Bytes、契约外内部 Table Key、Filesystem/Object Path、Secret Reference、
Provider Response、Raw Web URL 或任何 Cross-workspace Existence Hint。

## 12. 安全、错误、审计与遥测

Read 使用现有 Read Scope；Index/Policy/Build Mutation 与 Retry/Cancel 使用现有 Write 或
Admin Scope。P2.2 不虚假宣称提供 Resource-level ABAC。

稳定公开错误族至少包括：

- Knowledge Disabled；
- 受 Scope 限制的 Index、Build、Version、Policy、Route、Revision Not Found；
- Route 不是 EMBEDDING、未 READY、已禁止新 Build、Profile 非法；
- Source Tombstoned、Revision 未 READY、Source Set/Base 不匹配；
- Build Version Conflict、非法状态转换、Lease Conflict、不可 Retry/Cancel；
- Embedding Input/Batch Limit、Provider Unavailable、Output Index/Dimension 非法、非有限数；
- Budget/Rate Denial、Settlement Conflict、Provider Outcome Ambiguous；
- Entry 不完整、Lineage/Digest/Source Set Validation Failure；
- Index Version 未 READY、Query 过大、Dimension Mismatch；
- `NO_EVIDENCE` 是成功的类型化结果，不是异常。

Backend Error 只返回稳定 Code 与安全结构字段，客户端负责本地化。禁止新增硬编码
User-visible Text。

Administrative Audit 包括 Index/Policy Create、Build Request、Manual Retry/Cancel、
Terminal Failure/Reconciliation 与 Version Publication。Batch 级进度属于类型化 State/
Telemetry，不写成 Audit Spam。

Metrics 至少覆盖：

- Build Queue Wait、Step Duration、Attempt、Outcome；
- Requested/Embedded/Validated Chunk 与 Source Count；
- Batch Item/Input Unit、Provider Latency、Settlement Outcome；
- Dimension 与 Algorithm Version；
- Retrieval Latency、Candidate/Hit Count、Score Distribution、`NO_EVIDENCE`；
- Publication Validation Outcome。

Metric Label 只能使用低基数字段。Tenant、Workspace、Query、Content、Route/Build/Version ID、
URL、Provider Request ID 禁止作为 Label。

Spring AI Observation 只补充 Apvero 记录，不能代替 Governance Ledger 或 Build State。
Sensitive Prompt/Content Observation Export 继续默认关闭。

## 13. 性能边界

精确 pgvector 查询拥有确定性完整召回，但不能支持任意 Corpus Size。P2.2 验收必须发布实测
边界，而不是营销数字。

Benchmark Matrix 必须包括：

- 确定性 Adapter Dimension，以及有代表性的 384、768、1,536 Dimension；
- Small、Medium、Acceptance-limit Corpus；
- 英文、简体中文、混合语言 Query；
- Cold 与 Warm Database State；
- No-hit、Low-threshold、High-`topK`；
- 并发 Retrieval Lab Request 与同时执行的 Build Write；
- Storage Size、Build Throughput、p50/p95/p99 Latency、Query Plan。

支持的最大 Corpus 与 Concurrency 由文档化参考机器上的 Resource Budget 实测选择。如果
Exact Search 不满足已接受 Latency Envelope，就下调公开支持边界，不能静默加入 ANN。

## 14. 实施切片

切片按顺序合并，但任何切片都不能单独把 P2 或 Knowledge Page 改为 Live。

### P2.2a——受保护勘误与 Capability/Governance 外壳

- 保留第 3 节维护者批准记录；
- 修订 ADR-0006 与中英文 Contract Baseline；
- 勘误 OpenAPI Route Reference、Dimension、Policy Provenance；
- 增加 Embedding Route Shape 与厂商无关公开 API；
- 增加向后兼容的 Governance Subject/Component API；
- 建立 Corpus Benchmark 与确定性 Adapter Decision Record；
- 在启用业务执行前扩展 Modulith/ArchUnit 检查。

### P2.2b——有 Scope 的不可变持久化

- 增加 Knowledge 与 Governance 向前迁移；
- 实现全部 Composite Scope Key、Shape Check、Uniqueness、Immutability Trigger；
- 实现不跨模块读表的 Scoped Repository；
- 验证 Clean Migration 与 V8-to-head Upgrade；
- 验证 Published Artifact 拒绝 Mutation，Failed Unpublished Build 保持可检查。

### P2.2c——受治理 Embedding 执行

- 实现确定性 Spring AI Embedding Adapter 与 Golden Vector；
- 实现显式启用的 OpenAI-compatible Adapter 和本地 Protocol Stub；
- 实现 Route Readiness、Batching、Output/Dimension/Finite Validation；
- 实现 Admission、Dispatch、Settlement、Ambiguous Outcome；
- 验证 Crash/Retry 不产生重复 Entry 或 Ledger Charge。

### P2.2d——持久化 Build 与原子发布

- 实现 Build Create/List/Get/Retry/Cancel API；
- 实现准确 Source Set Snapshot 与 Canonical Request Idempotency；
- 实现 Leased Build Runner 与全部持久状态转换；
- 实现 Manifest/Digest Validation 与单事务 Publication；
- 增加 Audit、Metric、Health 与 Restart Recovery 证据。

### P2.2e——精确 Retrieval Lab

- 实现不可变 Retrieval Policy Publication；
- 实现 Query Admission/Embedding 与有 Scope 的精确 Cosine SQL；
- 实现确定性 Tie、Threshold、Overlap、Context Budget、`NO_EVIDENCE`；
- 暴露有边界 Lineage/Content Projection；
- 验证 Cross-workspace、Tombstone History、Masking、Query Retention。

### P2.2f——验收加固

- 执行 Corpus/Performance Envelope 并记录支持边界；
- 执行真实 Adapter Local-stub 验证与可选 Secret-free Local Provider Smoke；
- 执行 Migration、Architecture、Contract、Security、Compose、Container 检查；
- 生成匹配的中英文 Slice Evidence 与 P2.2 Acceptance Candidate；
- 维护者验收前保持 P2 `in-progress`、Knowledge Disabled、P2.3 未启动。

## 15. 验证矩阵

| 领域 | 最低证据 |
|---|---|
| 架构 | Spring Modulith/ArchUnit 允许与禁止依赖；公开 API 无 Provider 类型 |
| 契约 | OpenAPI 3.1 校验、Conformance 与已批准勘误证据 |
| 迁移 | Clean Install、V8 Upgrade、Check、Composite Scope FK、Trigger、Forward Mitigation |
| 隔离 | 所有 Build/Version/Policy/Retrieval 路径使用两个 Tenant/Workspace 验证 |
| Route | CHAT Backfill、EMBEDDING Shape、不可变 Profile、Readiness、Secret Failure |
| Embedding | Golden Vector、Order、Dimension、Finite Value、Batch/Input Limit |
| Governance | Admission Denial、Component Identity、Settle/Release、Duplicate Settle、Stale/Ambiguous Call |
| Job | Dispatch/Entry Commit/Settlement 前后 Crash；Lease Expiry、Retry、Cancel、Restart |
| 发布 | Missing/Extra/Duplicate Entry、错误 Lineage/Dimension/Digest、Atomic Rollback、Mutation Rejection |
| 检索 | Exact Order、Deterministic Tie、Threshold、topK、Overlap、Context Budget、NO_EVIDENCE |
| 历史 | Tombstoned Source 不进入新 Build，但旧 Published Version 仍可检索 |
| 留存 | 只保留 Query Digest、Content 有边界/Mask、无 Raw URL/Path/Secret/Provider Error |
| 遥测 | 低基数 Metric、安全 Log、Administrative Audit、Spring AI Observation Boundary |
| 性能 | 文档化 Exact-search Corpus/Concurrency Envelope、Query Plan、p50/p95/p99 |
| 部署 | PostgreSQL-only Mandatory State、Worker 暴露不变、Compose Healthy、Restart-safe |
| 国际化 | 中英文计划、证据、未来 Client Key 匹配 |

适用的 Java、Testcontainers、OpenAPI、Flyway、Security、Dependency、Container、Compose
检查必须通过。只有修改前端文件时才要求 TypeScript/Playwright；P2.2 未获授权把页面改为 Live。

## 16. 上线与回滚

- Migration 保持 Additive、Forward-only；
- 现有 P1/P2.1 Binary 忽略新表，CHAT 行为不变；
- `APVERO_KNOWLEDGE_ENABLED=false` 继续为默认值；
- P2.2 只能在显式 Development/Verification 环境启用；
- Disable 后停止新 Build Claim 与 Retrieval Lab Call，并执行有上限 Drain；
- 已发布 Index Version 与 Failed Build 继续可检查；
- 回滚使用之前的兼容 Binary，保留所有新 Row；
- 不提供破坏性 Down Migration 或自动 Vector Cleanup；
- P2-compatible Release Rollback Floor 要到 P2.3 创建第一个有效 RAG Release 后才生效。

## 17. 自我批判与拒绝的捷径

1. Variable-dimension `vector` Column 不如 `vector(n)` 强类型，但它是避免全局固定 Dimension
   的必要选择；Row Check、复制 Dimension、Composite Build Link 会恢复约束。
2. Exact Cosine Search 简单、可复现，但 Latency 随 Corpus 增长；项目必须发布有边界的
   Envelope，拒绝不受支持的规模宣称。
3. Vector-only Retrieval 对准确 Code、Identifier 和部分多语言 Query 较弱；P2.2 不能宣传
   Hybrid 或 Best-in-class Retrieval。
4. Offline Deterministic Adapter 只证明 Orchestration，不证明 Semantic Relevance；把它叫
   生产 Model 是不诚实的。
5. PostgreSQL Lease 本质是 At-least-once。数据库唯一约束能防重复 Row 与 Ledger Entry，
   但不能让非幂等 Remote Provider 获得 Exactly-once。
6. Ambiguous Paid Request 停止运行会增加运维成本，但盲目自动重试可能让用户付两次费；
   安全优先。
7. 使用 `(index_id, version)` 做 Create Idempotency 很简单，但 Semantic Version 一旦用过就
   永久冲突；这正符合不可变发布。
8. 把 Vector 放在 Control-plane Database 会增加 Table Size 与 Backup Cost；这是
   PostgreSQL-only Self-hosted Baseline 的取舍，不是任意规模的最终设计。
9. 当前 P1 Governance 假设 Application + One Route。扩展必须以兼容为先，广泛 Billing
   重构超出 ADR-0006。
10. 当前 API Scope 较粗。P2.2 能做到 Workspace Fail-closed，但不能虚假宣称 Document-level
    ABAC。
11. Retrieval Policy 必须冻结 Algorithm Identity；否则“不可变 Policy”只是数据库标签，
    Code Upgrade 仍会静默改变行为。
12. 当前 Retention 可以隐藏旧 Index Version 的 Content，这会降低完美 Replay；但 Disclosure
    Safety 与法律控制优先于返回历史明文，Lineage/Digest 仍保留。
13. 在旧 Published Version 中保留 Tombstoned Content 是普通可复现性的需要，却与永久清除
    有冲突；Erasure 必须显式报告破坏的 Artifact，不能伪装成普通 Tombstone。
14. `latest_ready_version_id` 对 UI 有用，对执行很危险。所有 Execution API 必须拒绝
    `latest` 并要求准确 Identity。
15. 单独 Batch Table 会让恢复更容易，但它不在批准的模块表清单中。确定性 Batch Membership
    加 Governance Component 足够支撑 P2.2；以后是否加表必须由证据而不是便利性决定。

## 18. 验收门禁

只有下面这句话可以无保留成立时，P2.2 才能提请维护者验收：

> 在已授权 Workspace 中，Apvero 能从准确、不可变的 Source Revision Set 构建受治理的
> pgvector Artifact，在每个持久失败边界后安全恢复，把完整 Artifact 原子发布为不可变
> Index Version，并运行精确 Retrieval Lab：确定性返回排序证据或类型化 NO_EVIDENCE；
> 全程不泄漏 Workspace 数据、不绕过费用控制、不重复 Ledger Charge，也不虚假宣称未支持
> 的 Retrieval Quality。

验收只更新 P2.2 证据。P2 继续保持 `in-progress`，Knowledge 默认仍关闭；下一里程碑是
P2.3 Application-to-cited-Run Closure。

## 19. 主要实施参考

- Spring AI Embedding Model API：
  <https://docs.spring.io/spring-ai/reference/api/embeddings.html>
- Spring AI Embedding Observability：
  <https://docs.spring.io/spring-ai/reference/observability/index.html#_embeddingmodel>
- Spring AI Batching Strategy：
  <https://docs.spring.io/spring-ai/reference/api/vectordbs.html#_batching_strategy>
- pgvector Exact Search、Dimension 与 Variable-dimension Column：
  <https://github.com/pgvector/pgvector>
- PostgreSQL 18 Constraint：
  <https://www.postgresql.org/docs/18/ddl-constraints.html>
- PostgreSQL 18 Trigger Behavior：
  <https://www.postgresql.org/docs/18/trigger-definition.html>
