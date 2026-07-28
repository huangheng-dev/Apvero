# P2.2d-4 验证与原子发布——实施计划

状态：实施候选；尚未开始业务编码

目标：P2 / P2.2d-4

权威依据：ADR-0006、已批准的 P2.2d Durable Build 基线、V10/V11 持久化与数据库守卫，以及
已验证的 P2.2d-3 Governed Embedding Orchestration

推理强度：高

## 1. 目标结果

P2.2d-4 闭合 Embedding 之后的内部不可变索引构建路径：

```text
已持有 Lease 的 INDEXING Build
  -> 重建准确、冻结的 Source/Chunk/Entry Manifest
  -> 验证基数、顺序、血缘、Route 与 Vector 证据
  -> 持久化验证证据并进入 VALIDATING
  -> 重新领取 VALIDATING Build
  -> 重新计算所有发布关键证据
  -> 原子发布一个不可变 READY Index Version
  -> 关联 Build 并更新 Index 展示元数据
  -> 在同一事务中追加 Governance Audit
```

只有当 READY Version 必然对应一个结构完整、可复现的 Artifact，并且每个 Crash、Retry、
过期 Lease 与双 Publisher 竞争都有确定结果时，本切片才算完成。

本切片不启用生产 Runner，不上线 Index Version REST 操作，不实现 Retrieval Lab，不绑定
Application，不修改 ReleaseBundle，不增加前端行为，也不启动 P2.2e。

## 2. 变更声明

| 项目 | 决定 |
|---|---|
| 阶段 | P2 / P2.2d-4，`in-progress` |
| 主模块 | `knowledge` |
| 支撑模块 | 现有 `identity`、`capability-registry`、`governance` 公开 API |
| 允许依赖 | Knowledge → Identity、Capability Registry、Governance |
| REST / OpenAPI / JSON Schema | 不变 |
| 数据库迁移 | 无；V10/V11 已包含所需记录、键与守卫 |
| 有状态依赖 / Deployable | 无 |
| AI 抽象 / Provider 调用 | 无；验证与发布完全在本地完成 |
| 前端 / Python | 不变 |
| 暴露状态 | 仅内部且保持禁用；不宣称产品能力已上线 |

ADR-0006 已授权不可变验证与原子发布。本计划不改变 Invariant、模块边界、公开契约、
Release 语义、安全策略或技术基线，因此不需要新 ADR。若实施证明必须新增表、列、
Deployable、有状态依赖、模块依赖或公开契约，则立即停止编码并回到架构审查。

## 3. 必须复用的现有权威实现

- `KnowledgeIndexBuildTransitionKernel` 继续是 Build Transition 与终止失败的唯一
  Lease-fenced 所有者。
- `KnowledgeIndexPersistenceRepository` 继续是 Knowledge 自有的索引持久化边界。
- `KnowledgePersistenceRepository` 继续是不可变 Source Revision、Document 与 Chunk 的
  Knowledge 自有读取边界。
- `EmbeddingRouteCatalog` 是验证使用的 Provider-neutral Metadata 读取边界；发布不调用面向
  执行的 `EmbeddingCapability`。
- P2.2d-3 的规范顺序保持不变：
  `sourceSetOrdinal -> document.ordinal -> chunk.ordinal -> chunk.id`。
- P2.2d-3 的稳定 Entry ID 与精确 float32 Vector Digest 继续作为权威算法。Digest 实现移入
  唯一的 Knowledge 内部规范 Helper，不能复制第二套实现。
- `AuditEventCatalog` 继续是唯一发布审计边界。Knowledge 绝不写 Governance 表。
- V11 数据库守卫继续是 Build Transition、延迟 Entry 写入、Version 插入与终态不可变性的
  最终数据库防线。

不得引入第二套 Source 排序、Vector 编码、Lease 实现、发布表或 Audit Store。

## 4. 编码前修正与 Repository 边界

### 4.1 唯一的规范 Artifact Validator

新增一个包内纯验证组件：输入带 Scope 的 Build 与 Repository 证据，输出不可变的
`ValidatedIndexArtifact`。它不修改状态、不调用 Provider、不读取日志，也不推测缺失证据。

返回的 Artifact 只包含发布关键值：

