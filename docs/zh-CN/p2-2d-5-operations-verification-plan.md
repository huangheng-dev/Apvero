# P2.2d-5 生产 Runner 与运维验收——实施计划

状态：实施候选；尚未开始业务编码

目标：P2 / P2.2d-5

权威依据：ADR-0006、已批准的 P2.2d 持久化 Build 基线，以及已验证的 P2.2d-1 至
P2.2d-4 实现

推理程度：高

## 1. 结果

P2.2d-5 闭合持久化 Knowledge Index Build 工作流：

```text
可执行的 Build
  -> 有界且工作区公平的领取
  -> 执行一个受租约保护的步骤
  -> 持久化进度或分类失败
  -> 重新领取，直至 READY / FAILED / CANCELLED / 需要人工核对
  -> 可观测的健康、指标与安全诊断
  -> 可复现的 Compose 验收证据
```

当现有 Build 状态机能够自动运行、安全停机、中断恢复、暴露有界运维信号，并以英文
和简体中文证明完整 P2.2d 工作流时，本切片才完成。

这不是通用分布式任务平台。它不启用 Retrieval Lab，不开放 Index Version REST
操作，不新增前端页面，不探测付费 Provider，不承诺外部调用恰好一次，也不宣称具备
消息队列级吞吐量。

## 2. 变更声明

| 项目 | 决定 |
|---|---|
| 阶段 | P2 / P2.2d-5，`in-progress` |
| 主模块 | `knowledge` |
| 支撑模块 | 现有 `identity`、`capability-registry`、`governance` 公开 API |
| 允许依赖 | Knowledge → Identity、Capability Registry、Governance |
| REST / OpenAPI / JSON Schema | 不变；已接受的五个 Build 操作保持 live，Version 列表保持 `contract-only` |
| 数据库迁移 | 无 |
| 有状态依赖 / 可部署单元 | 无；PostgreSQL 仍是唯一强制有状态依赖 |
| AI 抽象 | 仅使用现有 Spring AI / Provider 中立能力边界 |
| 前端 / Python | 不改变产品行为；仍需通过累计检查 |
| 暴露方式 | Build 自动化受两层开关控制，外层 Knowledge 默认关闭 |

ADR-0006 与已批准的 P2.2d 基线已授权本 Runner 与运维工作。本计划不改变不变量、模块
边界、公开契约、发布语义、安全策略或技术基线，因此无需新 ADR。如果实现需要新增表、
队列、可部署单元、模块依赖、Provider SDK 类型或公开端点，必须停止编码并返回架构审查。

## 3. 当前实现盘点与缺口

实现必须组合现有权威组件，不能建立平行工作流：

- `KnowledgeIndexBuildTransitionKernel` 负责按作用域领取、续租、受保护转换、重试与终态失败；
- Embedding、验证与发布 Orchestrator 分别执行一个已领取步骤；
- `WorkspaceScopeCatalog` 是后台工作区枚举的 Identity 公开边界；
- `KnowledgeIndexPersistenceRepository` 是唯一 Build 持久化边界；
- `KnowledgeIndexBuildRunnerProperties` 已定义领取批次、租约、外部调用、提交余量与退避时间；
- 现有 ingestion runner 展示了有界调度模式，但不会被抽象成通用业务 Runner；
- 当前 Knowledge 健康检查只报告解析 Worker；
- 当前 Compose overlay 会启用 Knowledge，但未暴露独立 Build Runner 配置。

缺失内容是一套 Build Runner、生命周期、运维快照、有界遥测、Compose 验收与双语验证。
Build 状态或发布算法本身并不缺失。

## 4. Runner 所有权与生命周期

### 4.1 两个独立开关

只有以下两项同时为真，Runner 才接受领取：

1. `apvero.knowledge.enabled`；
2. `apvero.knowledge.index-build-runner.enabled`。

外层功能开关默认仍为 `false`，Build Runner 开关也继续默认 `false`。启用 ingestion
runner 绝不能隐式启用 Index Build。

Runner 暴露四个内部生命周期状态：

- `disabled`：任一配置开关关闭；
- `accepting`：定时 tick 可以领取工作；
- `draining`：禁止新领取，已提交工作拥有有界完成窗口；
- `stopped`：执行器已关闭，本进程没有正在运行的工作。

这些 token 只是运维状态，不是新的领域状态。

