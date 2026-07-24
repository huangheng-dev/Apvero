# P2 契约基线

## 状态

P2 正在进行，ADR-0006 已批准。本文中的契约已经成为获批设计权威，但在对应 P2 实现切片完成验证前，仍保持 `contract-only`。现有 P1 API 继续是真实功能；任何 P2 端点目前都不能声称服务端已经成功执行。

## 契约清单

| 契约 | 状态 | 用途 |
|---|---|---|
| `release-bundle-manifest.schema.json` | legacy-live | 准确描述已识别的 Manifest 1.0 CHAT 形式，包括 P1 整数版本简写和自动生成的运行参数元数据。 |
| `release-bundle-manifest.v1.1.schema.json` | contract-only | 严格的 CHAT/RAG 发布固定契约，包含明确运行模式和准确 Knowledge 绑定。 |
| `citation.v1.schema.json` | contract-only | 根据不可变 Run 检索证据验证的引用身份。 |
| `grounded-answer.v1.schema.json` | contract-only | `GROUNDED` 或 `NO_EVIDENCE` 的结构化 RAG 输出。 |
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
Manifest 1.1 中独立的 Model/Prompt 引用不匹配继续作为明确的 P2.3 前置事项。

## 兼容规则

1. Manifest 1.0 继续可读，并保持历史 CHAT 行为。
2. 绝不重写已有 Manifest 1.0 数据来伪造 Knowledge 固定信息。
3. 在 P2.3 完整实现 1.1 校验和运行行为前，现有创建 Release 接口继续只声明 Manifest 1.0。
4. 新 RAG Release 必须使用 Manifest 1.1，并至少包含一组准确的 `indexVersion + retrievalPolicyVersion` 绑定。
5. Manifest 1.1 的 CHAT Release 不允许包含 Knowledge 绑定。
6. `none@1` 等整数简写只属于历史兼容；Manifest 1.1 强制使用语义版本引用并禁止 `latest`。
7. 一旦存在 Manifest 1.1 RAG Release，只支持 P1 的运行时就低于安全回滚下限。

第 6 条描述当前 Contract-only Manifest 1.1 Schema，不会重新定义现有 Model Route 或 Prompt
聚合。P2.3 必须在 Manifest 1.1 变为 Live 前，把该 Schema 与已实现的整数版本 Identity 统一。

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