- 准确的 Build、Index 与 Requested Version 标识；
- 有序、冻结的 Build Revisions；
- 有序的 Document/Chunk 标识与 Content Digest；
- 有序的 Entry 标识、Input Digest 与重新计算的 Vector Digest；
- 准确的 Route ID/Reference 与固定 Profile；
- 规范 Source、Chunk、Entry 数量；
- Validation Digest 与 Artifact Digest。

INDEXING 与 VALIDATING 必须使用同一个 Validator。VALIDATING 发布路径必须从数据库行重新
构建新的 Artifact，不能把之前的计数或 Digest 当作证明。

### 4.2 原子发布 Repository 操作

增加窄范围的 Knowledge 内部操作：

1. 锁定带 Scope 的 Build，随后锁定其带 Scope 的 Index；
2. Build 仍处于已持有 Lease 的 `VALIDATING` 时持久化最终 Artifact Digest；
3. 插入或读取确定性 Version；
4. 将 Build 变为 `READY / COMPLETE` 并关联 Version；
5. 更新 Index 的 `latest_ready_version_id`、`version_count` 与 `metadata_version`。

Service 在一个 Spring `REQUIRED` 事务中协调这些操作，并在提交前调用 Governance 公开 Audit
Facade。不得创建隐藏跨模块表写入的 Repository 方法。

### 4.3 V11 对 Lock Version 的必然影响

V11 要求 Version Insert Trigger 执行前 `Build.artifact_digest` 已存在。因此一次成功发布事务
必须有意执行两次受 Lease Fence 保护的 Build Mutation：

1. `VALIDATING -> VALIDATING`：设置 `artifact_digest`，`lock_version` 增加一次；
2. `VALIDATING -> READY / COMPLETE`：设置 `published_version_id`、最终计数与时间，
   `lock_version` 再增加一次。

Version Insert 与两次递增都在同一事务中。Insert、Index Update 或 Audit 任一失败都会回滚
全部变化。测试必须断言这一准确行为，防止未来所谓“优化”破坏数据库发布守卫。

## 5. 规范 Validation Manifest

验证必须从不可变行重建被选择的 Artifact，绝不能依赖可变计数。任何一条规则不成立都必须
拒绝 Build。

### 5.1 Scope 与冻结 Source Set

- 每一行都属于同一 Tenant、Workspace、Knowledge Base、Index 与 Build；
- Build Revision 数量等于 `requested_source_count`；
- `source_set_ordinal` 唯一，并从 0 连续；
- 每个 Revision 与其冻结 Source Revision、Content Digest、Parser Version、Chunker
  Version 完全一致；
- 使用已批准 P2.2d 算法重新计算的 Source Set Digest 等于 `Build.source_set_digest`；
- 当前 Source 的 Active/Tombstoned 状态和“最新 Revision”不改变冻结 Snapshot。

Source 可以在 Build 创建后被 Tombstone，而不破坏旧的不可变 Snapshot。验证检查固定证据，
不检查当前是否仍可发现。

### 5.2 Document 与 Chunk

- 每个被选择 Chunk 只属于一个被选择 Source Revision 与一个现存 Document；
- Revision 内 Document Ordinal 稳定；
- Chunk Ordinal 与 ID 能重现已批准规范顺序；
- 规范 Chunk 数量等于 `requested_chunk_count`；
- 不缺失任何被选择 Chunk，也不引入任何未选择 Chunk；
- 使用已批准 Content Digest 算法，从存储的规范化文本重新计算 Chunk Content Digest，
  并要求与存储值相等。

### 5.3 Entry

- 每个规范 Chunk 恰好有一个 Entry，且不存在额外 Entry；
- Entry Ordinal 唯一、连续，并等于规范 Chunk Ordinal；
- 稳定 Entry ID 等于根据 Build ID 与 Chunk ID 派生的 Domain-separated 确定性 ID；
- Source、Revision、Document、Chunk、Knowledge Base、Index、Build 血缘完全准确；
- `normalized_input_digest` 等于准确 Chunk Content Digest；
- Entry Route ID/Reference 等于 Build Pin；
- Dimension 同时等于 Build Pin 和 Vector 长度；
- 每个浮点数都为有限值，且 Vector Norm 非零，符合 ADR-0006 的要求；
- 根据 IEEE-754 float32 Bit、按 Big-endian 顺序重新计算 Vector Digest，并要求等于存储值；
- Batch Ordinal 非负，且与 P2.2d-3 的持久 Batch 分组一致。

固定的 Normalization Token 只作为 Route Profile Identity 验证。P2.2d-4 不得凭空增加已批准
Embedding Contract 中不存在的 Unit-length 容差。