### 4.2 有界执行器与调度

在 `apvero.knowledge.index-build-runner` 下新增并校验：

| 属性 | 默认值 | 规则 |
|---|---:|---|
| `poll-interval` | `1s` | 正数且不超过 24 小时 |
| `concurrency` | `4` | 1–64 |
| `graceful-drain` | `30s` | 正数且不超过 24 小时 |

现有 `claim-batch` 保持 1–100。每次 tick 先计算本地剩余容量，再把
`min(剩余容量, claim-batch)` 交给转换内核。执行使用固定大小的平台线程池，其有界队列容量
不超过配置并发数；剩余容量同时计算 active 与 queued task。禁止无界队列、无界虚拟线程扇出
以及每工作区一个线程池。

`@Scheduled` 使用 fixed delay。原子 tick 守卫防止未来调度器并发配置造成重叠扫描。
提交被拒绝时不再领取更多工作；Runner 停止扫描，让已经持久化的租约自然到期并安全回收。

### 4.3 工作区公平性

Runner 只能通过 `WorkspaceScopeCatalog.listForBackgroundProcessing()` 获取作用域。Repository
领取保持 tenant/workspace 作用域，不新增跨工作区 Build SQL。

已排序工作区列表使用内存轮转游标访问。单个工作区最多消耗本次 tick 剩余容量，下次 tick
从上次访问位置之后开始，防止第一个稳定工作区永久占满本地容量。进程重启重置游标不影响正确性。

租约 owner 是有界、不透明的进程级值。它可以持久化用于 fencing 与诊断关联，但绝不能成为
指标标签、健康详情、API 响应或普通日志字段。

### 4.4 一次领取，一个持久化单元

Runner 根据已领取 Build 的准确状态与步骤分派：

| 已领取状态 | 操作 | 持久化结果 |
|---|---|---|
| `EMBEDDING / EMBEDDING` | 执行一个受 Governance 管理的 embedding batch | 记录进度并释放、进入 INDEXING、重试/失败或 reconciliation |
| `INDEXING / INDEXING` | 重建并验证完整 artifact | 进入 VALIDATING 或失败 |
| `VALIDATING / VALIDATING` | 原子发布 | READY、相等重放或失败 |

任何不匹配或终态都产生稳定状态冲突，绝不猜测下一步。一次领取最多进行一次 Provider
dispatch 和一个持久化工作单元。后续工作由下一次 tick 重新领取，以保留公平性和崩溃恢复能力。

### 4.5 失败归一化

Runner 不替换 Orchestrator 的专用决策，只归一化逃逸出步骤的意外失败：

- 租约/状态/并发冲突成为有界 stale-lease 结果，不覆盖新 owner；
- 已知瞬时本地失败仅在仍能证明当前租约归属时进入现有重试策略；
- 验证/完整性失败不可重试；
- Provider dispatch 结果不明时标记为需要 reconciliation，绝不盲目重试；
- 未知失败仅在能安全证明归属时写入一个稳定内部分类，否则等待租约到期回收。

日志只包含 step、有界 outcome 与稳定 code。绝不包含 ID、源文本、向量、URL、Provider
响应体、凭据、lease owner 或原始 SQL。

## 5. 租约时间与停机

继续保持现有不变量：

```text
lease-duration > external-call-timeout + commit-margin
```

Runner 不能假定 Java 超时会取消 Provider 请求。Pinned Route timeout 仍是执行超时，并且
不得超过配置的 `external-call-timeout` 安全上限；不安全的 Route/runner 组合必须在 dispatch
之前失败。当剩余数据库时间租约不足以覆盖该调用时间加提交余量时，步骤在外部调用或发布区间之前续租。

停机顺序：

1. 原子地把 `accepting` 改为 false；
2. 阻止后续定时领取；
3. 关闭执行器提交；
4. 最多等待 `graceful-drain`；
5. 进程收到中断时保留中断标志并停止等待；
6. 绝不因为 drain 超时而写入伪造成功或失败。

超过时限后，未完成的 Provider 工作仍可能处于不明确状态。下一进程必须根据持久化租约与现有
恢复矩阵决定动作。强制线程中断不能作为外部请求未发生的证据。

## 6. 指标契约

使用 Micrometer，并且只能使用有界枚举/布尔标签：

