# P2.1 可恢复摄取主干——实施计划

状态：维护者已批准计划；P2.1 已于 2026-07-24 验收

目标阶段：P2，里程碑 P2.1

决策基线：已接受的 ADR-0006

功能开关：完整 P2 验收前保持 `APVERO_KNOWLEDGE_ENABLED=false`

## 1. 目标结果

P2.1 将建立一条持久化、进程重启后可恢复的摄取流程：

```text
已授权 Workspace
  -> Knowledge Base
  -> 有边界的数据提交或安全网页快照
  -> 不可变 Source Revision
  -> 带租约的持久化摄取任务
  -> 无状态 Parser/Chunker Worker
  -> 不可变 Document 与 Chunk 血缘
  -> 检查、重试、重新同步或 Tombstone
```

P2.1 不生成 Embedding、Index、检索结果、Application 绑定、Release、可信 Run，也不把产品页面改成真实状态。这些属于 P2.2–P2.4。完整 P2 门禁通过前，全部 P2 REST 操作继续标记为 `contract-only`，局部产品界面继续保持 Demo、Planned、隐藏或禁用。

## 2. 编辑前必须声明的变更范围

| 项目 | P2.1 决定 |
|---|---|
| 阶段 | P2 / P2.1，已验收 |
| 主模块 | 新建物理 `knowledge` 模块 |
| 支撑模块 | `identity`、`governance`；不依赖 Application、Release、Runtime |
| 允许依赖 | 声明为 `knowledge -> identity, capability-registry, governance`；P2.1 实际只使用 `identity`、`governance` |
| 公开契约 | P2.1 Knowledge REST 子集与 Worker Internal API 1.0 |
| 迁移 | V7 之后一个只向前迁移；不提供 Down Migration |
| 新状态依赖 | 无 |
| 新部署单元 | 无 |
| AI 抽象 | P2.1 不使用；Spring AI 继续是唯一获批 Java AI 抽象 |
| 产品暴露 | 禁用/非真实状态 |

任何 P2.1 切片都不得新增 Kafka、Redis、MinIO、Milvus、Elasticsearch、第二数据库、第二 AI 框架或浏览器可访问的解析端点。

## 3. 编码前已批准的契约勘误

P2.0 的 contract-only 基线最初把 `EMBEDDING`、`INDEXING`、`VALIDATING` 放入 `IngestionJobStatus` 与 `currentStep`。这与领域模型冲突：这些状态属于 P2.2 的独立 `IndexBuild` 生命周期。

获批勘误为：

```text
Source 摄取状态：
QUEUED | SNAPSHOTTING | PARSING | CHUNKING |
READY | RETRY_WAIT | FAILED | CANCELLED

Source 摄取当前步骤：
SNAPSHOTTING | PARSING | CHUNKING | COMPLETE

Index Build 状态（不变，P2.2）：
QUEUED | EMBEDDING | INDEXING | VALIDATING |
READY | RETRY_WAIT | FAILED | CANCELLED
```

这是对明确标记为 `contract-only`、尚无真实客户端的实施前勘误，不影响已上线行为。维护者已于 2026-07-22 批准。P2.1 不能为了迁就错误枚举而制造一个职责混乱的综合任务。

同一次勘误还必须明确：Chunk Offset 是标准化文档文本中的零基 Unicode Code Point 偏移，采用左闭右开区间 `[startOffset, endOffset)`；页码、段落和行锚点继续从 1 开始。

## 4. 领域归属与公开 Java 边界

模块根包为 `io.apvero.platform.knowledge`。只有该根包直接包含的类型属于公开模块 API；Controller 位于 `.api`，Repository、任务执行、Worker Client、网页抓取、媒体识别和持久化映射位于 `.internal`。

计划中的公开接口：

- `KnowledgeBaseCatalog`：创建和列出 Workspace 范围内的 Base；
- `KnowledgeSourceCatalog`：列出 Source/Revision，提交内联、文件、网页 Source，增加 Revision，同步、Tombstone，并流式读取已授权快照；
- `KnowledgeIngestionCatalog`：列出/读取任务，请求重试或取消。

