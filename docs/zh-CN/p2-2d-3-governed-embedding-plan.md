# P2.2d-3 受治理的 Embedding 编排——实施计划

状态：实施候选；尚未开始业务编码

目标：P2 / P2.2d-3

权威来源：ADR-0006、已验收的 P2.2c Embedding 基线、已批准的 P2.2d 持久 Build
基线，以及已经实现的 P2.2d-2 租约/状态转换内核

推理级别：高

## 1. 结果

P2.2d-3 把一个已经取得租约的 `EMBEDDING` Build 接到 P2.2c 受治理批处理原语：

```text
已租赁 Build
  -> 按持久进度重建确定性批次
  -> 幂等准入完全相同的 Governance Component
  -> 检查持久 Component 与 Entry 证据
  -> 选择一个已批准的恢复动作
  -> 最多执行一次 Provider 调用
  -> 在租约栅栏后持久化一个完整 Entry 批次
  -> 结算同一个 Component
  -> 持久化进度并释放租约
```

只有每一个崩溃边界都能恢复，并且不会产生重复计费、部分 Entry 批次、伪造用量或
旧 Worker 写入，本切片才算完成。

本切片不启用调度器、公共 Build 操作、Retrieval Lab、发布、前端、Application
绑定、Release、Runtime、队列或新增基础设施。

## 2. 变更声明

| 项目 | 决策 |
|---|---|
| 阶段 | P2 / P2.2d-3，`in-progress` |
| 主模块 | `knowledge` |
| 支撑模块 | `governance`、现有 `capability-registry` API |
| 允许依赖 | Knowledge → Identity、Capability Registry、Governance |
| REST / OpenAPI / JSON Schema | 不变 |
| Java API | 新增一个厂商无关的 Governance Component 快照 |
| 数据库迁移 | 无；V10/V11 已包含所需持久证据 |
| 有状态依赖 / Deployable | 无 |
| AI 抽象 | 仅使用现有 Spring AI 2.0 Embedding Capability |
| 暴露状态 | 内部且禁用；不宣称产品功能已上线 |

已批准的 P2.2d 基线明确授权 Governance 快照接缝。本计划不改变不变量、模块边界、
Release 语义、安全策略或技术基线，因此不需要新 ADR。如果编码证明必须新增字段、
表、依赖或公共契约，则立即停止实现并返回架构审查。

## 3. 必须复用的现有接缝

- `KnowledgeIndexBuildTransitionKernel` 继续独占租约续期、进度、转换、重试和终态失败。
- `KnowledgeEmbeddingBatchExecutor` 继续独占 Source 重建、Route 校验、用量估算、稳定
  Component Identity、输出校验和 Entry 映射。
- `KnowledgeEmbeddingEntryBatchWriter` 继续作为唯一完整批次写入器。
- `EmbeddingCapability` 继续作为厂商无关的报价和执行边界。
- `ExecutionGovernance` 继续独占 Reservation/Component 生命周期。
- `KnowledgeEmbeddingRecoveryDecider` 保留且仅保留已经批准的八个动作。

不得新增第二套批处理算法、租约实现、Governance Facade 或 Provider Adapter。

## 4. 编码前必须修正的接缝

### 4.1 带作用域的 Component 快照

向 `ExecutionGovernance` 增加不可变 `ExecutionComponentSnapshot` 及带作用域查询。
快照只包含：

- Reservation 与 Component Identity；
- Component Type；
- 精确 Route ID/reference；
- 估算用量/费用和币种；
- 终态时的实际用量/费用与用量质量；
- 状态：`RESERVED`、`DISPATCHED`、`SUCCEEDED`、`FAILED` 或
  `RECONCILIATION_REQUIRED`；
- 可选 Provider Request Identity 与稳定失败码。

查询必须接收调用方 Workspace ID、Reservation ID 和确定性 Component Identity。
所有谓词都包含通过 Identity 解析出的 Tenant/Workspace 作用域。不存在与跨作用域记录
返回相同的空/未找到结果。不得暴露 Provider Body、Endpoint、Secret、Source Text 或 Vector。

