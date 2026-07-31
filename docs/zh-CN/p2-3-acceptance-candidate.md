# P2.3 Application 到 Cited Run 闭环验收候选

状态：已于 2026-07-31 组装本地累计候选。P2.3 继续保持 `in-progress`。

P2.3 只有在获得维护者里程碑验收并通过 Clean-host 候选 CI 后才能改为 `completed`。
P2.4 继续保持 Planned，并负责双语 Live 产品页与运维页面。

## 候选结果

当前实现支持以下有边界的声明：

> 在一个已授权 Workspace 中，Apvero 可以上传并处理受支持 Source，发布受治理的不可变
> Knowledge Index Version，把其准确版本与 Retrieval Policy 绑定到 RAG Application，创建
> 不可变 Manifest 1.1 ReleaseBundle，只使用这些 Release Pin 执行受治理 Run，保留有序
> Retrieval Evidence，并返回从该 Evidence 派生的 Verified Citation；同时跨 Workspace
> 失败关闭并保留历史 CHAT 兼容性。

这是服务器端生命周期声明。它不会让不完整 Console 页面变成 Live，不声称普遍语义正确，
也不加入 Hybrid Search、Reranking、OCR、Evaluation、Tool、MCP、Agent Loop、Streaming
或外部 Gateway 行为。

## Slice 证据

| Slice | 已验收结果 |
|---|---|
| P2.3a | Application 拥有有序不透明 Draft ID；Knowledge 拥有准确 Scoped Resolution；继续禁止 `application -> knowledge` |
| P2.3b | Release 解析权威 READY Pin，存储严格不可变 Manifest 1.1 与规范 Digest，同时保留 Manifest 1.0 |
| P2.3c | Runtime 持久化 Workspace-scoped 有序 Retrieval/Hit Evidence，支持 Retention-aware Content 与不可变 Lineage |
| P2.3d | Runtime 执行准确有序 Retrieval、有界不可信 Context、类型化 `NO_EVIDENCE`、Governance 与只使用不可变 Release 的派发 |
| P2.3e | Structured Grounded Answer 校验拒绝错误或伪造 Marker，只从保留 Run Evidence 派生公共 Citation |
| P2.3f | 加固历史兼容、重启读取、Retention、失败分离、回滚下限和真实 Upload-to-Citation Compose 路径 |

详细双语证据：

- [`p2-3a-verification.md`](p2-3a-verification.md)
- [`p2-3b-verification.md`](p2-3b-verification.md)
- [`p2-3c-verification.md`](p2-3c-verification.md)
- [`p2-3d-verification.md`](p2-3d-verification.md)
- [`p2-3e-verification.md`](p2-3e-verification.md)
- [`p2-3f-verification.md`](p2-3f-verification.md)

## 真实端到端闭环

隔离 Knowledge Compose Gate 现在执行一条连续 API 与持久化状态工作流：

```text
公共文本上传
  -> 持久化异步 Ingestion READY
  -> 确定性 Document 与 Chunk
  -> 受治理 Embedding Build
  -> 原子不可变 Index Version
  -> 不可变 Retrieval Policy
  -> RAG Application Draft
  -> 准确有序 Knowledge Binding
  -> Manifest 1.1 ReleaseBundle
  -> 受治理准确 Retrieval
  -> 确定性 Retrieval Child Trace
  -> 受治理 Chat Generation
  -> Structured Grounded Answer
  -> 持久化 [K1] Evidence
  -> 授权 Citation Read
```

干净运行断言：

- Build 使用公共上传路径创建的 Revision；
- Release 固定准确 Index 与 Policy Reference；
- Run 为 `SUCCEEDED`，并返回 `GROUNDED` Grounded Answer；
- `[K1]` 与保留 Run Evidence 具有相同 Source Revision 和 Content Digest；
- 数据库把 Citation Hit 连接回 Build 冻结 Revision；
- 外部 Workspace 得到失败关闭的 Not Found；
- Deterministic Local Provider 让工作流无需付费 Key 即可复现。

## 自审发现的闭环缺陷

