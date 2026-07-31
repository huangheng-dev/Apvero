# P2.3c 作用域 Run 检索证据账本验证候选

状态：维护者已于 2026-07-31 验收。P2.3 继续保持 `in-progress`；P2.3c 为
`completed`。

## 范围

P2.3c 闭合以下有界 Runtime 证据工作流：

```text
锁定一个 Workspace 作用域 Run
  -> 要求下一个连续检索序号
  -> 接收一个已经过治理的 Knowledge 检索结果
  -> 在持久化前应用本次执行的 Retention 决策
  -> 分配确定性的全局 [K1] 标记
  -> 在一个事务内保存检索及全部有序 Hit
  -> 只能通过 Run 与 Workspace 作用域读取保留投影
```

本阶段不调用检索、不构建模型上下文、不调用模型、不验证 Answer，也不把 Citation 标记为
有效。这些行为仍属于 P2.3d 与 P2.3e。

## 权威与边界

- 阶段：P2 / P2.3 / P2.3c。
- 所有者：Runtime。
- 新增的获准依赖：仅 Knowledge 公共 API。
- Runtime 不查询 Knowledge 表，只保存来自不可变 Release 执行上下文的不透明准确版本
  ID 与规范引用。
- V13 是 ADR-0006 已批准的增量迁移，新增 `ai_run_retrieval`、
  `ai_run_retrieval_hit` 与可空的 `ai_run.failure_code`。
- 未新增 Deployable、框架、队列、数据库或强制依赖。

## 首次实现前的契约修正

原 `contract-only` Run 检索响应组合了 `KnowledgeRetrievalResult`。该结构无法承载确定性
证据 Marker、准确规范引用、Retention 来源或 Citation 验证状态；它还继承了封闭的
Knowledge Hit 对象，因此通过 JSON Schema 组合增加 Run 专属字段本身无效。

P2.3c 在接口上线前把它改为专用的 `RunRetrievalExecution` 与 `RunRetrievalHit` 投影。
已实现响应现在包含：

- 全局确定性 Marker；
- 不透明 Index 与 Retrieval Policy ID；
- 对应准确规范引用；
- Query 与 Content Digest；
- 有序 Source Lineage 与 Anchor；
- 有界且经过 Retention 过滤的 Content；
- Retention Decision Version；
- Citation Validation 状态。

`GET /api/v1/runs/{runId}/retrieval` 现在是 `baseline`。
`GET /api/v1/runs/{runId}/citations` 继续明确保持 `contract-only`。

## 持久化与完整性

V13 强制：

- 通过组合 Run 外键实现传递式 Tenant 与 Workspace 作用域；
- 应用层连续序号检查及唯一 `(run_id, sequence)`；
- 唯一 `(run_id, marker)` 与 `(retrieval_id, rank)`；
- 有界 Sequence、Rank、Score、Latency、Hit Count、Anchor、Source Type、Digest 与准确引用
  形状；
- `MATCHES` / `NO_EVIDENCE` 与 Hit Count 一致；
- Retrieval 行以及 Hit Identity/Content 不可变；
- 只为后续保留 `citation_validated` 从 false 到 true 的转换；
- 只允许既有 Runtime 事务级 Retention Purge 删除。

Repository 在计算 Sequence 与 Marker 顺序前锁定 Run，避免并发写入者分配重复证据身份。
父行与全部 Hit 要么一起提交，要么一起回滚。

## Retention 与安全

Writer 接收本次执行的 Retention 决策；只有启用 Payload Retention 且未启用
Sensitive-field Masking 时才保存 Hit Content，否则 Content 保存为 null，同时保留用于
可复现性的不可变 Digest、Source Lineage、Rank、Score 与 Anchor。数据库不保存 Source
Path、Object-store Path、Secret、Credential 或持久授权 URL。

读取必须同时提供 Run ID 与 Workspace 作用域。其他 Workspace 的 Run 与不存在的 Run
表现一致。API 不接受调用方提交的 Tenant 身份。

## 失败语义

V13 增加可空 `ai_run.failure_code`，不重写历史 Run。新的 Provider 失败把稳定机器可读
Runtime Code 与有界 Category、可安全展示 Message 分开保存。证据失败使用稳定的
Bad-request、Not-found 或 Conflict Code。

## 验证

证据覆盖：

- 通过应用上下文执行 V1 到 V13 干净迁移；
- 真实 V12 到 V13 升级并保留历史 Run 数量；
- 多次检索有序持久化与全局 `[K1]`、`[K2]` Marker；
- 插入前执行 Content 保留与遮蔽决策；
- 类型化 `NO_EVIDENCE` 持久化；
- 错误 Workspace 不披露；
- 序号跳跃拒绝且无部分行；
- 后续 Hit 无法保存时回滚父行及此前 Hit；
- 数据库拒绝 Retrieval 与 Hit Identity 修改；
- 受控 Retention 级联清理以及普通删除保护；
- Controller 到公共边界映射；
- OpenAPI 实现状态与专用证据 Schema；
- Spring Modulith 依赖验证。

本地已执行：

- 完整 Gradle 测试套件与可启动 Platform Server JAR；
- 增加数据库延迟完整性保护后的最终 Runtime、V13 干净/升级、Controller、错误、
  Delivery Stage 与模块边界定向套件；
- TypeScript 严格类型检查与 Console 单元测试；
- 英文与简体中文 Key/Placeholder 校验；
- OpenAPI 3.1 Lint，仅保留既有 Platform Info 与 Worker Health 缺少 4xx 的两个 Warning；
- 默认与 Knowledge Profile Compose 配置验证。

生产准确检索 Benchmark 继续明确为选择性执行并被跳过。P2.3c 改变的是检索后的证据
持久化，不改变 P2.2 检索 SQL 热路径。

## 回滚

存在证据行前，可回滚到上一 P2.3b 二进制。存在证据后，回滚下限是保留 V13 并继续受控
Run Retention 的 P2 兼容二进制。新增表与可空 Failure Column 在未使用时仍安全。关闭
Grounded Runtime 执行可阻止新证据写入，而不修改已保留证据。

## 已知限制

1. P2.3c 不开放 Console 页面。
2. Retrieval 编排与有界 Context 构建仍属于 P2.3d。
3. Citation 验证与授权 Locator 仍属于 P2.3e。
4. 在 P2.3f 兼容与闭环验证通过前，P2.3 仍未完成。

## 退出声明

维护者已于 2026-07-31 验收 P2.3c，并确认：

> Apvero 能为一个 Workspace 作用域 Run 持久化并检查完整、有序、不可变、经过
> Retention 过滤的检索证据，具备稳定全局 Marker 与事务失败行为，同时不宣称
> Grounded 执行或 Citation 验证已经上线。
