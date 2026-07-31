# P2.3f 闭环与兼容性加固验证候选

状态：维护者已于 2026-07-31 验收。P2.3 继续保持 `in-progress`，直到累计候选通过
Clean-host CI 并获得单独的里程碑验收。

## 范围

P2.3f 组装并加固完整的 Application-to-cited-Run 工作流：

```text
Source Snapshot
  -> 不可变 Revision 与可恢复 Ingestion
  -> 不可变已发布 Index Version
  -> 准确 Retrieval Policy
  -> 不透明 Application Draft Binding
  -> 不可变 Manifest 1.1 RAG ReleaseBundle
  -> 受治理 Grounded Run
  -> 保留 Evidence
  -> 从 Evidence 派生 Verified Citation
```

它同时保留真实历史 Manifest 1.0 Release 和明确 Manifest 1.1 CHAT Release 的受治理 CHAT
执行。P2.3f 不会让 Knowledge 产品页面变成 Live；该工作仍属于 P2.4。

## 权威与边界

- 阶段：P2 / P2.3 / P2.3f。
- 主要模块：Application、Release、Runtime、Knowledge 与 Capability Registry。
- Runtime 依赖继续只使用已批准公共 API。
- 未新增跨模块 SQL、模块、Deployable、数据库、队列、框架、迁移或强制有状态依赖。
- PostgreSQL 与 pgvector 继续作为唯一强制有状态基线。
- Spring AI 继续作为唯一 Java 核心 AI 抽象。
- ADR-0006 已授权本次兼容与闭环工作。

## 发现并修复的兼容缺陷

闭环审计发现，真实历史 Seed Release 的 Model Route 与 Prompt Reference 使用
`local-deterministic@1.0.0` 等形式，而当前 Capability Governance 解析的是已实现规范身份
`local-deterministic@1`。原有 Provider 单元兼容测试没有经过受治理数据库路径，因此历史
Release 可以通过 Schema 验证，却会在执行前失败。

Runtime 现在为 Manifest 1.0 创建仅用于执行的内存 Projection：

- 真实历史 `name@N.0.0` Reference 在内存中转换为规范 `name@N`；
- 持久化 ReleaseBundle、Manifest JSON、Artifact Digest 与 Release Identity 均不改变；
- Manifest 1.1 永远不进行该规范化；
- `name@1.2.3` 等非零 Semantic Form 不会被猜测或静默重定向。

集成测试现在通过 Governance 执行 Seed Manifest 1.0 CHAT Release 与明确 Manifest 1.1
CHAT Release。两者都不会创建 RAG Retrieval Evidence。Manifest 1.1 RAG 继续只进入
Grounded Execution，不能静默回退 CHAT。

## 可复现性与重启行为

Runtime Execution 在派发前解析准确不可变 ReleaseBundle，绝不读取后续可变 Application
Draft。兼容测试在 Release 后修改 Draft Binding，再执行旧 Release；随后清除全部 Mock
Provider 与 Retrieval State，并从 PostgreSQL 读取持久化 Run 和 Verified Citation Lineage。

Source Resynchronization 创建新的不可变 Revision；未变化 Snapshot 保持可审计 No-op。
Tombstone 会把 Source 排除在未来 Build 之外，但 Retrieval 时不会过滤已发布 Index
Version。这既保留旧 Release Membership，也允许新 Release 固定后续 Index Version。

持久化 Ingestion Lease 可在每个 Durable Step 的进程丢失后恢复。不明确外部 Provider
Outcome 继续进入 `RECONCILIATION_REQUIRED`；确定性派发前失败会释放 Reservation；
Terminal Run 与 Release 继续不可变。

## 安全、Retention 与失败分离

累计测试证明：

- 跨 Tenant/Workspace 的 Source、Retrieval、Run Evidence 与 Citation 读取失败关闭；
- 恶意 Evidence 继续作为有界 JSON Data，不能选择 Capability 或 Policy；
- 只有存在于同一 Run 保留 Evidence 中的 Marker 才能成为 Citation；
- Source Locator 在授权读取时生成，不存储 Local/Object-store Path；
- Retention 可以丢弃 Run Input/Output，同时保留最小不可变 Citation Lineage；
- 启用 Payload Retention 时递归遮蔽敏感 Input Field；
- `NO_EVIDENCE`、Knowledge Disabled、Malformed Output、Invalid Citation、安全 Provider
  Failure、派发前 Failure 与不明确 External Outcome 保持不同稳定结果；
- Output 或 Citation 验证失败时仍保留已知 Provider Usage 与 Cost。

## 契约与回滚下限

