# P2.3 Application 到引用 Run 闭环——实施计划

状态：实施基线等待维护者审查；尚未实现任何 P2.3 业务切片

目标阶段：P2，里程碑 P2.3

决策基线：ADR-0006（已接受）

本计划使用的推理程度：高

功能开关：完整 P2 验收变更之前保持 `APVERO_KNOWLEDGE_ENABLED=false`

## 1. 目标结果

P2.3 要闭合一条可复现的服务端工作流：

```text
RAG Application 草稿
  -> 有顺序的不透明 Knowledge 版本绑定
  -> 发布时校验 Workspace 与 READY 状态
  -> 不可变 ReleaseBundle Manifest 1.1
  -> 受治理的 Run 准入
  -> 精确固定版本检索
  -> 持久化有序证据
  -> 带确定性 [K1] 标记的有界不可信上下文
  -> 固定 Chat 路由生成
  -> 结构化 Grounded Answer 校验
  -> 已验证引用或类型化 NO_EVIDENCE
  -> 遵守留存策略的证据检查
```

仅让绑定表、发布清单或检索调用各自可用，不能算里程碑完成。必须证明生产 Run
只读取不可变 ReleaseBundle，每条引用都能映射到该 Run 留存的证据，并且每种失败都明确且可复现。

P2.3 不会让 Console 中的 Knowledge、Studio、Releases、Playground 或 Run 投影转为真实页面。
双语产品与运维门禁仍属于 P2.4。P2.3 可以实现目前标记为 `contract-only` 的服务端端点，
但局部能力必须保持禁用，不能展示模拟成功。

## 2. 必需变更声明

| 项目 | P2.3 计划 |
|---|---|
| 阶段 | P2 / P2.3，当前为 `in-progress`；所有实施切片仍为 `planned` |
| 主模块 | `application`、`release`、`runtime` |
| 支撑模块 | `knowledge`、`capability-registry`；通过既有批准门面使用 `identity` 与 `governance` 行为 |
| 允许依赖 | `release -> application, capability-registry, knowledge`；`runtime -> application, release, capability-registry, knowledge`；`application -> none` |
| 禁止依赖 | 不得 `application -> knowledge`；不得跨模块访问 Repository 或表；不得 `runtime -> governance`；公共 API 不得出现 Provider SDK 类型 |
| 公共契约 | Application 草稿 Knowledge 绑定、Manifest 1.1、Grounded Answer 1.0、Citation 1.0、Run 检索证据 |
| 数据迁移 | V11 之后仅允许向前增量迁移 |
| 新增有状态依赖 | 无；PostgreSQL 仍是唯一必需的有状态依赖 |
| 新增 Deployable 或模块 | 无 |
| AI 抽象 | Spring AI 2.0 仍是唯一核心 Java AI 抽象 |
| 产品暴露 | P2.4 之前保持禁用/非 Live |
| 前端工作 | P2.3 实施切片不包含前端开发 |

本计划不改变产品不变量、模块边界、安全策略、发布语义或技术基线。ADR-0006 已经批准这里列出的
P2.3 边界与契约勘误。如果实现需要不同的依赖、表所有者、运行时回退、框架或发布含义，
必须停止并提出新的 ADR。

## 3. 实施前必须完成的权威对齐

### 3.1 Application 绑定是不透明的草稿引用

`application` 拥有可变草稿配置，但不允许依赖 `knowledge`。因此它的写入路径只能校验：

- 通过既有 Application 边界确认已认证的 Application/Workspace 所有权；
- UUID 非空及格式；
- 最多 16 组绑定；
- 稳定的从零开始的绑定顺序；
- `(indexVersionId, retrievalPolicyVersionId)` 组合不得重复；
- Application 草稿的乐观并发。

它不能声称被引用的 Knowledge 版本存在、属于当前 Workspace 或处于 READY。
这些权威检查属于可调用 `knowledge` 的 `release` 模块。

当前 `contract-only` OpenAPI 响应包含 Knowledge 规范引用，并宣称 Application 写入绑定的是
“已授权 READY 版本”。照此实现，要么产生被禁止的 `application -> knowledge` 依赖，
要么信任客户端提交的规范引用；两者都必须拒绝。

P2.3 的第一项契约勘误将：