### 4.2 事务参与方式

当前 P2 Component 重载使用 `REQUIRES_NEW`，导致 Knowledge 无法在持有并验证 Build
租约栅栏的同时提交 Governance Component 变更。

只把 P2 使用的 Component 生命周期重载改为标准 `REQUIRED` 参与方式：

- Component Reservation 准入；
- Dispatch 标记/补充；
- Component 结算；
- 标记需要人工对账；
- 读取 Component 快照。

单独调用时仍会开启一个事务；被 Knowledge 租约栅栏协调器调用时，则加入同一个短事务。
P1 单 CHAT 方法保持兼容。回归测试必须证明相同幂等、冲突行为与 P1 语义未改变。

### 4.3 带租约栅栏的 Entry 持久化

现有 Entry Writer 会锁 Build，但不会证明预期 Lease Owner、Lock Version 和数据库时间
下的租约未过期。新增一个接收精确 Claim Build 与 Owner 的带栅栏写入路径。在同一事务中：

1. 锁定带作用域的 Build；
2. 使用数据库时间验证 `EMBEDDING / EMBEDDING`、预期 Owner、预期 Lock Version 和
   未过期租约；
3. 校验完整预期批次；
4. 插入全部 Entries，或接受一个完全相同的完整批次；
5. 拒绝部分、冲突或旧 Worker 写入。

d-3 生产路径不得调用无租约栅栏的 Writer。

d-2 Kernel 还要新增一个不修改状态的内部租约断言。底层 Repository Query 锁定带作用域
Build，并要求预期 Owner、Lock Version、State 以及
`lease_until > current_timestamp`。d-3 的每个持久阶段都使用该断言；Java Wall Clock
比较不能作为租约有效性的权威。

### 4.4 恢复优先级修正

已实现的 P2.2c Decider 当前会让 `FAILED` 或 `RECONCILIATION_REQUIRED` 加相同 Entries
落入 `SETTLE_ONLY`。失败终态 Component 不能被成功覆盖，因此 d-3 在编排前修正该边界：

- Partial Entries 始终产生 `INTEGRITY_FAILURE`；
- 非终态 Component 加不同的完整 Entries 产生 `INTEGRITY_FAILURE`；
- `SUCCEEDED` 加相同完整 Entries 产生 `COMPLETE`；
- `FAILED` 或 `RECONCILIATION_REQUIRED` 加相同完整 Entries 产生
  `LEDGER_ARTIFACT_INCONSISTENCY`；
- 其他终态缺失/不同证据产生 `LEDGER_ARTIFACT_INCONSISTENCY`。

动作词汇不变。穷举真值表测试防止 Decider 中的判断顺序再次悄悄改变该优先级。

## 5. 确定性批次重建

持久 Cursor 是 `Build.embeddedEntryCount`，不是“当前第一个缺失的 Entry”。只有这样，
才能恢复 Entries 已提交但结算/进度尚未提交的窗口。

对每个已领取 Build：

1. 重载精确 Build、Source Revisions 和规范排序后的 Chunks；
2. 从 `embeddedEntryCount` 开始；
3. 在已固定的项目数与估算用量限制内选择连续 Chunks；
4. 如果第一个剩余 Chunk 单独超限，在准入前失败；
5. 使用第一个选中 Entry Ordinal 作为 `batchOrdinal`；
6. 使用 Build、Batch Ordinal、Route 及有序 `(Chunk ID, Content Digest)` Manifest
   派生现有 P2.2c Identity；
7. 拒绝空洞、乱序 Entry，或不属于这个精确重建批次却位于持久 Cursor 之后的 Entry。

相同 Build 状态必须在不同 Locale、Timezone、进程、Worker、数据库返回顺序和重试次数下
重建出同一个批次。

当 `embeddedEntryCount == requestedChunkCount` 时，不准入 Component，也不调用 Provider；
通过现有 d-2 Kernel 把已租赁 Build 转入 `INDEXING`。

## 6. 稳定 Reservation Identity

每个批次使用：

