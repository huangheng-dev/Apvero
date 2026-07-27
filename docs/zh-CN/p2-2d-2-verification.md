# P2.2d-2 租约与状态转换内核验证

状态：实施检查点候选；P2.2d 仍在进行中

## 已交付范围

P2.2d-2 增加了内部 Knowledge Index Build Lease 与 Transition Kernel，提供：

- 使用 `FOR UPDATE SKIP LOCKED` 的有界、Workspace Scoped PostgreSQL Claim；
- 对无租约 Active Work 的确定性继续执行，以及对过期 Lease 的恢复；
- `lock_version` Fencing，以及 Owner、Status、Step、Scope 和未过期 Lease 的
  Compare-and-set；
- 基于 PostgreSQL 时间的 Lease Renewal；
- 具有连续 Ordinal Prefix 的持久化 Embedding Progress；
- 从 Embedding 到 Indexing、从 Indexing 到 Validating 的窄粒度转换；
- 不含随机 Jitter 的确定性有界指数退避；
- 明确的永久失败、重试耗尽和需要协调的失败形态。

Kernel 仅在 Package 内部可见，不暴露通用 Status Setter。每次成功变更都恰好递增一次
`lock_version`，并返回新持久化的 Build Snapshot。零影响行数会转为稳定的
`APVERO_KNOWLEDGE_INDEX_BUILD_LEASE_CONFLICT` Outcome。

## 时间、Attempt 与公平性

PostgreSQL `transaction_timestamp()` 是 Claim Eligibility、Lease Expiry、Renewal、Retry
Scheduling 和 Mutation Validity 的权威时间。Worker 主机时钟无法取得或延长所有权。

Waiting Build 被认领时开始一次 Attempt。回收过期 Active Build，或在一个 Durable Unit
释放 Lease 后继续 Active Build，都保留 Attempt Counter。这样，进程重启和公平的批次让出不会
消耗 Retry Budget。

一次持久化 Progress 或前向转换操作会清除 Lease。下一次 Scoped Claim 可以继续同一个 Active
Attempt。这是 P2.2d 公平性规则的必要行为，不代表一次新 Retry。

## 安全与失败行为

每个 Repository Predicate 都先包含 Tenant、Workspace 和 Build Identity，再检查 Ownership
与 Version。错误 Scope、Owner、Version、Status、Step 或过期 Lease 只能影响零行。Lease Owner
Identity 和 Failure Detail 保持内部可见。

Failure Input 只接受稳定且有界的 Code 与有界 Category Enum，不保留任意 Provider Payload。
Ambiguous Failure 必须不可重试、使用 `AMBIGUOUS` Category，并持久化
`reconciliation_required=true`。自动重试耗尽时持久化 `FAILED` 与 `retryable=true`，保留
现有带审计的手动 Retry 路径。

本检查点不实现 Active Cancellation 或 Provider Interruption，只保留已批准的未租用 Waiting
Cancellation。测试证明 Cancellation 与 Claim 不会同时获胜。

## 验证覆盖

本检查点验证：

- 准确的确定性 Backoff 值、上限和溢出行为；
- 默认关闭的 Runner 配置与 Timeout/Lease 安全余量；
- 确定性 Scoped Ordering 与 Claim Batch 上限；
- 并发单 Winner Claim 与跳过已锁行；
- Claim、Progress、Failure 和两个前向转换后的事务回滚；
- 准确 Lease Expiry 边界恢复；
- 后继认领后拒绝陈旧前任；
- 单调连续 Progress 与仅完整状态允许前向转换；
- Due 与 Future Retry Eligibility；
- 自动重试、永久失败、重试耗尽和 Ambiguous Reconciliation；
- 跨 Workspace、错误 Owner 与陈旧 Version 的 Fail-closed；
- Cancellation 与 Claim 互斥；
- V11 Clean/Upgrade、状态转换和不可变持久化回归；
- P2.2d-1 API 与 OpenAPI Conformance 回归；
- Spring Modulith 与 Knowledge Module Test。

一个组合本地命令同时尝试启动多个独立 PostgreSQL Container，其中一个 P2.2b Container
启动失败。随后独立重跑 P2.2b Suite 并全部通过，因此该失败属于 Container 启动资源竞争，不是
代码或迁移失败。之后与 CI Backend 完全一致的
`gradlew test :apps:platform-server:bootJar` 通过了完整仓库测试套件。

## 架构与兼容性

本检查点没有修改公共 REST、OpenAPI、JSON Schema、模块依赖、表、迁移、Provider Abstraction、
Frontend Key、Python Contract 或部署单元。V11 继续作为当前 Schema Baseline。
独立的 `apvero.knowledge.index-build-runner.*` 配置默认关闭；Scheduler Activation、Metrics
和 Health 属于 P2.2d-5。

回滚方式是保留 V11 并使用上一个 P2-compatible Binary。回滚时不得删除或重写任何 Durable
Build Row。

## P2.2d 剩余工作

P2.2d-3 必须把 Kernel 接入受治理的单批 Embedding 与 Component Ledger Recovery。
P2.2d-4 必须验证并原子发布不可变 Index Version。P2.2d-5 必须激活 Runner、增加有界 Metrics
与 Health、执行最终 Compose Verification，并发布最终双语验收证据。本检查点不会让页面或后台
Build Execution 变为 Live。