计划中的公开 Record 只使用 Java/JDK 类型与厂商无关标识：

- `KnowledgeBase`、`KnowledgeSource`、`KnowledgeSourceRevision`；
- `KnowledgeIngestionJob` 与明确的状态、步骤、结果枚举；
- 与 OpenAPI 对齐的 Command 和 Receipt。

规则：

1. `knowledge` 只能通过 `WorkspaceScopeCatalog` 获取 Tenant/Workspace 范围，不能查询 Identity 表；
2. 安全相关变更通过 `AuditEventCatalog` 在同一个 Spring 事务写入 Audit；Audit 失败则业务变更失败；
3. Provider SDK、Application、Release、Runtime、Vector、Embedding 类型不得进入模块；
4. 所有 Repository 读写都必须同时接收 Tenant 与 Workspace 范围；禁止无 Scope 的方法；
5. 跨 Workspace 标识统一返回稳定的 Not Found，不泄露资源是否存在。

## 5. 持久化设计

P2.1 只增加摄取表。P2.2 的 Index 与 Retrieval 表明确排除在外。

### `knowledge_base`

核心字段：`id`、`tenant_id`、`workspace_id`、`slug`、`name`、`description`、`status`、`version`、`created_at`、`updated_at`。

约束包括 Workspace 组合范围、`(workspace_id, slug)` 唯一、乐观锁版本为正、有限状态检查，以及 `(workspace_id, updated_at desc)` 索引。

### `knowledge_source`

核心字段：`id`、完整 Scope、`knowledge_base_id`、`name`、`source_type`、`status`、受保护的网页定位元数据、最新 Revision 编号/标识、乐观锁版本、时间戳和 Tombstone 元数据。

Canonical Web URI 在 REST 边界只写。为了重新同步可以保存，但不得写日志、不得出现在不受限的查询投影中，每次使用前必须重新执行 SSRF 校验。

### `knowledge_source_revision`

核心字段：`id`、完整 Scope、`source_id`、单调递增的 `revision`、`content_digest`、`media_type`、`byte_size`、安全原始文件名、抓取响应元数据、不可变快照字节、快照状态、Parser/Chunker 版本、`created_at`。

规则：

- SHA-256 基于实际保存的字节，格式固定为 `sha256:<64 位小写十六进制>`；
- `(source_id, revision)` 与 `(source_id, content_digest)` 唯一；
- 有大小边界的快照存入 PostgreSQL `bytea`，本地磁盘只能作为临时处理空间；
- 数据库触发器拒绝 Update 和普通 Delete。

### `knowledge_document`

核心字段：`id`、完整 Scope、`source_revision_id`、Ordinal、Title、标准化文本摘要、Parser Version、Processing Profile、`created_at`。

如果 Chunk 加不可变 Source 已足以重建血缘，就不重复保存整份标准化文本。若 Parser 重建测试证明稳定 Offset 必须依赖它，实施前的迁移审查必须说明受限存储的必要性。

### `knowledge_chunk`

核心字段：`id`、完整 Scope、`source_revision_id`、`document_id`、Ordinal、不可变 Text、内容摘要、起止 Offset、Page/Heading/Paragraph/Line Anchor、Chunker Version、`created_at`。

`(document_id, ordinal)` 唯一，摘要和 Offset Check 让重复保存默认失败。Document 与 Chunk Insert-only。相同 Revision/Profile 再次处理必须比较一致后 No-op；如果输出不同，以 `APVERO_KNOWLEDGE_NON_DETERMINISTIC_OUTPUT` 失败，绝不能覆盖血缘。

### `knowledge_ingestion_job`

核心字段：`id`、完整 Scope、Base/Source/Revision 标识、Job Kind、Status、Current Step、Sync Outcome、尝试次数、最大次数、下次尝试时间、Lease Owner/Until、乐观锁版本、幂等键、稳定错误码/分类、安全失败元数据、取消请求、Started/Completed/Created/Updated 时间。

约束包括每个请求操作唯一的有效幂等标识、合法状态/步骤组合、非负尝试次数与组合 Scope 外键。错误元数据不得包含 Source 内容、原始 URL、Credential、Stack Trace 或 Provider Response。

