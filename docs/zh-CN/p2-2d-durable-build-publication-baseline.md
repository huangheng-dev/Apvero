# P2.2d 持久化 Build 与原子发布——实施基线

状态：实施候选；开始业务编码前需要维护者批准

目标：P2 / P2.2d

权威来源：ADR-0006、`architecture/invariants.yaml`、`architecture/delivery-stages.yaml`、
`architecture/modules.yaml`、`architecture/dependency-rules.yaml`，以及
`contracts/openapi/platform-api.yaml` 中现有的 Contract-only Knowledge Build Operation。

## 1. 交付结果

P2.2d 把 P2.2c Embedding Primitive 变成一条持久化内部工作流：

```text
创建 Build
  -> 固定准确 Source Revision
  -> 租用一个持久步骤
  -> 嵌入完整确定性批次
  -> 验证完整 Entry Manifest
  -> 验证全部发布不变量
  -> 原子发布一个不可变 Index Version
  -> 暴露持久化 Build 状态
```

完成意味着：进程可以在每个持久边界崩溃，而不会发布部分索引、丢失选定 Source Set、产生
重复 Entry 或重复计费，也不会让过期 Worker 覆盖更新状态。

P2.2d 不实现 Retrieval Lab、Application Binding、ReleaseBundle 1.1、Grounded Run、
前端启用、ANN/Hybrid Retrieval、队列、新 Deployable 或新的有状态依赖。

## 2. 架构决策检查

不需要新 ADR。ADR-0006 已经授权：

- Knowledge 所属的 Index Build 与 Immutable Index Version 生命周期；
- PostgreSQL Lease 与 Additive Migration；
- 受治理的 Spring AI Embedding；
- 原子发布与双语 Build API。

实现严格位于该决策范围：

| 关注点 | 所有者 | 允许依赖 |
|---|---|---|
| Build、Source Snapshot、Entry Manifest、Version Publication | Knowledge | — |
| Workspace Scope 与后台 Workspace 枚举 | Identity | Knowledge → Identity |
| Route 解析、Quote 与 Embedding 执行 | Capability Registry | Knowledge → Capability Registry |
| Admission、Component Recovery、Settlement 与 Audit | Governance | Knowledge → Governance |

Knowledge 不得依赖 Application、Release 或 Runtime，不得读取其他模块的数据表。Provider
SDK Type 继续限制在已批准 Adapter Package。

P2.2d 为崩溃恢复增加一个 Provider-neutral Governance 读取接缝：通过 Reservation ID 与
确定性 Component Identity 查询 Component Snapshot。这属于 ADR-0006 已批准的 Component
Ledger 窄扩展；它不是 REST Endpoint，也不暴露 Provider Body 或 Secret。

## 3. 公开界面

下列现有 Contract-only Operation 只有在实现与证据通过后才转为 Live：

- `GET /api/v1/knowledge-indexes/{indexId}/builds`
- `POST /api/v1/knowledge-indexes/{indexId}/builds`
- `GET /api/v1/knowledge-index-builds/{buildId}`
- `POST /api/v1/knowledge-index-builds/{buildId}/retry`
- `POST /api/v1/knowledge-index-builds/{buildId}/cancel`

P2.2d 不改变 Path、请求字段或响应语义。只有 Conformance、Authorization、Telemetry 与
失败测试通过后，才能逐个移除 Implementation Status。Index Version List 在 P2.2e 提供
读取工作流前继续保持 Contract-only。

Knowledge 所属的公开 Java Contract：

- `KnowledgeIndexBuildCatalog`；
- `CreateKnowledgeIndexBuildCommand`；
- 不可变 `KnowledgeIndexBuild` Projection；
- 与持久化生命周期一致的 Status 与 Step Enum。

Command 接收 `KnowledgeCommandContext`；Actor、Trace、Source IP 在 Audit 前有边界。
Backend Failure 使用稳定 Code，由 Client 本地化消息。

## 4. Build 创建与规范身份

Build 创建使用一个事务：

