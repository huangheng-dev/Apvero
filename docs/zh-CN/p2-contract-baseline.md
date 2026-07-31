# P2 契约基线

## 状态

P2 正在进行，ADR-0006 已批准。本文中的契约已经成为获批设计权威，但在对应 P2 实现切片完成验证前，仍保持 `contract-only`。现有 P1 API 继续是真实功能；任何 P2 端点目前都不能声称服务端已经成功执行。

## 契约清单

| 契约 | 状态 | 用途 |
|---|---|---|
| `release-bundle-manifest.schema.json` | legacy-live | 准确描述已识别的 Manifest 1.0 CHAT 形式，包括 P1 整数版本简写和自动生成的运行参数元数据。 |
| `release-bundle-manifest.v1.1.schema.json` | Release 与 Runtime baseline | 严格的 CHAT/RAG 发布固定契约，包含明确运行模式和准确 Knowledge 绑定。 |
| `citation.v1.schema.json` | Runtime baseline | 根据不可变 Run 检索证据验证的引用身份。 |
| `grounded-answer.v1.schema.json` | Runtime baseline | `GROUNDED` 或 `NO_EVIDENCE` 的结构化 RAG 输出。 |
| `platform-api.yaml` Knowledge 操作 | contract-only | 工作区受限的数据源、任务、索引、检索、绑定和 Run 证据闭环。 |
| `ai-worker-internal.v1.yaml` | contract-only、internal-only | Java 与 Worker 之间的无状态受限解析和确定性切块契约。 |

现有 Model Route 契约继续保留真实的 P1 CHAT 请求。P2 增加一个 `contract-only` 的 EMBEDDING 路由变体，固定维度、最大输入 Token、最大批量和归一化元数据。它仍然属于同一个厂商无关 Model Route 聚合，不会形成第二套模型体系。

## P2.2 已批准契约勘误

维护者于 2026-07-24 批准 P2.2 编码前勘误：

1. CHAT 与 EMBEDDING Model Route 保留现有不可变正整数版本和规范 `name@N` 引用。
   `KnowledgeIndexVersion` 同时固定准确 Embedding Route ID 与该引用；Knowledge Index 与
   Retrieval Policy 引用继续使用语义版本。
2. pgvector `vector` 维度限制为 `1..16000`。存储向量与查询向量必须匹配固定 Build
   Dimension、只包含有限数，并具有用于余弦排序的非零范数。
3. 选择新 Build Source Set 时检查 Source 当前 Tombstone 状态；它不是已发布 Index
   Version 的检索时过滤条件。读取时仍执行当前 Authorization 与 Retention/Masking Policy。
4. 已发布 Retrieval Policy 包含平台分配的 Retrieval Algorithm/Token Estimator Version、
   发布时 Retention Policy Version 与 Canonical Policy Digest。当前更严格的 Disclosure
   Policy 始终优先于历史 Policy Provenance。

这些都是 P2.2 Contract-only 字段实施前勘误，不迁移 Live Client 或已存 P2.2 Row。
P2.3a 在 Manifest 可以写入之前，完成独立的 Manifest 1.1 Model/Prompt 引用冲突对齐。

## P2.3a 契约对齐

Manifest 1.1 现在按字段使用精确引用规则：

- 已实现的 Model Route 与 Prompt 身份使用规范 `name@正整数`；
- Knowledge Index 与 Retrieval Policy 身份使用规范语义版本；
- 其他精确 Artifact 字段接受所属聚合的整数或语义版本身份；
- 所有形式都禁止 `latest`。

Application 草稿 Knowledge Binding 在 Application 模块中有意保持不透明。公共投影只包含
Index Version ID、Retrieval Policy Version ID、Binding Order 与 Application 乐观版本元数据。
Release 通过 Knowledge 公共 API 权威校验 Workspace、存在性、READY 状态与规范引用。

## 兼容规则

1. Manifest 1.0 继续可读，并保持历史 CHAT 行为。
2. 绝不重写已有 Manifest 1.0 数据来伪造 Knowledge 固定信息。
3. 在 P2.3 完整实现 1.1 校验和运行行为前，现有创建 Release 接口继续只声明 Manifest 1.0。
4. 新 RAG Release 必须使用 Manifest 1.1，并至少包含一组准确的 `indexVersion + retrievalPolicyVersion` 绑定。
5. Manifest 1.1 的 CHAT Release 不允许包含 Knowledge 绑定。
6. Manifest 1.1 使用按字段区分的精确身份：Model Route 与 Prompt 使用已实现的整数版本，Knowledge Index 与 Retrieval Policy 使用语义版本，并且所有字段都禁止 `latest`。
7. 一旦存在 Manifest 1.1 RAG Release，只支持 P1 的运行时就低于安全回滚下限。

第 6 条保留现有 Model Route 与 Prompt 聚合，不会发明第二套版本系统。P2.3b 现已实现完整
离线校验与权威 Release Pinning。完整 P2.3 Grounded Execution 闭环通过兼容候选门禁后，
Manifest 1.1 现在成为 Release 与 Runtime Baseline。

## P2.3b Release 固定

