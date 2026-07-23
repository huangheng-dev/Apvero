# P2.1e Worker 处理验证

状态：已合并的实施检查点；P2.1 总体验收仍未完成。

## 已交付边界

P2.1e 为不可变 Source Revision 快照实现了已批准的内部 Worker API 1.0。无状态 Python Worker 只接收 Java 控制面提供的字节，校验其 SHA-256 身份，解析五种已批准媒体类型之一，规范化文本，生成确定性 Chunk 与来源锚点，并返回带版本的输出。它不会获取 URL、访问 PostgreSQL、授权用户、解析 Secret 或分配领域身份。

Java Knowledge 模块在数据库事务之外调用 Worker。它限制请求时间和响应大小，校验返回的每个身份、版本、序号、摘要、Unicode 码点偏移、锚点和警告码，然后在独立事务中持久化已接受输出。

P2.1e 不宣称已经实现自动摄取。P2.1f 仍负责租约、耐久步骤转换、重试调度、取消、重启恢复，以及从排队 Job 调用本处理边界。

## 确定性处理

已实现的 Profile 为 `apvero-default@1.0.0`；Parser 与 Chunker 算法发布明确的语义版本。相同不可变字节和 Profile 会产生相同的规范化 Document 摘要、Chunk 文本、偏移、锚点与版本。

支持的媒体类型：

- UTF-8 纯文本；
- 带标题和段落锚点的 UTF-8 Markdown；
- 提取前移除活动/非内容元素的 HTML；
- 带页面锚点、加密拒绝和页数限制的 PDF；
- 带段落/标题锚点和压缩包检查的 DOCX。

Chunk 偏移使用 Unicode 码点，而不是 UTF-16 单元或编码字节位置。Chunk 边界稳定、重叠明确，Java 会独立验证每个 Chunk 摘要。

## 资源与文档安全

- 输入字节、规范化输出、Document 数量、Chunk 数量、标题、锚点和响应大小均有上限。
- PDF 页数受限；加密或损坏的 PDF 使用稳定错误码失败。
- 解析 DOCX 前检查条目数、展开大小、单条目压缩比、加密、必要结构和宏载荷。
- Worker 在处理期间执行协作式截止时间检查；Java 调用方还强制执行硬请求截止时间。网络调用期间不会打开控制面事务，因此无响应调用不能占用业务事务。
- Worker Problem 只包含稳定错误码和请求身份，不包含原始文档内容、解析器异常、文件名、URL 或 Secret。

## 幂等持久化

完成事务会锁定带 Workspace Scope 的不可变 Source Revision。Document 与 Chunk 标识由 Revision 和序号确定性派生。首次接受的结果会原子写入全部 Document 与 Chunk；相同结果重放为 No-op；已经完成的 Revision 若出现不同解析输出，则以 `APVERO_KNOWLEDGE_NON_DETERMINISTIC_OUTPUT` 失败，绝不覆盖不可变行。

Revision 锁及每次读写都带 Tenant 和 Workspace 谓词。跨 Workspace 完成会把 Revision 视为不存在。部分插入后发生失败时，整个事务回滚。

## 配置

| 环境变量 | 默认值 | 用途 |
|---|---:|---|
| `APVERO_KNOWLEDGE_WORKER_BASE_URI` | `http://ai-worker:8090` | 不允许路径、查询、片段或凭据的内部 Worker Origin |
| `APVERO_KNOWLEDGE_WORKER_READ_TIMEOUT` | `15s` | Java 端到端请求截止时间 |
| `APVERO_KNOWLEDGE_MAX_WORKER_RESPONSE_BYTES` | `20971520` | 可接受的最大 Worker 响应 |
| `APVERO_KNOWLEDGE_MAX_SNAPSHOT_BYTES` | `5242880` | 提交给 Worker 的最大不可变 Source 快照 |

Knowledge 继续通过 `APVERO_KNOWLEDGE_ENABLED=false` 默认关闭并失败闭合。

## 验证证据

本候选通过以下验证：

- Python 测试覆盖五种媒体类型、重复确定性输出、Unicode 偏移、来源锚点、摘要不匹配、损坏/加密 PDF、DOCX 展开限制、无效 Profile 和超时分类；
- Java HTTP 测试覆盖 Multipart 身份、响应校验、稳定 Worker Problem 映射和有界响应读取；
- PostgreSQL 集成测试覆盖首次完成、相同重放、变化输出拒绝、Workspace 隔离，以及事务中途约束失败后的完整回滚；
- 已实现内部契约的 OpenAPI 校验；
- 验收前执行完整 Java 模块、架构、迁移、前端、Worker、Compose 与容器检查。

## 回滚

使用 `APVERO_KNOWLEDGE_ENABLED=false` 关闭 Knowledge。回退 P2.1e 应用提交即可移除 Worker 处理与完成组件；本切片没有新增迁移，因此无需数据库回滚。已有不可变 Revision、Document 与 Chunk 保持有效但不活动。Knowledge 关闭时，也可以从 Knowledge Compose Profile 独立移除内部 Worker Endpoint。