- Subject：`KNOWLEDGE_INGESTION`，Subject ID = Build ID；
- Component：`EMBEDDING_INDEX`；
- Component Idempotency：现有 `knowledge-embedding:<sha256>`；
- Actor：固定且有长度限制的内部 Actor `apvero-index-build-runner`；
- Trace：由 Build ID 与 Batch Ordinal 派生的确定性有界 Identity；
- 精确固定的 Route、Quote、Units、Cost 和 Currency。

Actor 与 Trace 必须确定，因为 Governance 在重复准入时会比较它们。不得用随机 Request
Identity 让一次重试与自身 Reservation 冲突。

## 7. 单次 Claim 编排

新增一个包内 `KnowledgeIndexBuildEmbeddingOrchestrator`。它处理一个已经取得租约的
Build，并返回一个有类型、有界的 Outcome；它不是轮询循环或调度器。

### 7.1 短持久阶段

```text
PREP  只读确定性重建与报价；不锁 Build Row
TX-A  租约栅栏 + Plan/Evidence 复核 + 幂等准入 + 快照
TX-B  租约栅栏 + RESERVED -> DISPATCHED
I/O   最多一次 EmbeddingCapability.embed；无数据库事务
TX-C  租约栅栏 + 可选 Provider Identity 补充
TX-D  租约栅栏 + 完整 Entry 批次持久化
TX-E  租约栅栏 + Component 结算
TX-F  d-2 进度更新并释放租约
```

纯恢复路径跳过不需要的阶段。TX-A/B/C/E 中每个 Governance 变更都通过公共模块服务加入
锁定 Knowledge 租约校验所在的同一短事务。任何模块都不能读取另一个模块的表。

PREP 可能读取较大的不可变 Source Snapshot，因此不得持有 Build Row Lock。TX-A 锁定
Build，并在 Admission 前低成本复核预期 Build Version/State、持久 Cursor、精确 Batch
Identity 和 Entry Evidence。不可变 Source Revision 保证准备内容稳定；任何变化的可变
证据都会使 Plan 失效并重新计算。

Provider Timeout 必须小于 `leaseDuration - commitMargin`。只允许在 Dispatch 之前通过
d-2 Compare-and-set Kernel 续租。不能在不确定调用之后仅为了让旧 Worker 提交而续租。

### 7.2 Provider 结果处理

Provider 返回后：

1. 校验 Execution Identity、Route、输出数量/顺序、Item/Content Digests、Dimension、
   有限值与 Normalization；
2. 重新验证租约；
3. 如果存在 Provider Request Identity，补充到已 Dispatch 的 Component；
4. 持久化完整 Entry 批次；
5. 结算同一个 Component；
6. 记录单调进度并释放租约。

如果 I/O 后任一时点租约校验失败，旧 Worker 不再执行任何持久变更。新 Owner 根据
Component 与 Entry Ledger 恢复。

## 8. 恢复决策矩阵

纯决策器继续作为权威：

| Component 证据 | Entry 证据 | Replay Policy | 动作 | Provider 调用 |
|---|---|---|---|---:|
| 幂等准入前不存在 | 无 | 任意 | `ADMIT` | 0 |
| `RESERVED` | 无 | 任意 | `DISPATCH` | Dispatch 后最多 1 次 |
| `DISPATCHED` | 无 | `SAFE_REPLAY` | `REPLAY` | 最多 1 次 |
| `DISPATCHED` | 无 | `RECONCILIATION_REQUIRED` | `RECONCILE` | 0 |
| 非终态 | 完整且相同 | 任意 | `SETTLE_ONLY` | 0 |
| `SUCCEEDED` | 完整且相同 | 任意 | `COMPLETE` | 0 |
| 任意 | 部分 | 任意 | `INTEGRITY_FAILURE` | 0 |
| 非终态 | 完整但不同 | 任意 | `INTEGRITY_FAILURE` | 0 |
| `FAILED` 或 `RECONCILIATION_REQUIRED` | 完整且相同 | 任意 | `LEDGER_ARTIFACT_INCONSISTENCY` | 0 |
| 终态 | 缺失或完整但不同 | 任意 | `LEDGER_ARTIFACT_INCONSISTENCY` | 0 |

