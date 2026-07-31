# P2.3e 结构化 Answer 与 Citation 验证候选

状态：维护者已于 2026-07-31 验收。P2.3 继续保持 `in-progress`；P2.3e 为
`completed`。

## 范围

P2.3e 闭合 P2.3d 引入的 Grounded Answer 成功路径：

```text
不可变 Run Evidence
  + 只包含 Answer Text 与 Evidence Marker 的严格 Provider Draft
  -> 解析并验证准确的内部 Draft Shape
  -> 拒绝格式错误、重复、未知或伪造 Marker
  -> 从 Run Evidence 派生 Grounded Answer 1.0 与 Citation 1.0 身份
  -> 原子验证被引用 Evidence 并完成 Run
  -> 通过读取时 Locator 暴露 Workspace-scoped 已验证 Citation
```

本切片不允许模型编写 Source Identity、Lineage、Score、Anchor 或 Locator。

## 权威与边界

- 阶段：P2 / P2.3 / P2.3e。
- 所有者：Runtime。
- 获准依赖：Application、Release、Capability Registry 与 Knowledge 公共 API。
- Runtime 只访问自身的 Run 与 Retrieval Evidence 表。
- 未新增跨模块 SQL、Deployable、队列、数据库、框架或有状态依赖。
- 不需要迁移。已验收的 V13 从未验证 Evidence 到已验证 Evidence 的转换就是持久化下限。
- ADR-0006 已批准结构化 Answer 验证、Citation 1.0 与读取时 Locator。

## Provider Draft 与公开 Output

Provider 被要求只返回一个恰好包含四个字段的 JSON Object：

```json
{
  "schemaVersion": "1.0",
  "status": "GROUNDED",
  "answer": "员工每晚最多可报销 500 元。",
  "citationMarkers": ["[K1]"]
}
```

Runtime 拒绝 Wrapper、额外字段、错误 Status 或 Schema Version、空白或超长 Answer、
非 String Marker、空或超量 Marker Set、重复 Marker、格式错误 Marker，以及不存在于同一
Run 保留 Evidence 中的 Marker。

成功的公开 Grounded Answer 由 Runtime 重新构建。Citation Metadata 来自不可变 Evidence
Hit 及其准确 Index Version Reference。持久化 Answer 不包含 Locator。只有经过验证的
Answer 实际引用的 Evidence Hit 才会变成 `citation_validated=true`。

## 原子性、生命周期与成本

验证会锁定 Workspace-scoped Run，并读取其保留 Evidence。Citation 验证与 Run 转换为
`SUCCEEDED` 共用一个事务。数据库失败不会留下“Citation 已成功但 Run 未成功”的状态。

受治理 Provider Call 在 Answer 验证前完成结算，因为外部 Usage 与 Cost 已经确定。
格式错误 Answer 以 `APVERO_GROUNDED_OUTPUT_INVALID` 失败；无效 Evidence Marker 以
`APVERO_CITATION_VALIDATION_FAILED` 失败。两者都保留已知 Token Usage 与 Cost，不在失败
路径持久化不可信 Raw Output，并使用有界 Telemetry Outcome。

## Citation 读取与 Locator 安全

`GET /api/v1/runs/{runId}/citations` 现在是 Runtime Baseline。它：

- 要求经过认证的 Workspace Scope；
- 只返回已经标记为 Citation Validated 的 Evidence；
- 按 Retrieval Sequence 与 Hit Rank 保持确定性 Evidence 顺序；
- 在读取时根据 Source Revision 身份与 Anchor 构造相对 Platform Locator；
- 不返回 Local Path、Object-store Key、Provider URL 或模型提供的 Locator；
- Workspace 不拥有 Run 时，以 Run Not Found 失败关闭。

相对 Locator 被解引用时，仍由 Knowledge Source Revision Content Endpoint 执行 Workspace
授权。

## 契约与 Retention

Grounded Answer 1.0 与 Citation 1.0 从 `contract-only` 升级为 Runtime `baseline`，
Citation List OpenAPI Operation 同步升级为 `baseline`。`NO_EVIDENCE` 继续作为 P2.3d 的
类型化结果。Retention Policy 继续控制是否保留结构化 Run Output。Citation Lineage
独立保留在 Evidence Ledger 中；系统不会持久化长期 Locator。

## 验证证据

候选测试覆盖：

- 严格的有效 Provider Draft 解析；
- 按请求 Marker 顺序从 Evidence 派生 Citation Identity；
- 拒绝格式错误与包含额外字段的 Draft；
- 拒绝重复与伪造 Marker；
- 成功原子验证 Citation 并完成 Grounded Run；
- Citation 验证失败时保留已知 Usage 与 Cost；
- Grounded Output 不持久化 Locator；
- 根据保留 Anchor 生成授权 Locator；
- Citation List 跨 Workspace 失败关闭；
- Citation Endpoint 与 JSON Schema Baseline 声明；
- Controller 委托且不接受 Tenant Input。

本地已执行：

- 完整 Gradle 测试集：94 个 Suite 共 325 个 Test，0 Failure、0 Error，并按预期跳过需显式
  启用的 Exact Retrieval Benchmark；
- 可启动的 Platform Server JAR；
- TypeScript 严格类型检查、5 个 Console 单元测试与生产构建；
- English 与简体中文校验，每个必需 Locale 均为 405 个 Leaf Key；
- OpenAPI 3.1 Lint，仅保留既有 Platform Info 与 Worker Health 缺少 4xx 的两个 Warning；
- 默认与 Knowledge Profile Compose 配置验证；
- Git Whitespace 校验无误。

## 回滚

P2.3 里程碑发布前，可关闭 Manifest 1.1 RAG Execution，或恢复 P2.3d Binary 并保留 V13。
降级 Binary 仍可读取 Run 与 Evidence，但不能声称新验证的 Citation 已形成受支持的完整
工作流。已有 Terminal Run 继续保持不可变。

## 已知限制

1. P2.3f 仍负责完整兼容性、重启、Retention、Injection 与端到端加固。
2. Product Page Live State 闭环仍属于 P2.4。
3. Verified Citation 证明 Answer 与保留 Evidence 的 Lineage，不独立证明模型文本在语义上
   一定正确。
4. Evaluation 与 Answer Quality Scoring 属于 Evaluation 阶段，不会隐藏塞进 P2.3。

## 退出声明

维护者已于 2026-07-31 验收 P2.3e，并确认：

> Apvero 拒绝模型编写 Citation Identity，从不可变 Run Evidence 派生每一个被接受的
> Citation，以原子方式完成 Run 与 Citation Flag，在无效 Output 上保留已知外部 Cost，
> 并且只暴露 Workspace-scoped 的读取时 Source Locator。