- 让 Application 绑定响应只包含 ID 和顺序；
- 将草稿写入描述为不透明选择，而不是服务端已确认 READY 的资源；
- 把就绪状态与规范引用解析保留在发布校验中；
- 未来 UI 需要选择元数据时单独查询 Knowledge Catalog。

受影响端点仍为 `contract-only`，因此不会迁移真实客户端。

### 3.2 Knowledge 需要公开的精确版本解析能力

Knowledge 已拥有不可变 `knowledge_index_version` 行和 Retrieval Policy 版本，
但公共 Java Catalog 尚未提供供 Release 校验使用的精确 Workspace 作用域查询。
P2.3 将增加 Provider-neutral 公共投影与查询，等价于：

```text
KnowledgeIndexVersion getIndexVersion(workspaceId, indexVersionId)
RetrievalPolicyVersion getPolicyVersion(workspaceId, policyVersionId)
```

实现留在 Knowledge 内部并复用其作用域 Repository。跨 Workspace ID 使用同一个稳定的
Not-found/Denied 行为，不能泄露资源是否存在。Release 绝不能直接读取 Knowledge 表。

### 3.3 Manifest 1.1 必须匹配已实现的版本身份

当前 `contract-only` Manifest 1.1 Schema 对所有固定引用都应用语义版本格式。
已经实现且不可变的 Model Route 与 Prompt 身份采用 `name@正整数`。
ADR-0006 明确要求在 Manifest 1.1 转为 Live 之前修正该冲突。

勘误将采用按字段区分的引用定义：

- Model Route 与 Prompt 保持既有精确 `name@正整数` 身份；
- Knowledge Index 与 Retrieval Policy 使用精确语义版本引用；
- 已建立的占位或聚合身份继续保持精确，并且一律不允许 `latest`；
- 不为 Model Route 或 Prompt 发明第二套版本系统。

Manifest 1.0 继续作为 Legacy Live CHAT 契约。既有不可变行永不重写。
OpenAPI Release 投影会明确支持读取 1.0 与 1.1；新 RAG Release 创建只接受完整有效的 1.1。

### 3.4 完整 JSON Schema 校验

当前 Release Validator 只检查必需字段名并扫描 `latest`。P2.3 对新写入改用完整的
JSON Schema Draft 2020-12 校验，包括 CHAT/RAG 条件规则、封闭对象、数量、格式、范围和被引用 Schema。

增加校验库之前，实施切片必须记录：

- 通过 Version Catalog 固定所选维护中库的准确版本；
- 支持 Draft 2020-12 与无需联网的外部 `$id` 解析；
- 只允许本地白名单的进程内 Schema Registry；
- 依赖与许可证扫描结果；
- 将失败归一化为稳定 Apvero 错误码。

禁止在校验时远程获取 Schema。

## 4. 所有权与依赖流

```text
Application
  拥有可变的绑定 ID/顺序
        |
        v Application 公共 API
Release
  解析 Application 草稿
  -> Knowledge 公共精确版本查询
  -> Capability Registry 精确 Route/Prompt 查询
  -> 校验 Manifest 1.1
  -> 存储不可变 Manifest 与 Digest
        |
        v Release 公共 API
Runtime
  只解析不可变 ReleaseBundle
  -> Knowledge 公共检索
  -> Capability Registry 执行门面
  -> 拥有 Run 证据与 Grounded Answer 结果
```

事实边界：

- Application 是可变草稿选择的事实来源，不是 Knowledge 就绪状态的事实来源。
- Knowledge 是不可变 Index/Policy 身份与检索结果的事实来源。
- Release 是不可变生产固定版本与 Release Digest 的事实来源。
- Runtime 是 Run 状态、有序证据、引用校验、用量和失败的事实来源。
- Governance 仍是准入、预留、结算、留存和审计策略的事实来源。
- 日志与指标只用于诊断，不能替代类型化 Release、Run、Retrieval 或 Hit 记录。

## 5. Application 草稿绑定设计

Application 拥有的表：

```text
application_draft_knowledge_binding
  application_id
  tenant_id
  workspace_id
  binding_order
  knowledge_index_version_id
  retrieval_policy_version_id
  created_at
  updated_at
```

必需数据库规则：

