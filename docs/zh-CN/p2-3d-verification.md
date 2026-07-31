# P2.3d Grounded Runtime 编排验证候选

状态：维护者已于 2026-07-31 验收。P2.3 继续保持 `in-progress`；P2.3d 为
`completed`。

## 范围

P2.3d 为不可变 Manifest 1.1 RAG Release 实现以下有界工作流：

```text
解析准确 ReleaseBundle
  -> 持久化 RUNNING Run
  -> 按声明顺序解析每个准确 Knowledge Binding
  -> 执行受治理检索并持久化每次经过 Retention 的证据结果
  -> 构建一个有界的不可信证据 Context
  -> 没有可用 Hit 时返回类型化 NO_EVIDENCE，且不预留 Chat
  -> 否则预留并派发一个受治理 Chat Component
  -> 执行选定 Runtime Provider
  -> 结算已知结果，或对不明确结果要求 Reconciliation
  -> 在 P2.3e 验证结构化 Answer 与 Citation 前失败关闭
```

P2.3d 不宣称模型 Answer 或 Citation 有效。结构化解析、Citation 验证、授权 Locator 与
Grounded Answer 成功完成仍属于 P2.3e。

## 权威与边界

- 阶段：P2 / P2.3 / P2.3d。
- 所有者：Runtime。
- 获准依赖：仅 Release、Capability Registry 与 Knowledge 公共 API。
- Runtime 在执行前解析不可变 Release，不读取可变 Application Draft。
- Runtime 不直接查询 Knowledge、Governance 或 Capability Registry 表。
- Spring AI 继续作为唯一 Java 核心 AI 抽象。
- 未新增 Deployable、队列、数据库、框架或强制状态依赖。
- ADR-0006 已批准 P2.3 与 V13 持久化基线。

## 准确执行身份

Release Manifest 继续作为执行 Binding 的唯一来源。Knowledge 提供 Workspace 作用域的
准确规范 Index 与 Retrieval Policy 引用公共解析器。证据进入模型 Context 前，Runtime
验证 Tenant、Workspace、准确引用、READY Index 状态、受支持 Policy 与返回的检索身份。

Binding 按 Manifest 顺序串行执行。每次完成的 Retrieval 会先提交 Sequence 与全局 Marker，
再开始下一个 Binding。如果后续 Binding 失败，Run 会失败，但此前证据继续可检查；系统
不会改写历史，伪装成检索从未发生。

## Context 与注入安全

只有 Retention 允许披露且非空白的 Hit Content 才能进入模型 Context。全局 Context 最多
128 个 Hit、100,000 个 UTF-8 字节。Marker 在过滤和预算后分配，因此持久化证据与
Provider 可见证据保持相同 `[K1]` 顺序。

Evidence 作为 JSON Data 放在明确边界内。Spring AI Adapter 增加 System Instruction：
Evidence 不可信、不得遵循 Evidence 内指令、不得从 Evidence 推导 Capability。Provider
SDK 类型不会进入 Runtime Domain Contract。

## 生命周期、治理与失败语义

在尚未发布的 P2.3 里程碑对外发布前，V13 进行了增量加固：

- `RUNNING` 成为允许的 Run 生命周期状态；
- Run Identity 与 Input 不可变；
- 只有 RUNNING Run 可以绑定 Chat Execution Identity 或进入终态；
- Terminal Run 继续不可变；
- Transaction-scoped Retention Purge 之外继续禁止普通删除。

执行编排有意不使用外层 Read-only Transaction。Lifecycle 与 Evidence 步骤独立提交，
确保后续外部步骤失败时仍保留 Evidence 与 Reconciliation State。

Chat Generation 使用 P1 Execution-component Ledger。ADR-0006 要求每个终止路径都必须
结算或释放 Reservation，因此确定性的派发前失败会把 Component 从 `RESERVED` 转为
`RELEASED`，Usage 与 Cost 均为零，不伪造 Dispatch Timestamp，并记录可审计 Failure Code；
外部调用结果不明确时，Component 进入 `RECONCILIATION_REQUIRED`，Run 写入稳定的
`APVERO_EXTERNAL_OUTCOME_RECONCILIATION_REQUIRED`，而不是猜测 Cost 或 Success。

检索没有产生可用 Evidence 时，Runtime 返回：

```json
{"schemaVersion":"1.0","status":"NO_EVIDENCE","answer":"","citations":[]}
```

此时不会创建 Chat Reservation，也不会调用 Provider。有 Evidence 且 Provider 返回后，
P2.3d 结算已知 Usage 与 Cost，按策略保留原始 Output，并以
`APVERO_GROUNDED_OUTPUT_VALIDATION_PENDING` 终止 Run。这是有意的失败关闭，不是模拟成功。

## Retention 与遥测

执行时 Retention Decision 连同 Version 一起传递。Input、Output 与 Evidence Content 在
持久化前被省略或递归遮蔽。Metric 仅使用有界 Outcome 与 Provider 值；Prompt、Evidence、
Reference、Tenant ID、Workspace ID、Actor ID 与 Trace ID 都不是 Metric Tag。

## 验证证据

候选测试覆盖：

- 类型化 NO_EVIDENCE，且没有 Chat Reservation；
- 递归遮蔽敏感字段，同时不破坏类型化 NO_EVIDENCE Envelope；
- 准确且有序的 Binding 解析；
- 后续 Binding 无法解析时保留此前 Evidence；
- 确定性全局 Marker 与全局 UTF-8 Byte/Hit 预算；
- 恶意 Evidence 继续作为 JSON Data，而不是控制指令；
- 受治理 Chat 结算与明确的 Validation-pending 失败关闭；
- 派发前释放 Reservation，且不伪造 Provider Dispatch；
- Terminal Run 不可变与 V13 Retrieval 完整性；
- Manifest 1.1 RAG Provider 兼容，且不静默回退到 CHAT；
- Spring Modulith 公共边界验证。

本地已执行：

- 完整 Gradle 测试套件与可启动 Platform Server JAR；
- 最终 P2.3d、P2.3c、P1 Governance、P2.2 Embedding、Delivery Stage 与 Module Boundary
  定向回归；
- TypeScript 严格类型检查、Console 单元测试与生产构建；
- 英文与简体中文校验，每个必需 Locale 均为 405 个 Leaf Key；
- OpenAPI 3.1 Lint，仅保留既有 Platform Info 与 Worker Health 缺少 4xx 的两个 Warning；
- 默认与 Knowledge Profile Compose 配置验证。

生产准确检索 Benchmark 继续明确为选择性执行并被跳过。P2.3d 不改变 P2.2 Retrieval SQL
热路径。

## 回滚

P2.3 发布前，可关闭 Manifest 1.1 RAG 执行或恢复上一里程碑二进制，同时保留 V13。回滚前
必须协调或管理性关闭已有 RUNNING Run。一旦存在 P2.3d 数据，V13 表与生命周期约束就是
数据兼容下限。

## 已知限制

1. 在 P2.3e 前有意不开放 Grounded 成功完成。
2. Citation 验证与授权 Source Locator 仍属于 P2.3e。
3. Console 闭环与兼容加固仍属于 P2.3f。
4. P2.3e 与 P2.3f 通过验证门前，P2.3 仍未完成。

## 退出声明

维护者已于 2026-07-31 验收 P2.3d，并确认：

> Apvero 能执行不可变且有序的 RAG Binding，保留受治理 Evidence，限制并隔离不可信
> Context，在不调用 Generation 的情况下返回类型化 NO_EVIDENCE，释放派发前
> Reservation，并协调不明确的外部结果，同时不宣称结构化 Answer 或 Citation 已经有效。