1. 要求 Knowledge 已启用并取得完整授权 `WorkspaceScope`；
2. 锁定 Scope 内的 Active Index；
3. 解析准确 EMBEDDING Route 并复制其不可变 Reference/Profile；
4. 通过 Knowledge Repository 加载全部请求的 Source Revision；
5. 要求相同 Knowledge Base、Active Source、已快照 Revision、终态 READY Ingestion Job、
   至少一个 Document 与至少一个 Chunk；
6. 拒绝重复 Source 或 Revision Identity；
7. 按 Source UUID、Revision UUID 排序并分配 Source-set Ordinal；
8. 计算准确 Source/Chunk Count 与规范 Source-set Digest；
9. 计算规范 Build Request Digest；
10. 插入 Build 与全部 Build Revision Row；
11. 追加 `knowledge.index-build.requested`；
12. 提交。

Request Digest 对以下 Length-prefixed UTF-8/Binary Field 做 SHA-256：

- Tenant、Workspace、Index、Knowledge Base ID；
- 请求的 Semantic Version；
- 准确 Route ID、Reference、Dimension、Input Limit、Batch Limit、Normalization；
- 每个有序 Source、Revision、Content Digest、Parser Version、Chunker Version、Chunk Count。

`(knowledge_index_id, requested_version)` 是公开幂等身份。相同请求返回现有 Build；同一
Version 的不同 Canonical Digest 返回 `APVERO_KNOWLEDGE_BUILD_VERSION_CONFLICT`。
Uniqueness Race 必须捕获后在 Scope 内重新读取，并使用相同等价规则处理。

之后禁止查询 Source 的 Latest Revision。

## 5. 持久化状态机

```text
QUEUED / EMBEDDING
  -> EMBEDDING / EMBEDDING
  -> INDEXING / INDEXING
  -> VALIDATING / VALIDATING
  -> READY / COMPLETE

active step -> RETRY_WAIT / same step -> matching active status
active step -> FAILED / same step
FAILED retryable --manual retry--> RETRY_WAIT / same step
QUEUED or RETRY_WAIT -> CANCELLED / retained step
ambiguous dispatch -> FAILED / EMBEDDING / reconciliation required
```

只允许上述转换和幂等的同状态 Lease/Progress 更新。`READY` 不可变；Counter 单调递增，
`validated_entry_count <= embedded_entry_count`；自动重试期间 Attempt 不超过配置上限；
每次成功 Mutation 都增加 `lock_version`。

自动重试进入 `RETRY_WAIT`，使用确定性、有上限的指数 Backoff。`FAILED` 记录一次已完成
Attempt。Manual Retry 只允许 `retryable=true`，重置自动 Attempt Window、保留持久 Step
并写 Audit。Audit History 是持久 Manual-retry History，Log 不是。

只允许取消没有 Lease 的 `QUEUED` 或 `RETRY_WAIT`。活动 Provider Call 不能被虚假报告为
已取消。

## 6. Lease 与过期 Worker 规则

Build Runner 复用已验证的 P2.1 PostgreSQL Pattern，但使用独立的有界配置
`apvero.knowledge.index-build-runner.*`。

- 通过 Identity 枚举允许后台处理的 Workspace；
- 使用 `FOR UPDATE SKIP LOCKED` 领取少量有 Scope 的 Build；
- 工作前持久化 Owner、Expiry、Attempt 与 Active Status；
- Database Transaction 打开时不执行 Network Call；
- 每次 Claimed Task 最多执行一个外部 Embedding Batch；
- 每次 Mutation 都要求 `workspace + build + lease owner + lock version + unexpired lease`；
- 一个持久单元后释放 Lease，保持不同 Build 的公平性；
- Provider Timeout 必须短于 Lease Duration，并保留已记录的 Commit Margin；
- Shutdown 时停止新 Claim，并有界 Drain In-flight Work。

过期 Worker 可以完成本地计算，但不能 Persist、Settle 或 Advance。只有 Compare-and-set
证明没有后继者领取时才能 Renew。Lease Expiry 本身不能证明 External Call 是否发生。

## 7. EMBEDDING 步骤

每次 Claim：