`FAILED` 与 `RECONCILIATION_REQUIRED` 是 Ledger 终态，不得被新成功覆盖。

### 8.1 诚实的 Settlement-only 计费

本切片有意不新增 Provider Result 表。如果 Entries 已提交，但实际用量在结算前因崩溃
丢失，恢复使用 Component 中冻结的估算 Units/Cost，并以 `ESTIMATED` 质量结算，绝不能
标成 `ACTUAL`。

正常路径按真实可用质量结算实际用量/费用。如果 Provider Request Identity 可用，则在
Entry 持久化前补充，以改善诊断，但它不是正确性依赖。

这是无需迁移时唯一正确的恢复方式：伪造实际用量被禁止，重复不安全的付费调用风险更大，
而永久不结算一个完全相同的持久 Artifact 会破坏闭环。

## 9. 动作行为

- `ADMIT`：创建或复用精确 Reservation；准入拒绝发生在 Provider I/O 前。
- `DISPATCH`：先持久标记 Dispatch，再执行一次调用。
- `REPLAY`：持久确认同一 Dispatch Identity，仅在 `SAFE_REPLAY` 时执行一次调用。
- `RECONCILE`：把 Component 标记为需要对账，并使 Build 以
  `reconciliationRequired=true` 失败；不重试。
- `SETTLE_ONLY`：当前 Attempt 仍有终态结果时使用该结果，否则使用冻结 Estimate 并标记
  `ESTIMATED`；然后更新进度。
- `COMPLETE`：不执行 Governance/Provider 变更；根据相同 Entries 更新进度。
- `INTEGRITY_FAILURE`：使用稳定 Validation/Integrity Code 使租赁 Build 失败。
- `LEDGER_ARTIFACT_INCONSISTENCY`：使用独立稳定码 Fail Closed，并保留全部证据。

持久 Dispatch 前的瞬时错误可以使用 d-2 重试策略。`DISPATCHED` 后的 Timeout 或传输失败
遵循 Replay Policy，不能走通用自动重试。

## 10. 失败映射

错误只映射一次到 `KnowledgeIndexBuildFailure`：

- 无效 Source/Route/Digest/Output/Entry 证据 → `VALIDATION`，不可重试；
- Authorization、Scope、Secret 或 Policy 拒绝 → `SECURITY`，不可重试；
- Dispatch 前的临时数据库/Readiness 故障 → `TRANSIENT`，可重试；
- 确定性不支持、超限或 Provider 拒绝 → `PERMANENT`，除非已批准的规范错误明确表示
  Dispatch 前瞬时错误；
- 未知内部不变量破坏 → `INTERNAL`，不可重试；
- 不安全且未解决的 Dispatch → `AMBIGUOUS`，需要对账。

只存稳定 Code 与有界 Metadata。Provider Body、URL、Source Content、Vector、Secret 和
Lease Owner 不得进入错误、日志、指标或 Audit Payload。

## 11. Audit 与遥测边界

P2.2d-3 仅对终态失败/对账记录管理 Audit，并在需要时通过现有 Governance Audit API 与
失败变更共享事务。逐批进度属于有类型 Build/Component 状态，不制造 Audit 噪声。

本切片输出有类型 Orchestration Outcome，供 d-5 绑定 Metrics；不启用新的 Meter Set 或
Health Contributor。未来有界维度仅包括 Action、Outcome、Usage Quality、Replay Policy
与 Failure Category。Tenant、Workspace、Build、Route、Chunk、Reservation、Trace、
Request 和 URL Identity 仍禁止作为 Label。

## 12. 验证

### 12.1 单元与契约测试

1. 穷举 Component × Entry × Replay Policy 的每个恢复组合。
2. 冻结不同 Locale、Timezone、Row Order 与 Retry 下的批次选择和 Identity。
3. 证明持久 Cursor 会重建已经写入但未结算的批次。
4. 证明确定性 Actor/Trace 重复准入返回同一 Reservation。
5. 证明 Snapshot 作用域、相等性与安全投影。
6. 证明 P1 CHAT 与现有 P2.2c Component Lifecycle 兼容。
7. 证明规范失败映射且不存在敏感字段。