迁移在 Knowledge 内部使用组合外键，并可引用已有 Workspace 组合键保证完整性。Knowledge 的运行时 SQL 不能读取 Workspace 表；授权仍由 Identity 公开 API 完成。

## 6. Source 工作流

### 内联 Text 与 Markdown

1. 授权 Workspace 并验证 Base；
2. 在内存无限增长前同时执行字符数和编码字节数限制；
3. 只规范传输编码；接受编码转换后，提交的 UTF-8 快照字节原样保存；
4. 在一个事务中创建 Source、Revision、Queued Job 和 Audit 证据；
5. 返回 `202` Receipt，请求事务中不执行解析。

### PDF、DOCX、Markdown、Text 上传

1. 仅在经实测的配置范围内流式读取或缓冲；
2. 捕获字节时同步计算 Digest；
3. 检测真实媒体结构，文件名与请求 Content-Type 只能作为提示；
4. 在排队前拒绝可执行文件、宏、加密、畸形或不支持内容；
5. Source、不可变 Revision、Queued Job、Audit 一次提交。

### 公共网页 Source

1. 创建请求保存 Source Identity 和 `SNAPSHOTTING` Job，此时 Revision 为空；
2. Java Job Runner 负责 Fetch，Python Worker 永远不接收 URL；
3. 只允许 HTTP/HTTPS，拒绝 User Info，安全规范化 Host/Port/Path；
4. 每一跳都解析地址，默认拒绝 Loopback、Private、Link-local、Multicast、Reserved、Metadata 网段；连接固定到已校验地址，每次 Redirect 重新校验；
5. 禁止隐式 Proxy 绕过，限制 Redirect/连接/读取/Body，只接受支持的 HTML/Text，并使用原始主机名校验 TLS；
6. 解析前先持久化最终字节与安全抓取元数据；
7. 与最新 Revision 比较 Digest。未变化时 Job 以 `UNCHANGED` 完成，写 Audit/Telemetry，但不创建 Revision 或 Chunk。

### 重新同步与 Tombstone

- 内联/上传 Revision 先比较 Digest；未变化时返回 Audit No-op，不创建 Job；
- Web Sync 必须创建持久化 Job，因为抓取前不知道 Digest；
- 内容变化时创建新的不可变 Revision，绝不修改旧版本；
- Tombstone 阻止新 Revision、Sync 和未来 Index 选择；已授权历史流程仍可读取旧 Revision；
- 法律永久清除不属于普通 Tombstone，也不在 P2.1 范围内。

## 7. Parser 与 Chunker 边界

Java 按 `ai-worker-internal.v1.yaml` 向 `/internal/v1/documents/process` 发送获批 Multipart 请求。持久化前，Client 必须校验 Request ID、Revision ID、Digest、Processing Profile、响应大小、返回 Digest、Ordinal 唯一性、Offset 合法性和全部 Anchor。

Worker 实施规则：

- 对声明的 `processingProfile` 保持无状态与确定性；
- Text/Markdown：UTF-8 校验、确定性换行处理、Line/Heading Anchor；
- HTML：只解析已捕获快照，移除可执行/非正文元素，保留 Heading/Paragraph 血缘；
- PDF：提取文字并保留 Page Anchor，不宣称 OCR；
- DOCX：限制 ZIP/XML 展开，拒绝宏、加密、压缩炸弹，保留 Heading/Paragraph Anchor；
- 限制 Parser 并发、CPU/时间、输入、解压字节、页数、元素数、Document、Chunk 和输出；
- 返回不含原始内容的稳定 RFC 9457 风格错误；
- 不访问数据库、外部网络、Secret，也不回调 Control Plane。

算法标识必须具有语义且不可变，例如 `apvero-text@1.0.0`、`apvero-boundary@1.0.0`。任何影响输出的行为变化都必须升级版本。切块边界优先级和 Overlap 必须确定，Offset 始终指向 Processing Profile 声明的标准化文档文本。