### 5.4 Route Profile

Validator 比较 Build Pin 与 Provider-neutral Route Snapshot：

- Tenant/Workspace 与 Route ID/Reference；
- Vector Dimension；
- Maximum Input Tokens；
- Maximum Batch Size；
- Normalization。

当前 Provider Readiness 或 Enabled 状态不使已完成 Embedding 的 Artifact 失效。验证读取
`EmbeddingRouteCatalog`，不调用强制可用性的 Execution Facade。发布不调用 Provider，也不
解析 “latest”。Route 记录缺失或身份改变时必须 Fail Closed，因为此时不能再证明固定
Artifact。

## 6. Digest 规范

新增 Manifest Digest 复用现有 Knowledge Digest Encoding：Domain-separated、
Length-prefixed SHA-256。

```text
string:  4-byte signed non-negative big-endian byte length + UTF-8 bytes
UUID:    4-byte length (16) + two big-endian 64-bit components
integer: 4-byte length (4) + one big-endian 32-bit value
```

Enum 使用已批准准确 Token。Optional Value 使用显式带类型 Presence Marker，绝不能编码为空
字符串。Timestamp、数据库行顺序、JSON 序列化、Locale、Timezone 与 Process Identity 都不
参与。现有 Request 与 Source-set Digest Byte 不改变。

### 6.1 共享原语 Digest

- 规范化 Chunk Input：复用现有规范 Chunk Content Digest；
- Vector：对准确 IEEE-754 float32 Bit 按 Big-endian 顺序执行
  `sha256:<lowercase-hex>`；
- 确定性 ID：使用带 Domain Separation 的 SHA-256 UUID，并设置 RFC 4122 Version/Variant
  Bit，复用 P2.2d-3 算法。

P2.2d-3 私有的 Vector 与 Stable-ID Helper 移至唯一 Knowledge 内部规范 Utility；所有旧测试
继续作为兼容性 Fixture。

### 6.2 Validation Digest

Domain：`apvero-knowledge-index-validation-v1`。

Validation Manifest 固定以下内容：

- Build/Index/Knowledge Base 身份与 Requested Version；
- Source Set Digest 与固定 Route/Profile；
- 有序 Build Revision 身份及 Source 证据；
- 有序 Document/Chunk 身份、Ordinal 与 Content Digest；
- 有序 Entry 身份、Ordinal、血缘、Input Digest 与重新计算的 Vector Digest；
- 规范 Source/Chunk/Entry 数量。

INDEXING Step 持久化该 Digest 与 `validated_entry_count`。

### 6.3 Artifact Digest

Domain：`apvero-knowledge-index-artifact-v1`。

不可变 Artifact Digest 固定准确 Route/Profile、有序冻结 Source Snapshot、有序 Chunk
Identity/Content Digest、有序 Entry Identity/Vector Digest 与规范计数。Lease Owner、
Attempt、Validation Time、Audit ID 等运维状态不参与。

VALIDATING 重新计算两个 Digest。新 Validation Digest 必须等于 INDEXING 持久化值；任何差异
都是 Integrity Failure。随后将 Artifact Digest 冻结到 Build 与 Version。

### 6.4 确定性 Version 身份与 Reference

- Version ID 使用现有 SHA-256 确定性 UUID 算法，Domain 为
  `apvero:knowledge-index-version:<build-id>`；
- Version String 等于 Build 的准确 `requested_version`；
- Reference 是已接受契约要求的规范语义引用
  `<index-slug>@<requested-version>`，绝不是 Provider Resource 或 `latest`；
- `published_at` 来自 PostgreSQL `transaction_timestamp()`，不使用 Java Clock。

Equal Replay 必须比较每个持久化 Version Field 与 Digest。仅 ID 相等绝不足够。

## 7. INDEXING 单次 Claim 流程

新增一个包内 INDEXING Orchestrator：处理一个已经 Claim 的 Build，并返回一个有界 Typed
Outcome。它不是 Scheduler。

```text
PREP  重建并验证完整不可变 Artifact；无 Provider I/O
TX-A  锁定并要求准确有效的 INDEXING Lease/Version
      重新检查 Build 身份与持久化证据
      持久化 Validated Count/Digest
      进入 VALIDATING 并释放 Lease
```