- 仅使用复合外键关联所属 Application 作用域；
- `(application_id, binding_order)` 唯一；
- `(application_id, knowledge_index_version_id, retrieval_policy_version_id)` 唯一；
- `binding_order` 范围为 0 到 15；
- 不得向 Knowledge 所有的表建立外键；
- Replace-all 更新必须在一个事务中完成；
- Application Version 必须乐观递增，避免并发草稿编辑静默覆盖。

CHAT 草稿必须为零 Knowledge 绑定。RAG 草稿在编辑期间可以为零绑定，
但 Preview/Release 创建在至少一组精确绑定通过校验前必须失败。
这样既允许不完整草稿，又不会削弱生产发布门禁。

## 6. ReleaseBundle 1.1 创建与兼容

Release 创建是校验和固定版本边界：

1. 在已认证 Workspace 中加载 Application；
2. 快照草稿 Model Route、Prompt、Runtime Mode 和有序 Knowledge ID；
3. 通过 Knowledge 解析每个精确 Knowledge Index Version 与 Retrieval Policy Version；
4. 要求 Workspace 所有权、不可变发布、READY 状态、受支持检索算法和允许执行策略；
5. 通过 Capability Registry 解析既有精确 Model/Prompt 引用；
6. 使用权威投影在服务端构建 Manifest 1.1；
7. 使用离线 Registry 完整校验 Schema；
8. 计算规范化 Artifact Digest；
9. 在同一个 Release 事务中插入一个不可变 ReleaseBundle。

标准 Application 发布路径中，客户端提交的 Manifest 不能覆盖服务端解析出的草稿固定版本。
如果保留原始 Manifest 导入，它必须是单独明确授权的操作，并通过相同完整解析与校验门禁；
P2.3 不会隐式创建该能力。

兼容矩阵：

| 已存 Manifest | Application 模式 | Runtime 行为 |
|---|---|---|
| 1.0 | CHAT | 保留历史 P1 执行 |
| 带占位 Knowledge 字符串的 1.0 | CHAT | 按历史 CHAT 行为忽略占位 |
| 1.1 | CHAT | 要求零 Knowledge 绑定 |
| 1.1 | RAG | 要求 1 到 16 组已校验精确 Knowledge 绑定 |
| 未知 Schema | 任意 | 使用稳定 Unsupported-manifest 错误失败 |

第一个 1.1 RAG Release 存在后，P1-only Binary 低于安全回滚下限。

## 7. Runtime 证据持久化

Runtime 拥有两个新表以及稳定失败码扩展：

```text
ai_run_retrieval
  id
  run_id + tenant_id + workspace_id
  sequence
  index_version_id + index_version_reference
  retrieval_policy_version_id + retrieval_policy_version_reference
  query_digest
  status
  hit_count
  latency_ms
  retention_decision_version
  created_at

ai_run_retrieval_hit
  id
  retrieval_id + run_id + tenant_id + workspace_id
  marker
  rank
  score
  source_id
  source_revision_id
  document_id
  chunk_id
  content_digest
  retained_content
  source_title
  source_type
  page / heading / paragraph / line_start / line_end
  citation_validated
  created_at

ai_run.failure_code
  P2 执行失败时稳定、可为空、机器可读的代码
```

必需约束：

- 每条证据都能通过复合键传递地限定到所属 Run；
- `(run_id, sequence)` 唯一；
- `(run_id, marker)` 唯一且 Marker 顺序确定；
- `(retrieval_id, rank)` 唯一；
- Score、Rank、Anchor、Digest、Status 和 Count 检查；
- Identity/Digest/Order 字段插入后不可变；
- 根据当前留存策略，Retained Content 可以为空或已脱敏；
- 不得存储文件系统路径、对象存储路径、原始 Secret 或持久授权 URL。

公共读取投影在读取时生成经过授权检查的 Locator。Locator 不属于 Release Digest 或证据身份。

## 8. Grounded Run 状态机

```text
创建 Run 与 Trace 身份
  -> 执行准入并预留 Query Embedding
  -> 解析不可变 Release
  -> 对每个有序绑定：
       检索精确 Index/Policy
       持久化 Retrieval Result 与 Hits
  -> 如果所有绑定都没有合格证据：
       持久化类型化 NO_EVIDENCE
       释放/结算预留
       不进行 Chat Generation 并完成
  -> 预留 Chat Generation
  -> 构造有界不可信上下文
  -> 调用精确固定 Chat Route
  -> 解析结构化答案
  -> 校验每个 Citation Marker
  -> 持久化 Grounded Answer、已验证引用、用量和费用
  -> 完成
```