Parser Library 和准确上限不能靠猜。第一个实施切片必须先建立版本化的正常/对抗语料，Benchmark 候选 Parser，完成依赖与安全审查，并在启用 Parser Endpoint 前把选型和上限写入中英文验证文档。

## 8. 持久化任务协议

模块化单体使用定时 PostgreSQL Poller。Claim 事务通过 `FOR UPDATE SKIP LOCKED` 选择小批任务，写入唯一 Runner Identity 和 Lease Expiry，在适当时增加 Attempt，然后在文件、Worker 或网络 I/O 前提交。

每一步遵循：

1. 使用完整 Scope 加载 Job，校验 Lease Owner/Version；
2. 只读取已持久化的步骤输入；
3. 不持有数据库事务执行有边界的外部工作；
4. 校验完整输出；
5. 在一个短事务中幂等提交输出与下一持久步骤；
6. 清理 Lease 并发送低基数 Telemetry。

进程崩溃后 Lease 到期，任务可重新 Claim。第二个 Runner 必须识别已保存输出并 No-op 或比较，不能重复创建 Revision、Chunk、Audit Mutation 或未来计费工作。

Retry 使用有上限的指数退避加 Jitter，并按稳定错误类别定义是否可重试。Manual Retry 只允许 `FAILED` 且 Retryable；Cancellation 只允许 `QUEUED` 或 `RETRY_WAIT`，不能把正在进行的外部工作虚假报告为已取消。Graceful Shutdown 停止 Claim，执行有上限的 Drain；若被中断，则等待持久 Lease 过期。

P2.1 的终态 `READY` 仅表示不可变 Revision 已经具有校验通过的确定性 Document/Chunk 集合，不代表存在 Index。

## 9. 安全、错误、审计与遥测

初始授权把现有 API Key 的 `read`、`write`、`admin` Scope 分别映射到读取、变更和运维操作。P2.1 不重构 P1 Identity。Retry、Cancel、Tombstone 在当前策略下需要 Write 或 Admin；未来的资源级 Policy 需要单独批准。

稳定公开错误族至少包括：

- `APVERO_KNOWLEDGE_DISABLED`；
- 受 Scope 限制的 Base/Source/Revision/Job Not Found；
- Source Tombstoned 或操作冲突；
- Media 不支持、内容过大、Digest 不匹配、加密/畸形/压缩展开/Page/Timeout/Resource Limit；
- Web URI/Destination/Redirect/Content 拒绝与有边界的 Fetch Failure；
- Worker 不可用或响应不合法；
- Job 不可重试/取消、Lease 冲突、次数耗尽；
- Parser/Chunker 非确定性输出。

Backend Error 只暴露稳定 Code 与安全结构字段，由客户端本地化用户消息。Validation Annotation 与 Exception Mapping 不能让前端依赖硬编码英文提示。

Administrative Audit 覆盖 Base/Source 创建、Revision Accepted/No-op、Web Sync 请求/完成、Tombstone、Manual Retry、Cancellation、Terminal Failure。高频步骤转换保留为类型化 Job State 与 Telemetry，不污染 Audit Ledger。

Metrics 覆盖 Queue Wait、Step Duration、Outcome、Retry、输入输出大小、Document/Chunk 数量、Parser/Chunker Version、SSRF 拒绝、Worker Latency 和 Failure。Tag 只能使用低基数字段（`source_type`、`step`、`outcome`、`error_category`、算法版本）；Content、URL、Filename、Tenant、Workspace、Source、Job ID 不能成为 Metric Label。按 Scope 的运维检查来自授权数据库投影，不靠高基数 Metrics。

日志绝不记录原始 Source 内容、无限制 URL、Multipart 字节或 Secret。持久化 Job/Revision/Document/Chunk 才是事实来源。

## 10. 部署与配置

P2.1 在 `apvero.knowledge` 下增加 Enablement、Worker Base URI、Claim Batch/Lease/Polling/Backoff、Source Limit、Parser Limit、Web Policy、Graceful Drain 配置。环境变量统一使用 `APVERO_KNOWLEDGE_*` 前缀。

准确上限只有在语料实测后才可接受。每个 Limit 必须能在启动时看到有效值，具备边界内和刚好越界测试，并记录资源依据。

