# P2.2e-3 受治理检索执行验证

状态：本地验证完成的实施检查点；里程碑推送与 GitHub CI 延后到完整 P2.2 验证候选。

## 范围

P2.2e-3 把一次查询接入现有受治理 Embedding 路径：

```text
验证查询并计算摘要
  -> 加载作用域限定的 Policy、READY Version 与固定 Build
  -> 解析并报价精确 Embedding Route
  -> 准入 KNOWLEDGE_QUERY / EMBEDDING_QUERY
  -> 调用一次查询 Embedding
  -> 结算一次或要求对账
  -> 执行 P2.2e-2 精确排序内核
```

本检查点尚不应用重叠折叠、上下文预算、当前保留策略披露、最终
`MATCHES`/`NO_EVIDENCE` 投影、REST 授权、遥测 Dashboard 或产品页面。这些仍属于
P2.2e-4 与 P2.2e-5。

## 架构结果

- P2 与 P2.2e 继续保持 `in-progress`。
- Knowledge 归属该编排，只使用 Identity、Capability Registry 和 Governance 公共 API。
- Provider SDK、Spring AI、jOOQ Record、Secret 与 Provider 选项不会进入 Knowledge
  公共边界。
- 没有修改迁移、表、有状态依赖、可部署单元、队列、框架、REST 契约或页面。
- `EmbeddingCapability` 仍是唯一 Provider-neutral 执行 SPI。
- Provider 调用时没有打开 Knowledge 事务；Governance 操作继续使用已有的窄事务边界。

## 查询与固定制品校验

执行器会：

- 在解析任何 Live 工作流前要求 Knowledge 已启用；
- 解析认证后的 Workspace 作用域；
- 只剥离边界空白，拒绝空输入与超过 20,000 个 Unicode Code Point 的输入，其余查询
  字节保持不变；
- 只保存和返回规范化 UTF-8 查询的 `sha256:` 摘要；
- 在同一 Workspace 加载精确 Policy Version、READY Index Version 及其 READY Build；
- 校验 Build、Version、Route ID、精确 Route 引用、向量维度、输入上限、批次上限与
  Normalization；
- 在准入前拒绝不可用 Route 或固定配置漂移；
- 拒绝估算输入超过固定 Route 上限的查询。

查询正文只发送给选定的 Embedding Adapter，不写入 Knowledge 或 Governance 表、
Component Identity、Trace Identity、异常或正常日志。

## 治理与幂等

操作先报价再准入，并创建一条：

- Subject：`KNOWLEDGE_QUERY`；
- Component：`EMBEDDING_QUERY`；
- 确定的请求作用域 Component Identity；
- 精确固定的 Route ID 与引用；
- 估算用量、成本与币种。

Identity 摘要包含 Tenant、Workspace、Index Version、Policy Version、调用者 Trace 与
查询摘要。相同请求 Trace 会收敛到已有 Reservation。已经结算的请求不会再次计费或调用；
由于查询向量被有意设计为不持久化，重放返回稳定的
`APVERO_KNOWLEDGE_QUERY_ALREADY_SETTLED` 冲突，而不是伪造可复现响应。

准入拒绝发生在 `markDispatched` 与 Provider 调用之前。

## Provider 与结算行为

系统只发送一个输入，其中包含：

- 精确固定的 Route 引用；
- 确定的 Execution 与 Item Identity；
- 不带前缀的 SHA-256 输入摘要；
- 经过边界 trim 的查询。

结果必须与该输入精确映射，并匹配 Route ID、引用、维度和币种。返回向量已经由
Capability Registry 校验，进入 SQL 排序前还会由精确内核再次校验。

所有结果均失败关闭：

- 成功时优先结算实际用量，不可用时采用保守估算用量；
- 明确 Provider 失败使用规范化稳定错误码结算一次失败；
- 在要求对账的 Adapter 上，超时或其他付费结果不明确的情况进入
  `RECONCILIATION_REQUIRED`；
- 已存在的 `DISPATCHED` Component 永远不会被盲目重放；
- Provider Identity 或结算账本在 Provider 返回后失败时，返回
  `APVERO_KNOWLEDGE_QUERY_SETTLEMENT_CONFLICT`，并保留持久证据供对账；
- 没有任何实现路径会自动重试 Provider。

## 验证证据

单元与协议桩测试证明：

- 从 Route 解析到排序的精确调用顺序；
- 边界 trim 与仅摘要 Component Identity；
- 准入拒绝发生在调用前；
- 一次 Provider 调用与一次成功结算；
- 明确失败以估算用量结算；
- 模糊结果与既有 Dispatch 进入对账；
- Dispatch 后不重放 Provider；
- Provider 返回后 Ledger 补充失败时安全失败。

PostgreSQL/Testcontainers 集成测试使用真实确定性本地 Embedding Adapter、真实
Capability Registry Route 解析、真实 Governance Reservation/Component 持久化以及真实
pgvector 精确排序内核。它证明：

- 返回一个分数为 `1.0` 的不可变匹配 Chunk；
- 一条 `KNOWLEDGE_QUERY` Reservation 到达 `SUCCEEDED`；
- 一条 `EMBEDDING_QUERY` Component 以估算用量和本地零成本到达 `SUCCEEDED`；
- 确定性路径不保留原始查询或 Provider Request Identity；
- 使用相同 Trace 重放不会创建第二条 Reservation 或第二次 Provider 费用。

## 已执行验证

本地通过：

- Knowledge 完整模块测试；
- 受治理执行协议桩测试；
- 真实 PostgreSQL 18、Governance、确定性本地 Embedding 与 pgvector 集成；
- Platform 测试编译。

依据仓库推送策略，里程碑级架构、OpenAPI、Compose、安全和完整 CI 验证延后到组装完成的
P2.2 候选。

## 回滚

- 回退 P2.2e-3 本地实施提交或使用上一个兼容二进制；
- 保留已有不可变 Index、Policy 与 Governance Ledger 行；
- 不需要迁移或数据回退；
- Knowledge 继续默认关闭；
- 没有端点或产品页面变为 Live。

## 退出声明

P2.2e-3 在以下条件满足时完成本地检查点：

> 一次授权且作用域限定的查询使用精确 Index Version 固定的 Embedding Route，在调用前
> 完成报价与准入，最多调用 Provider 一次，对明确结果结算一次，对模糊结果要求对账，
> 并把一个校验后的向量交给确定性精确排序，同时不保留原始查询，也不静默重放付费工作。

下一检查点是 P2.2e-4 策略应用与披露。P2.2e 与 P2.2 继续保持 `in-progress`。