规则：

1. Runtime 解析 ReleaseBundle 后绝不能读取当前 Application 草稿。
2. Binding 按 Manifest 顺序执行。经过策略过滤与上下文预算后，证据获得确定性的全局
   `[K1]`、`[K2]` 等 Marker。
3. 检索内容作为不可信数据放在明确边界内，不能覆盖 System Prompt、选择 Capability、
   调用 Tool 或改变 Policy。
4. `NO_EVIDENCE` 是零引用的成功类型化 Grounded 结果，并且不调用 Chat Generation；
   它绝不是普通 CHAT 回退。
5. Knowledge 被禁用时使用 `KNOWLEDGE_DISABLED` 失败。
6. 未知 Manifest、不可用精确固定版本、结构化输出错误、伪造 Marker、Provider 失败和
   付费调用结果不确定必须保持不同的稳定结果。
7. P1 预留与结算语义适用于 Query Embedding 和 Chat Generation。
   Runtime 使用批准的 Capability 门面，绝不能写 Governance 表。

## 9. 引用校验

Grounded Answer 只有满足以下条件才能成功：

- 通过 Grounded Answer Schema 1.0；
- `GROUNDED` 包含非空 Answer 与至少一条唯一 Citation；
- 每个 Citation Marker 都存在于当前 Run 的证据集合；
- Marker Identity、Source Lineage、Content Digest、Rank、Score 和 Anchor
  从留存证据复制，不能信任模型提交的元数据；
- 不得引用被 Policy 或 Context Budget 移除的 Marker；
- `NO_EVIDENCE` 包含零 Citation。

模型可以返回 Marker，但不能编写完整 Citation Metadata。Runtime 从已验证证据派生公共
Citation 对象。未知或格式错误的 Marker 使用稳定的 `CITATION_VALIDATION_FAILED` 错误族
让 Run 失败；Apvero 绝不能删除伪造引用后仍报告成功。

## 10. Source 重同步、Tombstone、留存与历史 Release

- Source 重同步会创建新 Revision，只影响未来 Index Build。
- Tombstone 阻止 Source 进入未来 Build。
- 旧 Release 继续检索其精确旧不可变 Index Version。
- 执行或检查旧 Run 时始终应用当前 Authorization。
- 当前更严格的 Retention/Masking Policy 可以隐藏已存摘录或 Locator；
  在策略允许时，不可变 Digest、Rank、Source Revision、Document 与 Chunk Identity 仍保留。
- Legal Erasure 不能伪装为 Tombstone。如果批准的破坏性删除导致历史证据不可用，
  系统必须明确记录可复现性被破坏。

测试必须比较 Source 重同步/Tombstone 后的旧 Release 与新 Release，
证明两者继续使用各自固定的 Index。

## 11. 稳定错误、安全、审计与遥测

最少稳定错误族：

```text
APPLICATION_KNOWLEDGE_BINDING_INVALID
KNOWLEDGE_INDEX_VERSION_NOT_FOUND
RETRIEVAL_POLICY_VERSION_NOT_FOUND
RELEASE_KNOWLEDGE_BINDING_INVALID
RELEASE_MANIFEST_UNSUPPORTED
RELEASE_MANIFEST_INVALID
KNOWLEDGE_DISABLED
RUNTIME_RETRIEVAL_FAILED
GROUNDED_OUTPUT_INVALID
CITATION_VALIDATION_FAILED
EXTERNAL_OUTCOME_RECONCILIATION_REQUIRED
```

后端响应只暴露稳定代码，由客户端本地化消息。普通错误消息不能包含 Provider Error、Prompt、
留存 Source Content、Query Text、Secret 或原始 Locator。

类型化遥测覆盖 Release Pin 校验、Retrieval 数量/耗时/命中/空证据、有界 Context 大小、
Grounded Success、No Evidence、Citation Failure、Provider Failure 与 Settlement。
管理变更与策略决策继续可审计。高频 Hit 事件保留为类型化 Runtime Evidence，
不能淹没管理审计账本。

## 12. 迁移与事务计划

计划的向前迁移：