1. 重新加载准确 Build、Source Snapshot、Route Profile 与有序 Missing Entry；
2. 在 Item 与估算 Unit 限制内选择下一个确定性 Batch；
3. 推导 P2.2c Stable Batch/Component Identity；
4. 重新 Admit 相同 `KNOWLEDGE_INGESTION / EMBEDDING_INDEX` Reservation；
5. 读取 Governance Component Snapshot；
6. 应用已批准的 P2.2c Recovery Decision；
7. 允许时在调用 `EmbeddingCapability.embed` 前标记 Dispatched；
8. 完整验证有序 Result；
9. 重新验证 Lease Ownership；
10. 原子持久化完整 Entry Batch；
11. Settle 相同 Component；
12. 更新持久 Build Progress 并释放 Lease。

Recovery Action 保持为：

`ADMIT`、`DISPATCH`、`REPLAY`、`RECONCILE`、`SETTLE_ONLY`、`COMPLETE`、
`INTEGRITY_FAILURE`、`LEDGER_ARTIFACT_INCONSISTENCY`。

Runner 不从 Log 推测 Component State。未解决的 Dispatched Component 只有在 Adapter
声明 `SAFE_REPLAY` 时才能 Replay；否则 Build 进入 `FAILED` 且
`reconciliation_required=true`。

全部选定 Chunk 都有一个自洽 Entry 后，通过带 Lease 的 Compare-and-set 把 Build 转到
`INDEXING`；此后禁止插入 Entry。

## 8. INDEXING 与 VALIDATING 步骤

`INDEXING` 不执行 Provider I/O。它验证：

- 准确 Build Revision Membership 与 Source-set Digest；
- 每个选定 Chunk 恰好一个 Entry，且没有额外 Entry；
- 稳定 Source/Document/Chunk/Entry Ordinal；
- 准确 Route Reference/Profile 与 Vector Dimension；
- 重新计算的 Normalized-input 与 IEEE-754 Float32 Vector Digest；
- Finite、Non-zero Vector 与准确 Lineage。

它计算规范 Validation Manifest/Digest，持久化 Validated Count，并通过 Lease/Version
Compare-and-set 进入 `VALIDATING`。

`VALIDATING` 重新加载并重新计算全部发布关键证据，不能只信任 Counter 或上一步 Digest。

Artifact Digest 是对准确 Route/Profile、有序 Source Snapshot、有序 Chunk Identity/
Content Digest、有序 Entry Identity/Vector Digest 与规范 Count 做 Length-prefixed
SHA-256。JSON Ordering、Default Charset、Locale、Timezone 均不能影响结果。

## 9. 原子发布事务

发布使用一个短事务：

1. 按稳定顺序锁定 Scope 内 Build 与 Index；
2. 要求 `VALIDATING`、预期未过期 Lease Owner 与 Lock Version；
3. 重新执行 Cardinality、Membership、Lineage、Digest、Route 与 Vector-shape Check；
4. 持久化最终 Validation 与 Artifact Digest；
5. 插入一个确定性的 Immutable Index Version；
6. 把 Build 设为 `READY / COMPLETE`，关联 Version、完成 Count/Time 并清除 Lease；
7. 更新 Index `latest_ready_version_id`、Version Count 与 Metadata Version；
8. 通过 Governance Public Audit Interface 追加 `knowledge.index-version.published`；
9. 提交。

任何失败（包括 Audit Failure）都回滚 Version、Build、Index 变化。Deterministic Version
ID 来自 Build Identity；相同 Replay 只有在全部 Field/Digest 相等时返回已发布 Version，
否则返回 `APVERO_KNOWLEDGE_PUBLICATION_CONFLICT`。

Retrieval 不能使用 Build ID，任何 Partial Artifact 都不会成为 READY Version。

## 10. V11 数据库加固

P2.2d 需要一个 Forward Flyway Migration，不增加 Table 或有状态依赖。

V11 必须：