Parser 实施前必须完成的部署加固：

1. 从默认 Compose 删除 Worker Host Port；
2. 删除通用 `/worker/` Nginx Proxy；
3. 只在需要时从内部服务网络访问 Worker Health；
4. Platform Server 与 Worker 使用不与 PostgreSQL、Console 共享的专用 Internal Network，同时 Platform Server 继续加入普通 Backend Network；
5. Worker 使用非 Root、只读文件系统、有限临时空间与 CPU/内存；
6. Worker 不持有数据库或 Provider Credential；
7. 只有启用 Knowledge Processing 时，Platform Server 才依赖 Worker Health。

## 11. 实施切片

切片按顺序合并，但任何一个都不能独立宣称 P2 功能已交付。

预期实施位置为 `modules/knowledge`、迁移 `V8__p2_1_durable_knowledge_ingestion.sql`、现有 `apps/ai-worker`、Platform Server 配置/异常映射，以及现有 Compose/Nginx 文件。P2.1 不得创建通用 `common`、`shared` 或 `utils` 包。最终文件名可以在这些获批位置中进一步细化，但归属不能移动。

### P2.1a——模块与安全外壳

- 增加 Gradle Module 与 Server Dependency；
- 声明 Spring Modulith Boundary 和 ArchUnit Rule；
- 增加 Fail-closed Property 与 Health；
- 关闭 Worker 的公共暴露；
- 在实施前应用已批准的契约勘误；
- 建立 Parser Corpus/Benchmark 与 Dependency Decision Record。

### P2.1b——有 Scope 的不可变持久化

- 为六张 P2.1 表增加向前迁移；
- 增加数据库 Check、组合 Scope Key、Index 与 Immutability Trigger；
- 实现 Scoped Repository 与 Mapping Test；
- 验证 Clean Migration 和 V7 到新 Head 的 Upgrade。

### P2.1c——Source Command 边界

- 实现 Base Create/List；
- 实现 Inline/Upload Snapshot、Media Detection、Digest/No-op 与授权 Content Stream；
- Source/Revision/Job/Audit 创建保持事务一致；
- 实现 Tombstone 与跨 Workspace 失败行为。

### P2.1d——安全 Web Capture

- 实现固定 DNS 的 SSRF 防护与 Redirect Revalidation；
- 实现有边界抓取与安全元数据保存；
- 实现 Changed/Unchanged Sync；
- 测试 IPv4、IPv6、Rebinding、Redirect、Metadata、Proxy、Timeout 绕过。

### P2.1e——Worker Processing

- 在 Python 与 Java 实现并校验 Internal Parser/Chunker API 1.0；
- 实现五种 Source Type 与确定性 Anchor；
- 幂等保存不可变 Document/Chunk；
- 测试畸形、加密、压缩炸弹、Limit、Timeout、非确定性失败。

### P2.1f——持久执行与运维

- 实现 Lease Claim、Step Commit、Expiry Recovery、Retry/Backoff、Cancellation、Graceful Shutdown；
- 增加 Job/Source/Revision Read API 与稳定错误映射；
- 增加 Audit、Metrics、Structured Log、中英文运维文档；
- 执行 Compose 安全、重启与端到端验证。

## 12. 验证矩阵

P2.1 提请验收前必须具备：

| 领域 | 最低证据 |
|---|---|
| 架构 | Spring Modulith 与 ArchUnit 允许/禁止依赖测试 |
| 迁移 | Clean Install、V7 Upgrade、Constraint、Index、Forward-only Mitigation 说明 |
| 隔离 | 每个 Repository/API Command/Query 都用两个 Tenant/Workspace 验证 |
| 不可变 | Update/Delete Trigger 与重复处理一致/非确定性测试 |
| 任务 | 每一步崩溃、Lease Expiry、Duplicate Claim、Retry、Exhaustion、Cancel、Restart |
| Source | Text、Markdown、PDF、DOCX、Captured HTML 的成功/失败路径 |
| 上传安全 | MIME Spoof、Executable、Macro、Encryption、Malformed ZIP/XML、Bomb、Size Limit |
| SSRF | Loopback、RFC1918、Link-local、IPv6、Metadata、Rebinding、Redirect、Proxy Bypass |
| Worker 契约 | Java/Python OpenAPI 校验，响应边界与 Digest/Ordinal/Offset 校验 |
| API | OpenAPI Conformance、Auth Scope、稳定错误、Content Streaming、不泄露存在性 |
| 运维 | Metric Cardinality、Log Redaction、Audit Atomicity、Graceful Shutdown、Health |
| 部署 | 无公共 Parser Route/Host Port，Worker 非 Root/只读，Compose Healthy |
| 国际化 | 中英文文档匹配与未来 Client Key；Backend 不依赖用户消息文本 |
| 端到端 | Create Base -> 各 Source Ingest -> READY -> 检查血缘 -> Retry/Resync/Tombstone |