EMBEDDING 之后已经禁止 Entry Insert，Revision/Document/Chunk 也不可变，因此 PREP 可以在不
持有行锁时执行 O(entries) 扫描。TX-A 仍必须使用数据库时间、Expected Owner、Expected
`lock_version`、准确 Status/Step。过期 Worker 无法持久化 Digest。

Validation Failure 使用现有 d-2 Failure Kernel 和稳定有界 Category。结构或 Digest 损坏不可
重试；临时数据库不可用可进入现有 Retry Policy。任何失败路径都不得伪造或删除 Entry 证据。

## 8. VALIDATING 与原子发布

新增一个包内 Publication Coordinator，处理一个已经 Claim 的 VALIDATING Build。

### 8.1 事务顺序

一个 Spring `REQUIRED` 事务执行：

1. 使用 `FOR UPDATE` 锁定带 Scope 的 Build；
2. 按这个稳定顺序使用 `FOR UPDATE` 锁定其带 Scope 的 Index；
3. 若 Build 已经 READY，进入 8.3 节 Equal Replay 检查；
4. 否则要求 `VALIDATING / VALIDATING`、Expected Lease Owner、Expected Lock Version，
   且 `lease_until > transaction_timestamp()`；
5. 要求 Index 仍为 `ACTIVE`；
6. 要求 Index 的 `version_count` 与 Current Pointer 匹配其现有 Scoped READY Versions；
7. 在事务内重新加载并完整验证所有发布关键行；
8. 要求重新计算的 Validation Digest 等于持久化的 INDEXING Digest；
9. 设置最终 `artifact_digest`，Build Lock Version 增加一次；
10. 插入确定性、不可变 READY Version；
11. 将 Build 置为 `READY / COMPLETE`，关联 Version，设置规范计数与数据库完成时间，清除
    Lease/Error/Retry 状态，并再次增加 Lock Version；
12. 使用已锁定 Index 行更新 Pointer/Count/Metadata Version；
13. 通过 `AuditEventCatalog` 追加 `knowledge.index-version.published`；
14. 提交。

事务内完整验证有意保持 O(entries)。P2 优先保证发布正确性，而不是缩短锁时间。P2.2d-5
测量并记录支持的 Corpus 与 Transaction Envelope；实现不得宣称任意规模。

### 8.2 Audit

Publication Audit 使用：

- Actor：`apvero-index-build-runner`；
- Action：`knowledge.index-version.published`；
- Resource Type：`knowledge-index-version`；
- Resource ID：确定性 Version ID；
- Outcome：`SUCCEEDED`；
- Source IP：空；
- Trace：根据 Build ID 派生的有界确定性 Trace。

`AuditEventCatalog.append` 使用普通 `REQUIRED` Propagation 加入发布事务。Audit Exception
必须中止 Version、Build、Index Mutation。高频验证进度不得写入管理 Audit Ledger。

### 8.3 Equal Replay 与双 Publisher

若提交后响应丢失，或者第二个 Publisher 等待 Build Lock：

1. 重新加载 READY Build、关联 Version 与 Index；
2. 重新计算，或使用调用方刚完成计算的完整 Artifact；
3. 比较每个 Version Identity、Scope、Build/Index Link、Requested Version、Reference、
   Route/Profile、Count、Status 与 Artifact Digest；
4. 要求 Build Link/Digest/Count 自洽；
5. 要求 Index Count 等于其 Scoped Version Row 数，Current Pointer 指向一个 Scoped READY
   Version；较新的合法 Publication 可以成为 Current Pointer；
6. 返回现有 Version，不再产生 Mutation 或 Audit Event。

任何差异都返回 `APVERO_KNOWLEDGE_PUBLICATION_CONFLICT`。禁止第二个 Version、重复 Audit
Event、重复计数或 Pointer Rewrite。

Index “latest” Pointer 按成功发布事务的串行 Commit 顺序变化。它仅为展示元数据，绝不能
用于 Runtime 或 Release 解析；后者必须使用准确 Version Identity。

## 9. 失败、重试与 Crash 矩阵