- 只允许 Scope 内未发布且处于 `EMBEDDING / EMBEDDING` 的 Build 插入 Entry；
- 串行化 Entry Insert 与 Build Step Transition，防止 Late Insert 在进入 `INDEXING` 后提交；
- 强制已批准 Build Transition Matrix 与 Terminal Immutability；
- 在 SQL Constraint 可表达范围内强制 Progress、Attempt、Lock Version 单调；
- 只允许 Scope 内 `VALIDATING` 且未发布的 Build 插入 Version；
- 拒绝第二次或不一致 Publication；
- 保留 V10 数据并提供 Forward Mitigation/Rollback 文档。

Clean Install 与 V10-to-V11 Upgrade Test 必须验证这些 Guard。上一版 Binary 可以忽略强化
Database Guard；一旦存在 READY Version，Rollback 必须遵循 ADR-0006 的 P2-compatible Floor。

## 11. 崩溃与并发矩阵

| 边界 | 持久证据 | 必需恢复 |
|---|---|---|
| Build Commit 前 | 无 | Client 安全重试 Create |
| Build 已提交、未 Claim | `QUEUED` | 正常 Claim |
| Claim 已提交、未 Admission | Active Lease | Expire/Reclaim 后 `ADMIT` |
| Reservation 已提交、未 Dispatch | Component `RESERVED` | `DISPATCH` |
| Dispatch 已提交、无持久 Result | Component `DISPATCHED` | Safe Replay 或 Reconciliation |
| Entry 已提交、未 Settlement | Complete-equal Entry Batch | `SETTLE_ONLY` |
| Settlement 已提交、未更新 Progress | Succeeded Component + Complete Entry | 不再调用，直接 Advance |
| Partial/Conflicting Entry Batch | Inconsistent Artifact | Integrity Failure，不能绕开补齐 |
| INDEXING Digest 已提交、未 Transition | Persisted Digest | 重算并幂等 Advance |
| Version Insert Transaction 回滚 | 无 Version，Build 非 READY | 重试 Validation/Publication |
| Publication 提交、Response 丢失 | READY Build + Equal Version | 返回现有 Version |
| Old Worker 返回时 Lease 已过期 | Successor/Expired Lease | Stale Worker 不能 Mutation |
| 两个 Publisher 竞争 | Build/Index Lock + Unique Version | 一个提交；等价 Loser 读取现有 |

测试必须在每个 Row 注入 Crash 或 Transaction Rollback，不能只测试最终状态。

## 12. 安全、错误与遥测

全部 REST Operation 要求现有认证 Workspace Header 与当前 Read/Write/Admin Policy。
Cross-workspace ID 返回相同 Scoped Not-found Family。正常响应不暴露 Base URL、Secret、
Provider Body、Vector、Source Text、Internal Lease Owner 或跨 Scope 存在性。

稳定 Error 覆盖 Disabled、Invalid Request、Scoped Not Found、Version Conflict、Ineligible
Source、Route/Profile/Readiness、Illegal Transition、Lease/Concurrent Modification、
Retry/Cancel Conflict、Admission Denial、Provider Failure/Ambiguity、Entry Integrity、
Publication Validation、Audit Failure。

Audit 覆盖 Build Request、Manual Retry/Cancel、Terminal Failure/Reconciliation、Publication。
Per-batch Progress 是 Typed State/Metric，不产生 Administrative Audit Spam。

Metric 覆盖 Queue Wait、Step Duration/Outcome、Attempt、Batch Item/Unit、Embedded/Validated
Count、Retry、Stale Lease Rejection、Recovery Action、Publication Validation/Outcome。
Label 只允许有界 Enum；禁止 Tenant、Workspace、Build、Route、Chunk、Request、URL、Content
Identity。

Health 报告 Feature Flag、Runner Accepting、In-flight Count、最老 Eligible Build Age、
Reconciliation Count，不探测付费 Provider。

## 13. 验证门禁

