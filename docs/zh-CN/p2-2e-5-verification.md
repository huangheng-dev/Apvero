# P2.2e-5 精确检索实验室验收候选

状态：维护者已于 2026-07-30 验收。P2.2 继续保持 `in-progress`；P2.2f 验收加固现已启动。

## 验收范围

P2.2e 只闭合以下实验室工作流：

```text
发布不可变 Retrieval Policy
  -> 授权精确 READY Index Version 与 Policy Version
  -> 对查询 Embedding 报价并准入
  -> 调用并结算一次
  -> 执行 Workspace 作用域精确余弦排序
  -> 应用确定性重叠与上下文预算
  -> 应用当前保留披露策略
  -> 返回有界 MATCHES 或类型化 NO_EVIDENCE
```

它不生成答案、不绑定 Application Draft、不写生产 Run 证据、不启用 Knowledge 产品页面，
不增加混合或近似检索，也不发布未经测量的规模声明。

## 权威与架构

- 阶段：P2 / P2.2 / P2.2e。
- 归属模块：Knowledge。
- 只使用允许的 Identity、Capability Registry 与 Governance 公共 API。
- 禁止的模块内部实现与 Provider SDK 类型不会进入 Knowledge 边界。
- PostgreSQL 18 与 pgvector 仍是唯一必需的有状态依赖。
- 没有新增表、迁移、队列、可部署单元、框架、发布语义或公共 Schema 形状。
- `architecture/modules.yaml` 已把获准的 Retrieval Service 记录为现有 Knowledge
  边界内的已实现能力。
- Retrieval Policy 与 Retrieval Lab OpenAPI 操作已移除过期的 `contract-only` 标记；
  请求与响应 Schema 没有变化。
- 产品页面继续保持非 Live，本切片无需新增前端语言键。

本次没有受保护变更，不需要新 ADR。

## 验收矩阵

### 架构

Spring Modulith 验证模块化单体与 Knowledge 获准依赖。Repository 架构测试继续阻止持久化
实现跨越模块边界。禁止的 Provider 库仍未进入 Core。

### 不可变策略

发布测试证明：

- 精确公共范围与平台分配的算法身份；
- 规范摘要与持久 Retention Policy 来源版本；
- 相同版本幂等重放；
- 相同版本改变行为时冲突；
- 另一身份复用相同摘要时冲突；
- 并发首次发布收敛；
- 只插入持久化与一条安全管理审计；
- 审计写入失败时事务整体回滚。

### 精确 SQL

排序 Repository 使用一条语句，在排序与限制之前应用 Tenant、Workspace、READY Version、
Build、Entry、维度、阈值和精确 `topK` 条件。PostgreSQL Distance 与 Chunk UUID 决定
顺序。代表性 `EXPLAIN ANALYZE` 证明有界索引访问和 `Limit`；该证据不被解释成任意规模
能力声明。

### 隔离

同 Tenant 不同 Workspace 与不同 Tenant 的内核测试都会得到相同作用域 Not Found。
完整 REST 链路还证明：即使管理员掌握真实外部 Version ID，通过另一 Workspace Header
调用也只返回 `APVERO_KNOWLEDGE_INDEX_VERSION_NOT_FOUND`，且不会在外部 Workspace
创建 `KNOWLEDGE_QUERY` Reservation。

### 历史检索

新 Build 已排除 Tombstoned Source。精确检索有意不连接当前 Source 状态。内核与完整
REST 测试证明：Source Tombstone 后，已经发布的 READY Index Version 仍返回相同不可变
Chunk。

### 治理与失败语义

测试证明先报价后准入、拒绝发生在调用前、单一 Reservation/Component、单次 Provider
调用、成功结算、明确失败结算、模糊结果对账、Dispatch 后不盲目重放，以及安全的结算
冲突行为。重用已结算 Trace 不会创建第二次 Reservation 或费用。

### 确定性

证据覆盖数据库 Tie Break、阈值与精确 `topK`、同一不可变 Document 内重叠、首尾相接
范围、不同 Document、完整正文预算、超大 Hit 跳过、后续 Hit 继续候选、最终连续 Rank、
英文、简体中文、混合 UTF-8 和 20,000 Unicode Code Point 边界。

### 保留与披露