适用的 Java、Python、Contract、Migration、Security、Dependency、Container、Compose 检查必须通过。只有修改前端文件时才要求 TypeScript/Playwright；P2.1 未获授权把 Knowledge 页面改为 Live。

## 13. 发布与回滚

- 迁移是增量的，P1 Binary 会忽略新表；
- `APVERO_KNOWLEDGE_ENABLED=false` 继续作为默认值；禁用时端点返回 `APVERO_KNOWLEDGE_DISABLED`，不能伪造成功；
- 局部分支只能由运维显式启用，启用也不改变产品页面状态；
- 禁用后，在有上限 Drain 后停止新 Claim 和变更；持久任务仍可检查；
- 回滚使用上一个兼容 Binary，新表与数据保留；
- 不自动破坏性清理，不提供 Down Migration；
- P2.1 不能创建 RAG Release，因此 ADR 中后续的 P2-compatible Rollback Floor 尚未生效。

## 14. 自我批判与拒绝的捷径

1. PostgreSQL `bytea` 有利于自托管，但限制文件和语料规模；必须公布实测上限，不能宣称无限摄取。
2. 无状态 Python Worker 降低 Control Plane 风险，却引入双语言契约；双向契约测试不可省略。
3. PostgreSQL Lease 运维简单，但本质是 At-least-once；幂等输出与 Crash Test 必须完成。
4. PDF/DOCX 解析既敏感又不完美；P2.1 保证有边界、可见失败、有血缘，不保证 OCR 或版面还原。
5. Web Capture 比只上传更实用，但显著增加 SSRF 风险；先校验 URL、再使用普通且未固定 DNS 的 HTTP Client 并不安全。
6. 复用当前通用 HTTP Scope 是现实选择，但不等于细粒度 ABAC；P2.1 不能虚假宣传资源级权限。
7. 当前 Audit Interface 的结构化 Details 能力有限；P2.1 可记录安全的 Action/Resource/Outcome，扩展公开形状必须另行审查。
8. 现有部分模块存在直接查询 Workspace 表的技术债；Knowledge 必须遵守已批准边界使用 `WorkspaceScopeCatalog`，不能复制。
9. 只保存 Job 最新错误会限制逐次尝试取证。P2.1 保存 Current Step、Attempt Count、稳定终态证据、Audit Mutation 和 Telemetry；只有验收证明不足时，才能在先审查权威文件后增加 Append-only Job Event Ledger。
10. P2.1 API 技术上可调用而产品保持禁用，可能让贡献者困惑；Contract Status、Feature Flag 与文档必须一致说明它是内部里程碑，不是完成的 RAG。

## 15. 验收门禁

只有六个切片与验证矩阵全部通过，并且下面这句话可以无保留成立时，P2.1 才能提请维护者验收：

> 在已授权 Workspace 中，Apvero 能安全捕获受支持 Source、保留不可变 Revision、在失败后恢复持久化处理、恰好一次生成确定且可追踪的 Chunk，并提供真实的检查、重试、重新同步、Tombstone 行为；整个过程不增加基础设施，也不虚假宣称已完成 RAG。

验收只更新 P2.1 里程碑证据。P2 继续保持 `in-progress`，Knowledge 默认继续禁用，下一里程碑是 P2.2 不可变 Index 与 Retrieval Lab。
