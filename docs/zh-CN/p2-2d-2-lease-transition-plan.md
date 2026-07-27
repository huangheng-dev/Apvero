# P2.2d-2 租约与状态转换内核——实施计划

状态：规划候选；实施前需要维护者批准

目标：P2 / P2.2 / P2.2d-2

权威来源：ADR-0006、P2.2d 持久化 Build 基线、V11 数据库守卫和当前 Knowledge 模块契约。

## 1. 结果与边界

本检查点增加后续 P2.2d 执行步骤共用的内部 PostgreSQL 租约与状态转换内核。它必须证明：
一个 Worker 能独占一个持久化 Build 单元；租约过期的旧 Owner 无法在新 Owner 认领后写入；
重试与终止失败会以确定方式持久化。

本检查点不执行 Embedding、不调用 Governance、不发布 Index Version、不增加 Scheduler、
不激活页面、不修改 OpenAPI，也不增加数据库迁移。P2.2d-3 负责受治理的 Embedding 编排，
P2.2d-4 负责验证与发布，P2.2d-5 负责生产 Runner 和运维门禁。

目标模块仅为 Knowledge。Identity 仍是工作区范围后台枚举的允许依赖，但本检查点接收已授权的
`WorkspaceScope`，不增加新的跨模块调用。

## 2. 架构决策检查

不需要 ADR。本计划保持在 ADR-0006 批准的 PostgreSQL Lease Runner 和 P2.2d 状态机范围内，
不增加模块、部署单元、有状态依赖、公共契约、Release 语义、安全例外或技术基线。

V11 已包含所需持久化字段与数据库守卫。实现时如发现字段缺失或守卫不兼容，必须停止并进行架构
审查；不得用通用表、内存租约或未经审查的 V12 绕过问题。

## 3. 内核形态

使用一个包内可见的 `KnowledgeIndexBuildTransitionKernel`，由窄粒度 Repository 操作支撑。
不得暴露通用的 `transition(build, nextStatus)` 方法。

内核接收有界参数并返回不可变内部结果：

- `claim(scope, owner, capacity)` 返回零个或多个已认领 Build 快照；
- `renew(scope, claim)` 延长已持有且未过期的租约；
- `recordEmbeddingProgress(scope, claim, progress)` 记录单调进度，不改变持久步骤；
- `advanceToIndexing(scope, claim)` 与 `advanceToValidating(scope, claim, evidence)` 只编码已批准
  的前向转换；
- `releaseForRetry(scope, claim, failure)` 安排确定性重试或持久化终止失败；
- `fail(scope, claim, failure)` 持久化明确的不可重试或待协调结果；
- 后续 P2.2d-4 增加独立发布事务，而不是在这里增加 `READY` 转换。

每次成功变更返回新的 Build 快照及递增后的 `lock_version`。零影响行数是类型化的陈旧/并发结果，
绝不能无条件重试或静默报告成功。

## 4. 认领与回收事务

认领必须受工作区限制且有界：

1. 校验非空且有界的 Owner、正数 Capacity 和有界 Lease 配置；
2. 按准确 Tenant/Workspace，以 `next_attempt_at NULLS FIRST, created_at, id` 排序选择候选；
3. 使用 `FOR UPDATE SKIP LOCKED LIMIT ?`；
4. 候选是未租用且到期的 `QUEUED`/`RETRY_WAIT`，或 Lease 已过期的
   `EMBEDDING`/`INDEXING`/`VALIDATING`；
5. Waiting Build 被认领时，Status 转为与 `current_step` 匹配的 Active Status，
   `attempt_count` 加一，清空 `next_attempt_at`，`started_at` 仅首次赋值，设置 Owner/Expiry，
   并递增 `lock_version`；
6. 回收过期 Active Build 时保留 Status、Step、Attempt、Progress、Digest 和失败证据，只替换
   Owner/Expiry 并递增 `lock_version`；
7. 在任何外部调用或高成本工作前提交事务。