- V12：Application 草稿 Knowledge 绑定表、作用域约束、顺序、唯一性和支持不可变语义的
  Replace Protocol。
- V13：Runtime Retrieval/Evidence 表、`ai_run.failure_code`、作用域约束、
  Evidence 不可变 Guard 与检查索引。

迁移测试覆盖从 P1/P2.2 基线升级和空数据库创建。不提供破坏性 Down Migration。
回滚缓解方案：

- 不存在 RAG Release 之前，可以关闭 Knowledge 并运行 P1-compatible Binary；
- 存在 RAG Release 后，保留增量数据且只使用 P2-compatible Binary；
- 关闭 Knowledge 必须产生 `KNOWLEDGE_DISABLED`，绝不能回退 CHAT；
- 失败或局部 Run 保持可检查，回滚时不删除。

## 13. 内部实施切片

这些切片只是检查点，不代表可以独立发布产品能力。

### P2.3a——契约对齐与不透明 Application 绑定

- 修正 `contract-only` Binding 语义和 Manifest 1.1 按字段版本引用；
- 增加 Knowledge 精确版本公共投影/查询；
- 增加 V12 与 Application Binding Aggregate/API；
- 验证不存在 `application -> knowledge` 依赖；
- 所有 P2.3 端点保持 Disabled/Non-live。

### P2.3b——不可变 Manifest 1.1 发布固定版本

- 为 Release 增加已批准的 Knowledge 依赖；
- 增加离线完整 JSON Schema 校验；
- 构建服务端权威 Manifest 1.1；
- 校验精确 READY Index/Policy Pin 与有序 Binding；
- 保留 Manifest 1.0 读取及 CHAT 执行兼容。

### P2.3c——作用域 Run 检索证据账本

- 增加 V13 与 Runtime Evidence Repository；
- 事务化保存 Retrieval Result 与有序 Hit Identity；
- 内容持久化前应用 Retention/Masking；
- 实现 Workspace 作用域 Evidence/Citation Read Model，但不暴露 Live Console。

### P2.3d——Grounded Runtime 编排

- 只解析不可变 ReleaseBundle；
- 使用 P1 治理语义执行精确有序 Retrieval；
- 实现有界不可信 Context 与确定性 Marker；
- 实现不调用生成模型的类型化 `NO_EVIDENCE`；
- 保留外部调用不确定结果的 Reconciliation 行为。

### P2.3e——结构化 Answer 与 Citation 校验

- 解析 Grounded Answer 1.0；
- 从 Evidence 派生 Citation 1.0；
- 拒绝格式错误、未知或伪造 Marker；
- 读取时生成授权 Locator；
- 记录稳定 Failure、Audit、Usage、Cost 与 Telemetry 结果。

### P2.3f——闭环与兼容加固

- 执行 Manifest 1.0/1.1 兼容测试；
- 证明 Resync/Tombstone 后新旧 Release 行为；
- 执行跨 Workspace、Retention、Injection、Failure、Restart 与回滚下限测试；
- 执行离线确定性 E2E 与可选 Adapter 测试；
- 准备英中双语 P2.3 验证证据供维护者验收。

## 14. 验证矩阵

| 范围 | 必需证据 |
|---|---|
| 边界 | Spring Modulith 与 ArchUnit 允许/禁止依赖测试 |
| Application | Replace-all 顺序、重复拒绝、乐观冲突、CHAT 零绑定、Workspace 隔离 |
| Knowledge API | 精确 READY 查询、Policy 查询、Not-found/跨 Workspace Fail-closed |
| Release | 完整 Schema 校验、权威解析、Digest 稳定、1.0 兼容、未知 Schema 拒绝 |
| 迁移 | 空库 V1–V13 与 P2.2-to-V13 Testcontainers 升级、约束和不可变 Guard |
| Runtime | 仅从精确 Release 解析、多 Binding 顺序、重启/终止路径 |
| Governance | 调用前预留、结算/释放、Budget/Rate 拒绝、付费调用不确定结果协调 |
| 安全 | Prompt Injection 边界、Masking、Locator 授权、无路径/Secret/Error 泄漏 |
| 引用 | 确定性 Marker、有效映射、重复/未知/格式错误 Marker 拒绝 |
| 兼容 | 旧 CHAT Release、1.1 CHAT、1.1 RAG、Resync/Tombstone 新旧 Release 比较 |
| 契约 | OpenAPI/JSON Schema 兼容与离线 Schema Registry 测试 |
| i18n/文档 | 稳定后端 Code 与配套 English/zh-CN 文档 |
| 运维 | Metrics、Typed Evidence、Audit、Health、Container/Compose 检查 |
| 端到端 | Upload → Ingest → Build → Retrieve → Bind → Release → Run → 检查已验证 Citation |

