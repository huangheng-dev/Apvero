# P2.1c Source Command 验证

状态：本地验证完成的实施检查点
日期：2026-07-22
阶段：P2 / 里程碑 P2.1c

## 已交付边界

P2.1c 在 P2.1b Schema 上闭合第一个可持久化的 Knowledge 编写工作流：

`创建 Base -> 添加内联或上传 Source -> 保存不可变 Revision -> 排队 Ingestion Job -> 查看 Revision/内容 -> 添加有变化的 Revision 或记录 No-op -> Tombstone Source`

公共 Java 边界包含 Command、Catalog、Receipt 与 Read Model 类型。内部 Service 负责编排，并且只使用获批的 Identity Workspace Scope API 与 Governance Audit API。REST Adapter 实现已批准的 P2.1 Base、Source、Revision、上传、内容和 Tombstone 路由，但不改变这些契约的 `contract-only` 发布状态。

Knowledge 继续默认关闭。启用后只激活本 Source Command 边界；解析、切块、Embedding、索引、检索、Application Binding、Release 固定和 Grounded Run 仍不可用。

## 接受内容与安全边界

- 内联输入接受 `TEXT` 与 `MARKDOWN`，按 Unicode Code Point 计数，保存精确 UTF-8 字节，并执行可配置的字符数和 Snapshot 字节数限制。
- 上传输入根据实际捕获字节分类，不信任文件名或客户端声明的 Media Type。
- PDF 必须在开头附近包含有效 Header，并包含 EOF Marker；加密 PDF 被拒绝。
- DOCX 必须满足最小 OpenXML 结构；格式错误、路径不安全、重复 Entry、宏和嵌入对象均被拒绝，同时限制 Entry 数量和解压后字节数。
- 可执行文件签名、非法 UTF-8 文本、不支持的媒体、空内容和超限 Snapshot 均默认拒绝，并返回稳定错误码。
- 原始文件名被压缩为安全 Leaf Name，并在不切断 Unicode Code Point 的前提下限制长度。
- 每个接受的 Snapshot 都获得标准 SHA-256 Digest。同一 Source 的相同内容返回 `UNCHANGED` Receipt，不创建 Revision 或 Job。

默认自托管限制可通过 `APVERO_KNOWLEDGE_MAX_INLINE_CHARACTERS`、`APVERO_KNOWLEDGE_MAX_SNAPSHOT_BYTES`、`APVERO_KNOWLEDGE_MAX_DOCX_ENTRIES` 和 `APVERO_KNOWLEDGE_MAX_DOCX_EXPANDED_BYTES` 配置。

## 隔离、授权、审计与事务行为

- 每个 Catalog 操作都解析已授权的 `WorkspaceScope`；每条 Repository 查询都重复 Tenant 与 Workspace 条件。
- 其他 Workspace 拥有的标识与不存在的标识无法区分。
- 现有平台授权保护 Read 与 Management 路由。HTTP 测试证明只读 API Key 不能创建 Base，但可以流式读取已授权的不可变 Snapshot。
- Source Tombstone 幂等，并阻止未来 Revision，同时保留不可变历史。
- Base 创建、Source/Revision 变更、Job 创建及其 Audit Event 位于同一 Spring Transaction。通过强制 Audit Insert 失败，已证明业务变更会一起回滚。
- 后端失败暴露稳定 Code，供客户端使用 English 或简体中文本地化；公共 Knowledge API 不暴露 Provider 或 Parser 实现类型。

## 验证证据

本检查点包含精确捕获、Digest、Unicode 限制、媒体嗅探、可执行文件拒绝、严格 UTF-8、PDF 安全、DOCX 结构、Active Content 拒绝和有界输入的单元测试。PostgreSQL Testcontainers 集成测试验证：

- 完整的内联 Source 与 Changed/No-op Revision 工作流；
- 根据字节识别上传类型，并保持 Source Type 稳定；
- 跨 Workspace 默认拒绝；
- 业务与 Audit 原子回滚；
- HTTP 授权与不可变内容流式读取；
- 幂等 Tombstone 和 Tombstone 后禁止变更。

以下全仓检查已在本地通过：

- 完整 Gradle `test` 与 Platform Server `bootJar`，包含 Spring Modulith、ArchUnit、P1 Governance 和全部 P2.1 检查点；
- Console 严格 Typecheck、5 个 Vitest 测试、必需语言 Key 覆盖检查和生产构建；
- Worker 的 9 个测试、Ruff 与 `pip-audit`，未发现第三方依赖的已知漏洞；
- JSON Contract 解析与两份 OpenAPI 的 Redocly 验证（保留两个既有 Health Route 警告）；
- 默认 Compose 与 `knowledge` Profile Compose 配置；
- 使用仓库 Dockerfile 干净构建 Platform Server Container；
- Diff 空白与 Credential Signature 扫描。

## 回滚与缓解

P2.1c 不增加数据库迁移，也不修改已批准的 Invariant 或公共契约。运维回滚使用上一版 P2.1b-compatible Binary，并保持 `APVERO_KNOWLEDGE_ENABLED=false`。P2.1c 已写入的数据继续符合 V8，并保留用于诊断或后续向前恢复；回滚不得删除 Source Revision、Job 或 Audit Event。

如果需要在不回滚 Binary 的情况下立即隔离 Source Capture，可关闭 Knowledge 并重启平台。现有非 Knowledge 的 P1 工作流继续可用。

## 尚未交付

在该检查点，P2.1c 尚未实现 Web Capture（P2.1d）、Worker Parsing/Chunking（P2.1e）、
Durable Job Execution and Operations（P2.1f）、Embedding、索引、检索、Application
Binding、Release 或 Grounded Run。WEB Source 路由返回稳定的 Not-available 错误，
不伪造成功。产品 Prototype 页面继续明确为 Demo-only。因此当时 P2 与 P2.1 继续为
`in-progress`，P2 REST Contract 继续为 `contract-only`。