| Meter | 类型 | 有界标签 |
|---|---|---|
| `apvero.knowledge.index.build.claimed` | counter | `step` |
| `apvero.knowledge.index.build.queue.wait` | timer | `step` |
| `apvero.knowledge.index.build.step.duration` | timer | `step`, `outcome`, `error_category` |
| `apvero.knowledge.index.build.attempt` | counter | `step`, `attempt_bucket` |
| `apvero.knowledge.index.build.batch.items` | distribution summary | `outcome` |
| `apvero.knowledge.index.build.batch.units` | distribution summary | `quality`, `outcome` |
| `apvero.knowledge.index.build.entries` | distribution summary | `kind`, `outcome` |
| `apvero.knowledge.index.build.retry` | counter | `step`, `error_category` |
| `apvero.knowledge.index.build.stale.lease` | counter | `step`, `operation` |
| `apvero.knowledge.index.build.recovery` | counter | `action`, `outcome` |
| `apvero.knowledge.index.build.publication.validation` | counter | `outcome`, `error_category` |
| `apvero.knowledge.index.build.publication` | counter | `outcome` |
| `apvero.knowledge.index.build.inflight` | gauge | 无 |
| `apvero.knowledge.index.build.oldest.eligible.age` | gauge | 无 |
| `apvero.knowledge.index.build.reconciliation` | gauge | 无 |

允许值由编译期枚举定义。`attempt_bucket` 只能使用 `1`、`2`、`3`、`4_plus` 等固定集合，
不能使用原始或配置后的 attempt 值。数量 summary 记录数值，不记录 ID。

严禁把 tenant、workspace、Build、Index、Route、Provider request、Chunk、source、URL、
content、异常消息或 lease owner 放入标签。测试必须枚举每个已注册标签的 key/value，并阻止
身份型或无界标签进入。

运维 gauge 使用 Runner 扫描后更新的内存不可变快照，不能在每次 scrape 时执行无作用域查询。
第一次成功扫描前 oldest age 标记为不可用，负值归零。

## 7. 健康契约

新增独立的 `knowledgeIndexBuildRunner` health contributor，不混入解析 Worker 健康组件，
也不调用 embedding Provider。

健康详情仅包含有界字段：

- `featureEnabled`；
- `runnerEnabled`；
- `accepting`；
- `lifecycle`；
- `inFlight`；
- `oldestEligibleBuildAgeSeconds` 或 `unknown`；
- `reconciliationCount` 或 `unknown`；
- `lastScanOutcome`；
- `snapshotAgeSeconds`。

状态语义：

- 两个开关按配置关闭：`UP`，lifecycle 为 `disabled`；
- 已启用、正在接受且最后扫描新鲜：`UP`；
- 受控停机排空中：`UP`，lifecycle 为 `draining`；
- 已启用但作用域/领取扫描连续失败，或快照超过已记录的 `poll-interval` 倍数：`DOWN`；
- 存在需要 reconciliation 的 Build 不会让服务 `DOWN`，其数量是运维行动信号。

健康信息不暴露 ID、错误消息、Route、Endpoint 或 Provider 可用性。Readiness 表达本地
Runner 接受持久化工作的能力，不表达模型质量。

## 8. 运维快照查询

只新增 Knowledge 自有且按工作区限定的聚合读取：

- 最早可领取 Build；
- 需要 reconciliation 的终态 Build 数量。

Runner 对 Identity 公开目录返回的所有 scope 聚合结果，并发布一个本地不可变快照。查询条件
必须与 `claimBuilds` 使用相同的 eligible status、`next_attempt_at`、数据库时间与租约到期
规则，禁止创造第二套 eligibility 定义。

任一工作区扫描失败时，整个快照标记为 incomplete，不能用误导性的零值替代。不得导出 tenant
或 workspace 基数，也无需新表、物化视图或缓存服务。

## 9. 性能与支持范围

P2.2d 有意采用 PostgreSQL polling 和 O(entries) 发布验证。验收必须如实测量，不能宣传任意规模。

参考范围：

- 单 Build 最多 1,000 条 immutable Entry；
- 调度公平性测试覆盖 20 个工作区、最多 100 个 eligible Build；
- Runner concurrency 覆盖 1、4、8；
- 完整验证/发布事务覆盖 1、100、1,000 Entry。

CI 用明确超时验证正确性、有界完成与无死锁，并记录 queue、validation、publication 的观察时长；
共享 CI runner 的一次结果不能被写成通用延迟 SLA。任何更高规模声明都必须先测量并记录。