| 边界或证据 | 要求结果 |
|---|---|
| Revision、Chunk 或 Entry 缺失/额外/重复 | 不可重试验证失败；不产生 Version |
| Scope 或血缘错误 | 使用同一 Scoped Integrity Family Fail Closed |
| Route/Profile/Dimension/Input/Vector 不匹配 | 不可重试 Integrity Failure |
| Vector 非有限或 Norm 为零 | 不可重试 Vector Integrity Failure |
| 两个 Step 之间 Validation Digest 变化 | 失败；不得覆盖旧证据 |
| Mutation 前 Lease 过期 | 旧 Worker 不做持久化变更 |
| INDEXING Transition Commit 前 Crash | Build 仍为 INDEXING；可安全重算 |
| INDEXING Transition 已 Commit 但响应丢失 | Build 为 VALIDATING；下次 Claim 继续 |
| Publication Transaction 前 Crash | Build 仍为 VALIDATING |
| Artifact Update 后、Commit 前失败 | Artifact 与 Lock Increment 全部回滚 |
| Version Insert 或 Index Update 失败 | 整个 Publication 回滚 |
| Governance Audit 失败 | 整个 Publication 回滚 |
| Publication Commit 后响应丢失 | Equal Replay 返回已关联 Version |
| 两个 Publisher 竞争 | 一个 Commit；相等 Loser 返回现有；不等则 Conflict |
| Publication 前 Index 已 Archive | Fail Closed；不得向 Archived Index 发布 |

测试必须在每条 Publication Statement 后注入事务回滚，不能只测 Service Method 前后。

## 10. 稳定错误与有界 Outcome

复用现有 Scoped Not Found、Illegal Transition、Stale Lease 与 Concurrent Modification
Family。仅在现有词汇无法区分时增加窄范围内部稳定码：

- Artifact Membership/Cardinality Integrity；
- Ordinal/Lineage Integrity；
- Input/Vector Digest Integrity；
- Route/Profile Integrity；
- Validation Digest Drift；
- Archived Index Publication；
- `APVERO_KNOWLEDGE_PUBLICATION_CONFLICT`；
- Audit 导致的 Publication Rollback。

Exception 与 Typed Outcome 只包含有界 Category。Telemetry Label 绝不包含 Source Text、
Vector、URL、Provider Body、Secret Reference、Tenant/Workspace/Build/Chunk ID 或原始数据库
错误。

## 11. 安全与 Tenant Isolation

- 每个 Repository Predicate 都包含 Tenant 与 Workspace Scope；
- Build 与 Index Lock 都必须先 Scoped，不能提前暴露存在性；
- 所有 Revision、Document、Chunk、Entry、Version 行必须匹配 Scoped Aggregate；
- 跨 Workspace ID 与不存在记录使用同一 Not-found/Integrity 行为；
- 发布不使用 Provider Credential、Base URL、Network Call 或可变 Provider Resource；
- Vector 与 Source Content 只在本地验证，绝不写入日志或 Audit Metadata；
- 因未暴露新公开操作，Authorization 保持不变；
- 不可变 READY Version 与终态 Build 继续由数据库守卫。

## 12. 验证计划

### 12.1 Unit 与 Property Test

- Repository 行随机打乱后，规范顺序保持相同；
- Length-prefix Encoding 能区分容易产生拼接歧义的 Field；
- Digest 在不同 Locale、Timezone、Default Charset 下保持稳定；
- Vector Digest Fixture 证明 `-0.0`、NaN 拒绝、Infinity、float32 Bit 与 Big-endian 顺序；
- 确定性 ID/Reference Fixture 在不同 JVM Run 间稳定；
- 每一种 Membership、Lineage、Ordinal、Route、Count、Digest 损坏都失败；
- INDEXING 绝不只信任存储计数。

### 12.2 PostgreSQL/Testcontainers Test

- 完整 Artifact 在 Lease Fence 下从 INDEXING 进入 VALIDATING；
- 过期/失效 Lease 无法写入 Validation Evidence；
- Publication 原子持久化 Version、Build、Index 与 Audit；
- V11 下，一次成功 Publication 恰好发生两次 Build Lock Version Increment；
- 每个 Mutation 后的 Failure Injection 都使整个事务回滚；
- Version 行与 READY Build 保持不可变；
- 延迟 Entry Insert 无法与 Publication 竞争；
- Equal Replay 只返回一个 Version；冲突 Replay 失败；
- 双 Publisher 与双 Index Publication 竞争遵循稳定 Lock Order；
- Archived Index、Cross-workspace Row、Composite Key 不匹配全部 Fail Closed；
- 无新 Migration 的前提下，V11 Clean Install 与 V10-to-V11 Upgrade 继续通过。

### 12.3 Architecture 与累计回归

