# P2.2d-3 受治理的 Embedding 编排验证

状态：实施检查点候选；P2.2d 仍在进行中

## 已交付范围

P2.2d-3 将一个已取得 Lease 的 Knowledge Index Build 接入一个受治理的 Embedding Batch，
提供：

- 从持久化连续 Entry Cursor 确定性重建批次；
- 通过 Provider-neutral `EmbeddingCapability` 精确解析、报价和执行 Route；
- 使用 Scoped Immutable Component Snapshot 进行幂等 Governance Admission；
- 在 Admission、Dispatch、Entry Persistence、Settlement 和 Progress 周围使用短时
  Lease-fenced Transaction；
- 在数据库事务外执行 Provider I/O；
- 每次 Claim 最多调用一次 Provider；
- 完整批次 Entry Persistence，并拒绝 Stale Worker；
- 根据持久化 Component 与 Entry Evidence 恢复，不虚构 Actual Usage；
- 有界、稳定且不保留 Provider Payload 的 Failure Normalization。

确定性本地 Spring AI Adapter 与现有 OpenAI-compatible Adapter 统一通过一个 Capability
实现访问。Provider SDK 类型不会进入 Knowledge API。

## 恢复与计费

下一批次由 Durable Cursor 重建，而不是由第一个缺失 Entry 决定。因此可以恢复 Entry 已提交，
但 Component Settlement 或 Build Progress 尚未提交的窗口。

- Non-terminal Component 已存在相等 Entries 时，使用冻结 Estimate 和 `ESTIMATED` Usage
  完成结算，不调用 Provider。
- Succeeded Component 已存在相等 Entries 时，不修改 Governance、不调用 Provider，只推进
  Progress。
- Unsafe Unresolved Dispatch 进入 Reconciliation-required，绝不自动 Replay。
- Partial、Conflicting 或 Terminal-ledger/Inconsistent-artifact Evidence 均 Fail Closed。
- Successor Lease 会阻止 Previous Worker 的所有变更。

正常路径在 Provider 提供 Actual Units 时使用真实值。Safe Timeout 可重试；Unsafe Timeout
属于 Ambiguous；Provider Rejection 属于 Permanent；无效输出属于 Validation；Secret Material
缺失属于 Security；未知异常文本会被压缩为有界 Internal Code。

## 验证证据

单元与集成测试证明：

- 全部 Component State × Entry State × Replay Policy 组合；
- 确定性的 Next-batch Reconstruction 与 Already-written Unsettled Recovery；
- Kernel 和完整批次 Writer 对 Stale Lease 的拒绝；
- Scoped Component Projection 与 Cross-workspace Not-found 行为；
- 外层 Lease-fenced Transaction 回滚时 Component Admission 同步回滚；
- 一个生成 256 维向量的完整 PostgreSQL Governed Batch；
- Entries 已提交、Settlement 未提交时恢复且 Provider 零次重复调用；
- Settlement 已提交、Progress 未提交时恢复且 Provider 零次重复调用；
- Successor Claim 后拒绝 Previous Owner；
- 仅在 Durable Cursor 完整后转换到 `INDEXING`；
- 稳定 Failure Category、Retry、Reconciliation 与敏感详情收敛。

已执行门禁：

```text
gradlew :modules:capability-registry:test :modules:knowledge:test
gradlew :apps:platform-server:test --tests P22d3KnowledgeEmbeddingOrchestrationIntegrationTest
gradlew test
gradlew check bootJar
git diff --check
```

全部门禁通过。完整仓库测试包含历史 P1 Governance、P2.1 Ingestion、P2.2c Embedding、
P2.2d-1 Build API、P2.2d-2 Lease、Flyway/Testcontainers 与 Spring Architecture 回归。

## 架构与兼容性

本检查点没有修改 REST、OpenAPI、JSON Schema、数据库迁移、表、Queue、Deployable、
Frontend Key、Python Contract、Release Semantic、Runtime Behavior 或 Stateful Dependency。
Knowledge 只保留已批准的 Identity、Capability Registry 和 Governance 依赖。

Runner 继续默认关闭。本检查点不会让后台 Build Execution 或产品页面变为 Live。保留 V11
数据时，上一个 Compatible Binary 可以忽略内部 Orchestrator 与新增的 Governance Snapshot。

## P2.2d 剩余工作

P2.2d-4 必须验证并原子发布 Immutable Index Version。P2.2d-5 必须激活有界 Runner，增加
Metrics 与 Health，完成 Compose Verification，并发布最终双语验收证据。本检查点在合并或开始
依赖它的下一阶段实施前，仍需要 Maintainer Acceptance。