数据库时间是候选选择、过期比较和持久化 Expiry 的权威时间。应用 `Clock` 可用于确定性策略单测，
但生产 SQL 不得依赖 Worker 主机时钟。Lease Duration 加到认领事务捕获的同一个数据库时间上。

过期 Active Lease 代表执行历史未知。回收绝不重置或推进持久状态，也不能证明 Provider Call
没有发生。

## 5. Fencing 与变更规则

`lock_version` 就是 Fencing Token，不增加第二 Token 或新表。每次租约内变更都使用一条包含以下
条件的 SQL Compare-and-set：

```text
tenant_id + workspace_id + build_id
+ expected status/step
+ expected lease_owner
+ expected lock_version
+ lease_until > database_now
```

更新必须恰好递增一次 `lock_version`。每次成功后，调用方必须替换其 Claim 快照。陈旧快照、错误
工作区、错误 Owner、过期 Lease 或后继认领都只能影响零行。

续租从数据库当前时间延长，而不是从旧 Expiry 延长；不能复活已过期 Lease。一个持久单元完成后
清除 Owner/Expiry，以保证其他 Build 的公平性。认领/转换事务内不得执行网络调用或长时间 Digest
计算。

## 6. Attempt、重试与失败

Waiting Build 被认领时开始一次 Attempt。回收过期 Active Build 不增加 Attempt，因为它继续协调
同一个持久化 Attempt。

自动失败处理是一个事务：

- 可重试且 `attempt_count < maximum_attempts`：进入 `RETRY_WAIT`，保留当前 Step，清除 Lease，
  记录有界错误并设置 `next_attempt_at`；
- 可重试但自动窗口耗尽：进入 `FAILED` 且 `retryable=true`，允许已实现且有审计的手动 Retry
  重置窗口；
- 永久或完整性失败：进入 `FAILED` 且 `retryable=false`；
- Dispatch 结果不明确：进入 `FAILED`、`reconciliation_required=true`，绝不自动重试；
- 只有 `FAILED` 设置 `completed_at`；`RETRY_WAIT` 仍未完成。

退避是确定的：

```text
delay = min(backoffMaximum, backoffBase * 2^(attemptCount - 1))
```

乘法前做饱和溢出处理，不使用随机 Jitter。独立的
`apvero.knowledge.index-build-runner.*` 配置必须校验 Base、Maximum、Lease、Provider Timeout
Margin 和 Capacity 为正且有界。P2.2d-2 定义并验证策略；P2.2d-5 才接入调度与停机。

## 7. 取消边界

现有公共 Cancel Operation 仍是本检查点唯一的取消路径，只接受未租用的 `QUEUED` 或
`RETRY_WAIT`。本阶段有意不增加 Active Build 取消、Provider Call 中断或
`cancellation_requested` 变更，因为 V11 与已批准基线不允许 Active-to-cancelled 转换。

未使用的持久化字段为兼容性保留，不得对外宣称它是可用功能。

## 8. 错误、安全与遥测

内部失败使用稳定错误码，覆盖非法内核输入、Lease 冲突、状态冲突、Attempt 耗尽和有界失败元数据。
公共 REST 行为不变。Lease Owner、失败 Body、Provider Data、Source Content 和跨工作区存在性
不得进入正常响应。

内核暴露有界的类型化 Outcome，供 P2.2d-5 接入：

- Claim Outcome 与 Claimed Count；
- Reclaim 与 Renewal Outcome；
- 按有界 Status/Step 标记的 Transition Outcome；
- Retry Scheduled/Exhausted；
- Stale Lease Rejection。

P2.2d-2 不绑定 Micrometer Meter 或 Health Indicator；生产指标接入属于 P2.2d-5。类型化
Outcome Dimension 只能使用有界枚举，禁止 Tenant、Workspace、Build、Owner、Route 和错误
文本。常规 Claim/Progress 不写入管理审计记录。

## 9. 实施顺序