1. Spring Modulith/ArchUnit 保持全部 Module 与 Provider Adapter 边界。
2. OpenAPI Conformance 证明只有 5 个已验收 Build Operation 转为 Live。
3. V11 Clean Migration 与 V10 Upgrade Test 通过。
4. 双 Workspace API、Repository、Claim、Retry、Cancel、Entry、Publication 默认拒绝。
5. Canonical Request/Source/Artifact Digest 通过 Locale、Timezone、Ordering Variant。
6. 相同 Create/Publication Retry 幂等；不一致复用失败。
7. Lease Expiry、Stale Worker、Duplicate Claim、Two-publisher Race 确定性处理。
8. Crash Matrix 每一行都有持久化 Testcontainers 证据。
9. Admission Denial 发生在 Provider 调用前；Ambiguous Dispatch 不 Blind Retry。
10. Partial、Extra、Missing、Wrong-lineage、Wrong-dimension、Wrong-digest Entry 不能发布。
11. Audit Failure 回滚 Command/Publication Mutation。
12. Metric/Error 不包含高基数或敏感值。
13. P1 CHAT、P2.1 Ingestion、P2.2c Component/Entry 行为保持全绿。
14. Java、`bootJar`、Contract、Compose、Container、Security/Dependency Check 通过。
15. P2.2d-5 交付匹配的英文与简体中文 Evidence。

本切片不需要前端或 Python 变化。TypeScript/Playwright 与 Worker Test 继续在累计 CI 运行，
但 P2.2d 不把页面或 Worker Operation 标记为 Live。

## 14. 五个实施检查点

1. **P2.2d-1——Build API 与 Canonical Source Snapshot**
   - V11 Guard、Knowledge Public Build Contract、Create/List/Get/Retry/Cancel、Scope、Audit。
2. **P2.2d-2——Lease 与 Transition Kernel**
   - Claim/Reclaim、Compare-and-set Transition、Backoff、Cancellation、Stale-worker Test。
3. **P2.2d-3——受治理 Embedding Orchestration**
   - Governance Component Snapshot Seam、单 Batch Execution、Recovery Matrix Integration、
     Durable Progress。
4. **P2.2d-4——Validation 与 Atomic Publication**
   - Complete Manifest、Canonical Artifact Digest、Immutable Version Transaction、Race Test。
5. **P2.2d-5——Operations 与双语 Verification**
   - Metric、Health、Safe Error、Cumulative Regression、Compose Evidence、匹配 EN/zh-CN
     Acceptance Document。

每个检查点都是完整且已验证的 Implementation Commit。Planning 与 Implementation 使用独立
Branch/PR。任何检查点都不启用 P2.2e。

## 15. 发布、回滚与自我批判

- `APVERO_KNOWLEDGE_ENABLED=false` 继续作为外层 Fail-closed Default；
- Build Runner 有独立 Disable Switch，先停止新 Claim，再有界 Drain；
- Failed、Cancelled、Reconciliation-required Build 保持可检查；
- 自动 Cleanup 不得重写 Immutable Source、Entry、Version、Governance 或 Audit Evidence；
- READY Version 出现前可回滚到上一版兼容 Binary，并保留 V11；
- READY Version 出现后应用 ADR-0006 的 P2-compatible Rollback Floor。

明确限制：

1. 外部 Provider Call 无法与本地 Transaction 实现 Exactly-once；Reconciliation 是诚实的
   Terminal Outcome。
2. 每次 Claim 一个 External Batch 优先保证公平与恢复简单，而不是峰值吞吐。
3. PostgreSQL Polling 有明确边界，不声称具备 Queue-scale Throughput。
4. Audit Event 保留 Manual Retry History，但 P2.2d 不增加专门 Attempt-history Table。
5. 完整 Artifact Digest 是 O(entries)；Publication Correctness 优先，支持 Corpus Envelope
   在后续实测。
6. `latest_ready_version_id` 只用于展示，绝不能用于 Runtime Resolution。
7. READY Version 证明结构可复现，不证明语义检索质量；Retrieval Lab 证据属于 P2.2e。

## 16. 批准门禁

维护者批准只授权上述 P2.2d-1 至 P2.2d-5，不授权 P2.2e Retrieval Lab、P2.3
Application/Release/Run、前端启用、新 Table/Deployable、Kafka/Redis/MinIO/Milvus、
ANN/Hybrid Retrieval、第二套 AI Framework、Cross-module SQL 或 Mutable Published Index。