- Spring Modulith 与 ArchUnit 保持 Knowledge 的允许依赖；
- Knowledge 不导入 Governance Internal 或 Provider SDK 类型；
- P1 CHAT、P2.1 Ingestion、P2.2a/b/c、P2.2d-1/2/3 测试继续通过；
- OpenAPI 仍只暴露已接受的 Live Build 操作；Version List 继续为 `contract-only`；
- Java Unit/Integration Test、Formatting、`bootJar`、Compose Health、
  Security/Dependency Check 通过；
- 前端与 Python 不变，但累计检查继续通过。

P2.2d-4 增加聚焦的实施测试。匹配的中英文最终 Verification Evidence 属于 P2.2d-5。

## 13. 实施检查点

1. **d4.1 — Canonical Manifest 与 Digest Primitive**
   - 集中 Stable ID/Vector Digest 兼容算法；
   - 实现完整 Artifact Reconstruction 与纯 Corruption Test。
2. **d4.2 — INDEXING Validation Transition**
   - 增加单次 Claim INDEXING Coordinator；
   - 通过现有 Lease Fence 持久化 Validation Count/Digest。
3. **d4.3 — Atomic Publication Transaction**
   - 增加 Locked Repository Operation、确定性 Version 与 Governance Audit；
   - 证明 Build/Version/Index/Audit 的 All-or-nothing 行为。
4. **d4.4 — Recovery、Concurrency 与累计 Gate**
   - 证明 Response-loss Replay、双 Publisher 竞争、Archived Index、Scope Isolation 与每个
     Publication Rollback Boundary；
   - 运行累计 Architecture 与 Regression Verification。

这些是同一个 P2.2d-4 实施 Branch/PR 内的完整检查点，不是独立产品阶段，也不授权启用
P2.2d-5。

## 14. 发布与回滚

- `APVERO_KNOWLEDGE_ENABLED=false` 继续作为外层 Fail-closed 默认值；
- 独立 Build Runner 继续禁用，因此不会自动开始 Claim；
- 第一个 READY Version 产生前，可回滚到此前兼容 Binary，同时保留 V10/V11 行；
- READY Version 存在后，必须遵循 ADR-0006 的 P2-compatible Rollback Floor；
- 回滚绝不删除或改写 Build Revision、Entry、Version、Index Pointer、Audit Evidence；
- 禁用 Knowledge 时，未来固定的 RAG Execution 必须明确失败，绝不能回退到 Ungrounded
  Chat。

## 15. 自我批判与拒绝的捷径

1. Publication Transaction 内完整验证会增加锁持有时间。把所有检查移到事务外会削弱原子
   证明；P2 接受有界 O(entries) 成本，并在 d-5 测量支持范围。
2. PostgreSQL Guard 无法独立重新计算所有 Java Digest。因此实现必须同时具备应用层验证和
   关系型 Transition/Immutability Guard。
3. 一个事务中两次 Lock Version Increment 不如一次直观，但 V11 Version Insert Guard 要求
   如此；拒绝隐藏第一次 Update 或削弱 Trigger。
4. `latest_ready_version_id` 使用方便，但若被当作 Runtime Resolution 会很危险。它只保留为
   展示元数据，准确 Version Pin 仍是强制要求。
5. READY 只证明结构完整与可复现，不证明检索相关性。P2.2e 前不得宣称语义质量。
6. 当前 Route Readiness 与当前 Source Status 有意不参与发布。要求二者会让冻结 Artifact
   依赖可变外部状态。
7. Digest 是 Integrity Evidence，不是 Authorization 或 Encryption。Scope Check、Policy 与
   不可变行仍然必需。
8. 不新增 Attempt History、Manifest Blob 或 Publication Outbox 表。现有 Typed Build、
   Version、Entry 与 Audit Evidence 已足够支持此有界阶段；没有被证明的缺口就增加存储，会
   削弱 Self-hosted Baseline。

明确拒绝的捷径包括：信任计数、发布部分 Entry、解析 `latest`、改写相等 Version、Commit 后
再 Audit、验证期间执行 Provider I/O、跨模块 SQL、提前上线 Version API，以及为了模仿大型
平台而增加基础设施。

## 16. 批准门

维护者批准本计划，只授权上述 P2.2d-4 实施。不授权 P2.2d-5 Runner Activation/Operations、
P2.2e Retrieval Lab、前端上线、Application/Release/Run 变更、新 Migration/Table/
Dependency/Deployable 或公开契约变更。