标准 Application Release 写入只接受语义化 Release 版本。服务端从已认证 Application
Draft 和权威公共投影构建 Manifest；客户端不能提交或覆盖固定项。

- CHAT 为兼容 P1 Runtime，继续生成旧 Manifest 1.0 形状。
- RAG 只有在全部有序不透明绑定都解析为准确 Workspace 作用域 READY Index Version 和
  可执行 Retrieval Policy Version 后，才生成 Manifest 1.1。
- 1.0 与 1.1 都在插入前和读取时，通过离线白名单 Registry 按打包的 Draft 2020-12
  Schema 完整验证。
- 未知 Schema 版本与错误 Manifest 使用稳定 Release 错误码。
- 现有 Runtime Provider 接受 Manifest 1.0 CHAT 与明确的 Manifest 1.1 CHAT。
- Manifest 1.1 RAG 只通过 Grounded Orchestration 执行，绝不回退到 CHAT。

## 公开闭环

```text
Knowledge Base
  -> 数据源快照
  -> 持久化摄取任务
  -> 不可变 Source Revision
  -> Index Build
  -> READY 不可变 Index Version
  -> 检索测试
  -> Application 草稿绑定
  -> Manifest 1.1 RAG ReleaseBundle
  -> Run 检索证据
  -> 已验证引用
```

所有 P2 操作都必须携带 `X-Apvero-Workspace-Id` 并通过认证授权。跨工作区资源标识默认拒绝。上传和抓取内容受明确上限约束；普通读取契约绝不返回原始存储路径或无限制的数据源 URL。

## Worker 边界

Java 控制平面负责认证、授权、数据源抓取、SSRF 防护、快照持久化、任务状态、重试、身份、审计与计费。Worker 只接收已经捕获的字节，验证摘要，完成解析和切块，再返回带来源锚点的确定性序号结果。Worker 没有数据库凭证、不抓取 URL，也不向浏览器暴露。

Source 摄取与 Index 构建使用独立的持久化生命周期。P2.1 摄取任务在确定性解析/切块后结束；P2.2 通过 `KnowledgeIndexBuildStatus` 负责 `EMBEDDING`、`INDEXING`、`VALIDATING`。Worker 的 Chunk Offset 是标准化文档文本中的零基 Unicode Code Point 偏移，采用左闭右开区间 `[startOffset, endOffset)`；Page、Paragraph、Line Anchor 从 1 开始。

P2.1a 已删除旧 Worker 的宿主机端口和通用 `/worker/` 代理。Worker 现在只随 `knowledge` Profile 在私有内部网络启动；连 Health 也不通过宿主机或 Console 同源地址暴露。Parser 操作继续保持 contract-only 和禁用状态。

## 实现顺序

1. P2.1 实现物理 Knowledge 模块、持久化、任务、安全快照和 Worker 契约。
2. P2.2 实现受治理 Embedding、pgvector Build、原子发布和 Retrieval Lab。
3. P2.3 实现 Application 绑定、Manifest 1.1、可信运行时、证据和引用校验。
4. P2.4 只有在全部通用门禁通过后，才把双语产品界面升级为真实功能。

每个操作只有在实现、安全、遥测、国际化、失败路径测试和 Compose 证据全部具备后，才能逐项移除 `contract-only` 标记。

## P2.3e Grounded Output 基线

Runtime 现在接收严格的 Provider Draft，其中只能包含 Schema Version、`GROUNDED` 状态、
Answer Text 与 Evidence Marker。模型不提供公开 Citation Metadata。Runtime 从同一 Run 的
不可变 Evidence Ledger 派生 Grounded Answer 1.0 与 Citation 1.0 身份，拒绝格式错误、
重复、未知或伪造 Marker，并仅在 Run 成功时以原子方式把已接受 Evidence Hit 标记为
Citation Validated。

Citation List Operation 现在成为 Runtime Baseline。它继续受 Workspace 限制，并且只返回
已验证 Evidence。经过授权检查的相对 Source Locator 在读取时根据保留的 Source Revision
身份与 Anchor 生成；Model Output 与 Citation Ledger 都不会持久化 Locator 或 Storage
Path。P2.3 整体继续保持进行中，直到 P2.3f 完成兼容性、重启、安全与端到端门禁。

## P2.3f 兼容性基线

Runtime 保留真实历史 Manifest 1.0 Release，且不改写其不可变 Row。历史生成的
`name@N.0.0` Model Route 与 Prompt Reference 只在内存 Execution Projection 中规范化为
Canonical `name@N` Identity；系统不会猜测非零的模糊 Semantic Version。明确的 Manifest
1.1 CHAT 继续走受治理 CHAT Path，Manifest 1.1 RAG 则只进入 Grounded Execution。

Runtime Execution 读取不可变 ReleaseBundle，不读取可变 Application Draft。在清除内存中
Provider、Retrieval 与 Catalog State 后，Run 与 Verified Citation Lineage 仍可从 PostgreSQL
读取。Source Resynchronization 与 Tombstone 只影响未来 Build Selection，不改变已发布
Index Version 的 Membership 或 Retrieval Behavior。