1. 增加经过校验的 Index Build Runner Properties 和确定性 Backoff Policy，并编写单测。
2. 增加不可变内部 Claim/Failure/Progress Record 和窄粒度 Repository 方法。
3. 实现 Scoped `SKIP LOCKED` Claim/Reclaim 与租约 CAS SQL。
4. 实现 Transition Kernel 和类型化 Stale/State Outcome。
5. 证明 Outcome Data 有界且失败数据已脱敏；Meter Binding 留给 P2.2d-5。
6. 执行模块、迁移、架构和累计回归验证。

这是一个完整的实施检查点和一个 Implementation Pull Request。规划与实现继续使用独立分支。

## 10. 验证矩阵

PostgreSQL Testcontainers 证据必须证明：

1. 两个 Worker 不能认领同一个 Waiting Build；
2. 并行 Claim 跳过锁定行并保持确定排序；
3. 到期 Retry 可认领，未来 Retry 不可认领；
4. 过期 Active Lease 可回收，且 Attempt/Progress 不变；
5. 回收后，前任不能续租、更新进度、失败或推进；
6. Lease 在准确 Expiry 边界视为过期；
7. 成功变更恰好递增一次 `lock_version`；
8. 错误 Tenant/Workspace/Owner/Version/Status/Step 与过期 Lease 只能影响零行；
9. Claim 事务回滚后 Build 仍可认领；
10. Retry Delay 确定、有界且不会溢出；
11. 自动重试、重试耗尽、永久失败和待协调具有准确持久化形态；
12. Progress/Attempt Counter 不下降，也不超过批准边界；
13. Waiting Cancel 与 Claim 只能有一个获胜，不能同时 Active 与 Cancelled；
14. V11 拒绝绕过 Repository 的直接非法转换；
15. 类型化 Telemetry Outcome 不暴露高基数或敏感 Dimension。

还需运行 Knowledge 单元/模块测试、Spring Modulith、ArchUnit、V11 Clean/Upgrade、OpenAPI
兼容性、Java 格式/静态检查、`bootJar` 和 P1/P2 累计测试。预计不修改前端、i18n Key、Python
或迁移，但仍需通过累计 CI。

## 11. 故障注入与回滚

在行选择后、Claim Update 后、Retry/Failure Update 后和每个合法前向转换后立即注入 Rollback。
回滚后的持久状态必须与调用前完全相同。

本检查点没有 Live Scheduler，也没有 Schema 变更，因此回滚方式是保留 V11 并使用上一个
P2-compatible Binary。通过手动测试调用合法变更的记录在 V11 下仍然有效，后续可继续认领或
检查；回滚绝不删除它们。

## 12. 自我审查

- 全局跨租户 Claim 更简单，但会削弱租户隔离；拒绝，采用授权工作区枚举加 Scoped Claim。
- 应用主机时间便于测试，但会产生时钟偏差所有权问题；拒绝，采用 PostgreSQL Lease Time。
- 通用 Transition API 代码更少，但很容易表达非法状态；拒绝，采用窄粒度操作。
- Reclaim 时增加 Attempt 看似直观，但会因进程崩溃在没有新执行决策时耗尽 Retry；拒绝。
- 随机 Retry Jitter 能平滑负载，但会削弱确定性恢复证据；在测量到竞争并设计版本化确定性
  Jitter Policy 之前不增加。
- Active Cancellation 很有吸引力，但与批准的 V11 状态机冲突，也无法诚实中止未知 Provider
  Call；延期而不是模拟成功。
- 该内核自身不是用户功能。在完整 P2.2d 及后续 P2 工作流门禁通过前，它保持内部且非 Live。

## 13. 批准门禁

批准只授权实现该内部内核，不授权 P2.2d-3 Embedding Call、Governance 契约变更、P2.2d-4
发布、P2.2d-5 Scheduler 激活、OpenAPI 变更、迁移、新表/部署单元/依赖或扩展状态机。