累计单元和集成测试最初仍有三个真实边界没有连接。加入单栈工作流后暴露并修复了它们：

1. Captured Source Revision 的 Processing-version Metadata 可以为空，因为不可变 Snapshot
   先于处理发生。发布现在以不可变 Document/Chunk Version 为权威，同时继续拒绝 Revision
   中任何非空但冲突的声明。
2. 旧 CHAT `ModelRoute` Projection 曾尝试映射 CHAT 专用字段按设计为空的 EMBEDDING Row。
   CHAT Route 列表和 Release Resolution 现在排除 EMBEDDING Row；Embedding 继续使用专用
   Public Projection。
3. Knowledge Query 与 Chat Governance Reservation 最初复用 Run Root `trace_id`，违反持久化
   唯一约束。每个有序 Retrieval 现在使用确定性 Child Trace Identity，Chat 继续保留 Run
   Root Trace。

P2.3f 还通过狭窄的仅执行期 `name@N.0.0 -> name@N` Projection 修复历史 Manifest 1.0
Route/Prompt Reference。持久化不可变 Manifest、Digest 与 Release Identity 不变；
Manifest 1.1 永不执行该规范化。

## 架构、安全与回滚

- Modular Monolith 边界和已批准依赖图不变。
- 未新增跨模块 SQL、模块、Deployable、数据库、队列、框架或强制有状态依赖。
- PostgreSQL 与 pgvector 继续是唯一强制有状态基线。
- Spring AI 继续是唯一 Java 核心 AI 抽象。
- Production Execution 只解析不可变 ReleaseBundle。
- Retrieval Content 是有界不可信数据，不能选择 Capability 或 Policy。
- Citation 从 Evidence 派生；未知 Marker 会失败，而不是被静默删除。
- Workspace Scope 覆盖 Release Resolution、Retrieval、Evidence 与 Citation Read。
- Payload Retention 可以不保留 Content，同时保留政策允许的不可变 Lineage。
- 一旦存在 Manifest 1.1 RAG Release，仅支持 P1 的 Binary 就低于回滚下限。回滚保留 V13
  数据并恢复 P2.3-compatible Binary，或以 Fail-closed 方式禁用 Knowledge；不得重写
  Release、Terminal Run、Evidence、Reservation、Usage 或 Cost。

## 本地 Gate 证据

累计本地验证覆盖：

- Spring Modulith、ArchUnit、96 个 Suite 共 333 个 Java
  Unit/Module Integration/Testcontainers/Flyway Test，0 Failure、0 Error；一个需显式启用
  的 Exact Retrieval Benchmark 按设计跳过；`bootJar` 通过；
- Manifest 1.0 CHAT、Manifest 1.1 CHAT 与 Manifest 1.1 RAG 兼容；
- 全部 Contract JSON 解析、Manifest 1.1 RAG Draft 2020-12 AJV 校验和 OpenAPI Lint；仅保留
  两个既有 Health/Info 缺少 4xx 的 Warning；
- 19 个 Worker Test、Ruff 与 Dependency Audit，没有已知第三方漏洞；
- Console 严格 TypeScript、5 个 Unit Test、Production Build 与 405/405 English/zh-CN
  Leaf Key；
- 默认与 Knowledge Compose 配置；
- 非 Root Platform Server 与 Worker Image Build；
- 隔离健康 PostgreSQL/Platform/Worker Upload-to-Citation 执行；
- 临时 Compose Container、Network 与 Volume 清理；
- Git Whitespace 校验。

Clean-host Job Identity 将在累计候选 Commit 发布且 CI 完成后记录。

## 剩余验收程序

1. 通过 GitHub API 发布 P2.3 候选分支和一个 Draft PR。
2. 验证 Blob、Tree、Commit 与 Ref Identity。
3. 要求全部 Clean-host CI Job（包括升级后的 `knowledge-compose`）通过。
4. 向维护者提交证据，获取明确 P2.3 验收。
5. 只有验收后才把 P2.3 改为 `completed`；P2 继续 `in-progress` 并进入 P2.4。