系统在读取时应用当前 Governance Retention Policy。禁止保留正文或要求掩码时，正文会
在预算与观测前被抑制。响应只含契约规定的有界血缘、分数、摘要与安全锚点。测试拒绝或
省略字符偏移、原始 URL/路径、对象键、Secret、向量与 Provider Identity。持久身份只
保留查询 SHA-256，正常日志与指标标签不含原始查询。

### 稳定错误与空证据

能力关闭、错误身份/查询、作用域 Not Found、固定制品无效、准入拒绝、Provider 失败、
模糊结果、向量校验、重放与结算冲突均使用稳定失败族。排序为空或后处理后为空时成功返回
类型化 `NO_EVIDENCE`，绝不授权无依据回答。

### 遥测

P2.2e-5 新增 Retrieval 专用 Micrometer 证据：

- `apvero.knowledge.retrieval.request`；
- `apvero.knowledge.retrieval.latency`；
- `apvero.knowledge.retrieval.provider.latency`；
- `apvero.knowledge.retrieval.hits`；
- `apvero.knowledge.retrieval.score`。

标签只允许有界 Outcome、Failure Family、Hit Kind 与粗粒度 Score Bucket。重复请求身份
不会增加指标基数。Tenant、Workspace、Route、Index、Policy、查询、正文、URL 与
Provider Request Identity 永不成为标签。Governance 继续作为持久计费证据；高频检索
不会制造管理审计噪声。

### 契约与安全

Controller 反射与 OpenAPI 解析覆盖每个已实现 Knowledge Method/Path。Retrieval Policy
与 Retrieval Lab 使用已提交的 OpenAPI 3.1 形状。检索执行属于 POST，需要 `write` 或
`admin`；`read` API Key 在业务执行前被拒绝。后端响应使用稳定错误码供客户端本地化。

### 部署与回滚

默认与 Knowledge Profile Compose 配置保持有效。Knowledge 默认关闭。PostgreSQL/
pgvector 是唯一必需的有状态依赖。回滚使用上一个兼容二进制，保留不可变 Policy、Index
与 Governance 行，不需要逆向迁移；关闭 Knowledge 后 Retrieval Lab 恢复为不可用。

## 已执行验证

本地通过：

- Knowledge 完整模块测试，包括 Repository 架构与 Retrieval 遥测；
- Spring Modulith 验证；
- Retrieval Policy、Retrieval Lab 与 OpenAPI Controller 一致性；
- Platform Server 可启动 JAR；
- P2.2e-1 真实 PostgreSQL 策略发布、并发、审计、隔离与回滚套件；
- P2.2e-2 真实 PostgreSQL/pgvector 排序、作用域、历史与查询计划套件；
- P2.2e-3/e-4/e-5 真实 REST、认证、Governance、确定性 Embedding、保留、跨 Workspace、
  Tombstone 历史与遥测套件；
- OpenAPI 3.1 校验；
- 默认与 Knowledge Profile Compose 配置校验；
- Source Diff 与禁止依赖检查。

三组 Testcontainers 套件被有意拆分为独立 Gradle 调用。这样能提供相同的隔离数据库证据，
又不会依赖本地 Docker Desktop 在一个 JVM 内长期保留多个 Spring 数据库上下文的容量。
完整 P2.2 候选推送后，GitHub CI 是权威的干净主机里程碑验证。

## 保留的已知限制

1. 精确余弦检索是确定性的，不是混合检索。
2. SQL 使用精确 `topK`；策略过滤后不存在隐藏补位。
3. 确定性本地 Embedding Adapter 证明编排，不证明语义质量。
4. 由于没有获准的共享 Masker，敏感非结构化正文采用抑制。
5. Retrieval Lab 是同步操作，不持久化独立查询行。
6. Corpus 大小与并发支持边界属于 P2.2f。
7. Application 绑定、不可变 ReleaseBundle 固定与带引用答案属于 P2.3。

## 退出声明

维护者确认以下证据后，P2.2e 可以验收：

> 在一个授权 Workspace 内，Apvero 可以发布不可变 Retrieval Policy，并与精确 READY
> Index Version 一起执行受治理、确定性、PostgreSQL 作用域余弦查询，返回有界且当前
> 获准的证据或类型化 NO_EVIDENCE，同时不存在跨 Workspace 泄漏、原始查询保留、隐藏
> 排序行为、重复成本结算或不受支持的质量声明。

维护者已于 2026-07-30 验收本证据。P2.2e 已完成，P2.2 继续保持 `in-progress`，
P2.2f 验收加固现已启动。