### 12.2 PostgreSQL/Testcontainers 崩溃测试

分别在以下位置后注入崩溃：

1. Claim 后、Admission 前；
2. Admission 后、Dispatch 前；
3. Dispatch 后、Provider Call 前；
4. Provider Call 后、Identity Enrichment 前；
5. Identity Enrichment 后、Entries 前；
6. Entries 后、Settlement 前；
7. Settlement 后、Progress 前；
8. Progress 后、Response 前。

还必须证明：

- 准入拒绝时 Provider 调用为零；
- 一次 Claim 最多调用 Provider 一次；
- 不安全的 Dispatched Work 绝不自动 Replay；
- Partial/Conflicting Entries 绝不绕开填充；
- 旧/过期 Worker 不能 Admit、Dispatch、Enrich、Persist、Settle、Reconcile、Fail 或 Advance；
- 新 Claim 会栅栏旧 Owner；
- 跨 Workspace Reservation、Component、Build、Chunk、Entry 访问 Fail Closed；
- 相同重试幂等，冲突重试失败；
- Governance 或 Audit 失败会回滚对应租约栅栏阶段；
- Provider I/O 期间不存在活动数据库事务。

### 12.3 累计门禁

运行 Knowledge、Governance、Capability Registry 单元/集成测试、Spring Modulith/ArchUnit、
Flyway Migration 测试、P1 Governance 回归、P2.1 Ingestion、P2.2c Execution、d-1 Build
API、d-2 Lease 测试、Java 格式/静态检查、`bootJar`、Contract 检查与 Compose Health。
Frontend 与 Python 测试不变，但累计 CI 必须保持绿色。

## 13. 实施检查点

1. **d3.1 — Governance Snapshot 与事务参与**
2. **d3.2 — 确定性下一批重建与带租约栅栏的 Entry Writer**
3. **d3.3 — 单次 Claim Orchestrator 与全部八个恢复动作**
4. **d3.4 — 崩溃、并发、安全与累计验证**

这些是后续同一个 `feature/` 实施分支上的一致验证检查点，不授权拆分扩大功能范围。

## 14. 回滚

- Runner 仍保持禁用，因此合并 d-3 不会启动后台工作。
- 不需要 Schema 回滚。
- 之前的兼容 Binary 会忽略新增内部 Orchestrator 与 Snapshot API。
- 现有 Reservation、Dispatched Component 和 Entries 会保留且不重写。
- 后续启用后如果回滚 Binary，先停止新 Claim 并有界排空 In-flight Work。
- 回滚绝不能清除 Reconciliation-required 证据。

## 15. 自我批判

1. 没有 Provider Idempotency 时仍无法做到 Exactly-once；本设计暴露不确定性而不是掩盖。
2. Governance 变更加入 Knowledge 租约栅栏事务会产生有意的事务耦合，但不会产生数据库
   所有权耦合；它比允许旧 Worker 结算更窄、更安全。
3. 因为没有 Result Ledger，崩溃后 Settlement-only 可能把响应时可用的 Usage Quality
   降级为 `ESTIMATED`。这是诚实结果，也避免新增迁移；如果未来必须跨该边界保留精确
   Actual Usage，需要单独审查持久结果设计。
4. 从 `embeddedEntryCount` 重建假设进度是连续前缀。V11 与完整批次 Writer 必须强制并
   测试该不变量。
5. 一次 Claim 一个 Batch 牺牲吞吐量，换取公平性、有界不确定性和更简单恢复。
6. d-3 仍是内部工作流切片，不是最终用户 Knowledge 功能。发布属于 d-4，运维启用与
   Evidence 属于 d-5。

## 16. 批准门

Maintainer 批准后仅授权以上四个 d-3 检查点。不授权 d-4 Publication、d-5 Runner
Activation、P2.2e Retrieval Lab、公共契约上线、Migration、新表/Deployable/有状态依赖，
也不授权修改 Release/Runtime/Application。