测试证明：

- 领取不超过本地配置容量；
- 持续负载下每个工作区最终都能被领取；
- 两个 worker 不会执行同一个有效租约；
- 最大参考语料下发布仍保持原子性；
- workspace 与 Build 数量增长时指标基数保持不变。

## 10. Compose 验收

Compose 增加显式 Build Runner 变量，同时保持安全默认：

- 基础 Compose：除非明确选择，否则 Knowledge 与 Build Runner 都保持关闭；
- Knowledge overlay：启用解析/ingestion 依赖，但不静默宣称生产 Build 已就绪；
- 验证命令显式启用 Build Runner 并使用确定性本地 Provider adapter，不需要付费 key。

验收脚本必须：

1. 构建或使用准确的被测镜像；
2. 启动 PostgreSQL、platform server 与所需本地 worker；
3. 等待容器及应用健康；
4. 创建或加载确定性的双工作区 fixture；
5. 通过已接受 API 请求相等及不同 Build；
6. 观察自动推进到一个 immutable READY Version；
7. 证明相等请求/发布重放不会产生重复；
8. 在 eligible/in-flight 工作期间重启 platform server，证明基于租约恢复；
9. 证明跨工作区读取与写入 fail closed；
10. 检查指标、健康必需字段与禁止身份信息；
11. 关闭 Build Runner 后证明不再发生新领取；
12. 保存命令、镜像 identity、测试摘要与脱敏运维样本。

Preview 或 demo 状态不能作为服务端成功证据。Compose 证据来自持久化 API、数据库与审计断言。

## 11. 安全错误与审计

- 已接受 Build API 继续 deny-by-default 授权；
- 每个 Repository 操作继续限定 tenant/workspace；
- Provider key、Base URL、向量、源内容或原始请求/响应不能进入健康、指标或普通日志；
- 稳定 backend code 仍是本地化边界，不新增硬编码用户可见消息；
- Build 请求、手动 retry/cancel、终态 reconciliation/failure 与成功发布保持现有审计策略；
- 定时领取与逐 batch 进度属于 typed state/metrics，不产生管理审计噪声；
- 指标与健康端点遵守现有 actuator 授权与暴露规则。

本切片不新增 secret、retention、egress 或插件权限行为。

## 12. 验证矩阵

### 12.1 Runner 聚焦测试

- 两层开关与所有生命周期转换；
- 无重叠 poll，领取不超过剩余容量；
- 工作区轮转公平性以及空/失败 scope 扫描；
- 精确状态到 Orchestrator 分派；
- 每次 claim 最多一个 Provider batch；
- 提交拒绝、执行器失败与安全租约到期；
- graceful drain 完成、超时与中断；
- stale owner 不能持久化 Runner 归一化失败；
- retry、不明 dispatch 与 reconciliation 路径；
- 指标名称、标签词汇与身份信息脱敏；
- 健康状态、快照陈旧判定以及不调用付费 Provider。

### 12.2 PostgreSQL 与恢复测试

- eligibility 排序使用数据库时间，并与 claim 语义一致；
- 租约到期、续租、stale worker 与双 Runner 竞争；
- 双工作区领取及聚合读取隔离；
- 自动完成 EMBEDDING → INDEXING → VALIDATING → READY；
- 在 P2.2d failure matrix 每个边界崩溃/重启；
- admission denial 发生在 Provider 调用之前；
- partial/extra/missing/wrong-lineage/dimension/digest artifact 永不发布；
- 相等发布重放与双 publisher 竞争保持确定性；
- audit 失败回滚发布；
- V11 全新安装与 V10 升级继续通过且无新 migration。

### 12.3 累计 Gate

- Spring Modulith 与 ArchUnit；
- 全部 Java unit/module/Testcontainers suite 与 `bootJar`；
- OpenAPI 与 JSON Schema 兼容性；
- Flyway migration 测试；
- TypeScript strict typecheck、unit test 与 Playwright 关键路径；
- 英文/简体中文 key 校验；
- Python 测试、Ruff 与类型检查；
- secret、dependency、image 与 source 扫描；
- Compose build、health 与确定性验收流程；
- 有界 runtime-path 性能证据。

P1 CHAT、P2.1 ingestion、P2.2a/b/c 以及 P2.2d-1 至 d-4 的行为必须全部保持绿色。