P2.3 验收证据可以使用 API 与集成测试。完整双语 Live Page 与 Playwright 验收仍属于 P2.4。

## 15. 自我批判与拒绝的捷径

1. **在 Application 绑定时校验 Knowledge。** 拒绝，因为会违反批准的依赖图或形成不安全的重复投影。
   Release 校验才是权威。
2. **存储浏览器提交的规范引用。** 拒绝，因为可能过期或伪造。Release 必须从所属模块解析引用。
3. **Release 或 Runtime 直接读 Knowledge 表。** 拒绝，因为属于跨模块数据库访问。
4. **保留浅层 Manifest Validator。** 拒绝，因为无法执行条件、数量、封闭对象和引用 Schema 规则。
5. **重写旧 Manifest 1.0 行。** 拒绝，因为 ReleaseBundle 不可变。
6. **Run 时读取最新草稿。** 拒绝，因为会破坏可复现性。
7. **从 RAG 静默回退 CHAT。** 拒绝，因为无依据答案会伪装成 Grounded。
8. **让模型编写完整 Citation Metadata。** 拒绝，因为模型可以伪造 Lineage。
9. **存储长期 Signed Object URL。** 拒绝，因为 Authorization 与过期时间会变化；Locator 在读取时生成。
10. **在批准的 Evidence 表之外再增加 Citation 表。** 拒绝，除非证据证明必要；
    Runtime Hit Ledger 足以保存已验证 Citation State 与 Mapping。
11. **增加 Kafka、Redis、Milvus 或 Workflow Engine。** 拒绝，因为 PostgreSQL 与 Modular Monolith
    足以闭合当前工作流，并无新的已证明边界。
12. **后端闭环期间让页面 Live。** 拒绝，因为诚实的产品与运维暴露属于 P2.4。

已知限制：P2.3 证明可复现的向量 Grounded Execution，不保证普遍回答质量。
Retrieval Quality 取决于 Source Corpus、Parser Output、Embedding Route 与固定 Policy。
Evaluation、A/B Test、Reranking、Hybrid Search、OCR 与广泛 Connector 不会被悄悄塞进本里程碑。

## 16. 验收门禁

只有满足以下条件，P2.3 才能提交维护者验收：

- 六个切片全部实现并验证；
- RAG Draft 只有在精确 Knowledge Pin 校验通过后才能发布；
- Manifest 1.0 CHAT 行为继续兼容；
- Production Run 不读取任何可变 Draft State；
- `GROUNDED` Citation 只能解析到该 Run 留存的 Evidence；
- `NO_EVIDENCE`、Knowledge Disabled、Malformed Output、Invalid Citation、Provider Failure
  与 Ambiguous External Outcome 都有不同且已测试的结果；
- Source Resync/Tombstone 后新旧 Release 继续可复现；
- Migration、Module Rule、Contract、Security、Telemetry、Usage/Cost、英中双语文档、
  Compose 与 CI 证据全部通过；
- 维护者批准 P2.3 阶段转换。

即使 P2.3 验收完成，在 P2.4 完成产品与运维门禁之前，该能力仍保持 Non-live。
P2 整体仍为 `in-progress`。

## 17. 主要实施依据

- `AGENTS.md`
- `architecture/invariants.yaml`
- `architecture/delivery-stages.yaml`
- `architecture/modules.yaml`
- `architecture/dependency-rules.yaml`
- `product/navigation.yaml`
- `product/pages.yaml`
- `docs/adr/0006-p2-grounded-knowledge-rag-baseline.md`
- `docs/zh-CN/p2-contract-baseline.md`
- `contracts/openapi/platform-api.yaml`
- `contracts/schemas/release-bundle-manifest.schema.json`
- `contracts/schemas/release-bundle-manifest.v1.1.schema.json`
- `contracts/schemas/grounded-answer.v1.schema.json`
- `contracts/schemas/citation.v1.schema.json`