Manifest 1.1 从 `contract-only` 升级为 Release 与 Runtime `baseline`。Manifest 1.0 继续是
`legacy-live` 且不可变。Grounded Answer 1.0、Citation 1.0 与 Citation List Operation
继续保持 Baseline。

一旦存在 Manifest 1.1 RAG Release，只支持 P1 的 Binary 就低于受支持回滚下限。在 P2.3
里程碑发布前，可禁用 RAG Execution 或恢复最后一个 P2.3 Binary，同时保留 V13。已有
ReleaseBundle、Terminal Run、Evidence、Citation、Reservation、Usage 与 Cost 不得重写。

## 验证证据

以下定向验证已通过：

- 历史 Manifest 1.0 CHAT、明确 Manifest 1.1 CHAT 与 Manifest 1.1 RAG；
- 不可变权威 RAG Release Pinning，以及无效 Binding 时回滚；
- 严格 Manifest、Grounded Answer 与 Citation Contract Status；
- Source Resynchronization、未变化 No-op、Tombstone 与 Scoped Content；
- Ingestion 崩溃恢复、Retry、Idempotency 与 Workspace Isolation；
- 准确 pgvector Ranking、跨 Workspace 拒绝与已发布 Tombstone History；
- 受治理 Retrieval Settlement 与当前 Retention；
- 只使用不可变 Release 的 Runtime Execution 与 PostgreSQL-backed Citation Read；
- 已覆盖的类型化 Grounded 成功与失败路径。

最终闭环审计还新增了一条真实隔离 Compose 路径：从公共文本上传开始，经过 Ingestion、
不可变 Index 发布、Policy 发布、RAG Application 绑定、Manifest 1.1 Release 创建、受治理
Run 执行，直到 Verified Citation 检查。该路径暴露并修复了三个被 Fixture 与 Mock 边界
掩盖的集成缺陷：

- Captured Source Revision 在不可变 Document/Chunk 产生前可以没有 Processing Version；
  发布现在校验权威 Document/Chunk 版本，同时继续拒绝 Revision 中非空但不一致的声明；
- CHAT Route Projection 现在排除 CHAT 专用字段按设计为空的 EMBEDDING Route；
- 每个有序 Retrieval 使用确定性 Child Trace Identity，避免其 Knowledge Governance
  Reservation 与 Chat Governance 使用的 Run Root Trace 冲突。

干净隔离运行持久化了一个 Uploaded Revision、一个 Build Entry、一个 Published Index
Version、一个 Manifest 1.1 RAG Release、一个成功 Grounded Run，以及一个 Source Revision
与 Build 冻结 Revision 完全一致的 `[K1]` Citation。外部 Workspace 无法读取该 Citation。

完整本地验证：

- Gradle：96 个 Suite 共 333 个 Test，0 Failure、0 Error，并按预期跳过需显式启用的
  Exact Retrieval Benchmark；`bootJar` 通过；
- AI Worker：19 个 Test、Ruff 与 Dependency Audit 通过，没有已知第三方漏洞；
- Console：严格 TypeScript、5 个单元测试、生产构建，以及 405/405 English/简体中文
  Leaf Key 覆盖通过；
- Contract：全部 JSON 可解析，Manifest 1.1 RAG Example 通过 Draft 2020-12 AJV 校验，
  两份 OpenAPI 文档通过 Lint，仅有既有 Health/Info 缺少 4xx 的两个 Warning；
- 默认与 Knowledge Profile Compose 配置通过；
- Platform Server `runtime-prebuilt` 与 AI Worker Container Image 构建成功；
- Git Whitespace 校验通过。

## 已知限制

1. Verified Citation Lineage 不证明普遍语义 Answer 一定正确。
2. Product Live State、双语页面验收与运维展示仍属于 P2.4。
3. Evaluation、Reranking、Hybrid Search、OCR、广泛 Connector、Streaming 与 Tool 不会被
   静默塞入本阶段。
4. 兼容 Projection 只支持真实历史的零 Semantic Form，不会为任意导入的 Semantic Route
   Version 猜测 Identity。

## 退出声明

维护者已于 2026-07-31 验收以下 P2.3f 声明：

> Apvero 保留真实 Manifest 1.0 CHAT Execution，支持明确 Manifest 1.1 CHAT 与无回退的
> Grounded RAG，只执行不可变 Release Pin，在内存 Execution State 丢失后仍可恢复读取，
> 在 Resync 或 Tombstone 后保持旧 Published Index Behavior 可复现，并且只返回从保留
> Run Evidence 派生的 Workspace-scoped Citation。