## 13. 双语证据

实现阶段生成相互匹配的：

- `docs/en/p2-2d-5-verification.md`；
- `docs/zh-CN/p2-2d-5-verification.md`。

两份文档必须包含相同的 commit 与 image identity、命令、环境、配置、测试数量、failure
matrix 结果、语料规模、观察时长、已知限制、回滚下限与未解决风险。英文是源文档，简体中文
必须提供同等功能覆盖，不能缩写成摘要。

证据必须区分：

- 自动化通过/失败证明；
- 本地/CI 测量结果；
- 继承自已批准 ADR 的架构断言；
- 留给后续阶段的限制。

只有两份文件均存在、parity check 通过且维护者接受记录证据后，P2.2d 才能标记完成。

## 14. 实施检查点

1. **d5.1——Runner 生命周期与有界分派**
   - 配置、双层开关、固定执行器、轮转工作区扫描、步骤分派、排空与聚焦单元测试。
2. **d5.2——运维遥测与恢复**
   - 工作区限定快照查询、指标、健康、安全失败归一化与 PostgreSQL 并发/恢复测试。
3. **d5.3——Compose、支持范围与最终证据**
   - 确定性端到端验收、参考语料测量、累计 Gate、匹配双语证据与阶段状态提案。

这些是未来一个 `feature/` 实施分支和 PR 中的连贯 commit。本 `docs/` 分支只包含规划。
在维护者验收 d5.3 前，不得把 P2.2d 改为 completed，也不得启动 P2.2e。

## 15. 发布与回滚

- 外层 Knowledge 与独立 Build Runner 开关保持 fail-closed 默认；
- rollout 从确定性本地能力、concurrency 1 与小规模观察语料开始；
- 提高并发前，运维人员检查 queue age、reconciliation count、failure 与 publication outcome；
- 关闭 Build Runner 时先停止新 claim，只在配置时限内排空；
- 关闭不会 cancel、删除或改写 Build、Entry、Version、Governance 或 audit 证据；
- READY Version 出现前可保留 V11 并运行上一兼容 binary；
- READY 出现后遵循 ADR-0006 的 P2 兼容回滚下限；
- 回滚绝不解析可变 `latest`，也不降级到无知识依据的生产 chat。

## 16. 自我批判与拒绝的捷径

1. 扫描所有工作区只在当前 Identity catalog 范围内有界。轮转游标避免首工作区饥饿，但超大安装
   最终需要经过证明的分区边界；P2 不伪装已经解决。
2. 进程暂停后内存健康快照可能陈旧。显式 snapshot age 和 `unknown` 比每次 scrape 做昂贵的
   无作用域查询更诚实。
3. 固定平台线程限制本地资源，却不能让 Provider 调用恰好一次。不明 dispatch 继续要求
   reconciliation。
4. 优雅停机无法证明超时的外部请求已经停止，因此拒绝用中断推导成功或盲目重试。
5. 完整发布验证是 O(entries) 且持锁。1,000 Entry 参考范围是经过测量的 P2 支持声明，不是永久
   架构上限或通用 SLA。
6. PostgreSQL polling 更适合默认自托管，但不是 Kafka 级调度。没有实际边界与 ADR 前拒绝加队列。
7. reconciliation count 是行动信号，不是 liveness failure。仅因需要人工处理就把应用标为 DOWN
   会制造重启循环而无法修复证据。
8. 与 ingestion 共用一个通用 Runner 抽象会隐藏不同恢复语义。复用模式与公开 seam 比强行共用
   框架更安全。
9. 缺少严格词汇测试的指标很容易在事故调试中加入 ID。基数与脱敏测试属于正确性，不是可选美化。
10. READY 只证明结构可复现。检索相关性、排序与评测仍属于 P2.2e。

明确拒绝：数据库全局 claim、无界虚拟线程、Provider 健康探测、可变发布、伪造停机成功、原始
异常标签、mock Compose 成功、付费 key 验收、提前开放 Version API，以及提前启动 Retrieval Lab。

## 17. 批准 Gate

维护者批准本计划只授权所述 P2.2d-5 实现，不授权 P2.2e、前端启用、新 migration/table/queue/
deployable/stateful dependency、公开契约变更、第二 AI 框架、Provider 类型进入核心 API 或新的规模声明。
