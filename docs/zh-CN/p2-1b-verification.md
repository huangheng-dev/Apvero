# P2.1b 有 Scope 的不可变持久化验证

状态：本地验证完成的实施检查点
日期：2026-07-22
阶段：P2 / 里程碑 P2.1b

## 已交付边界

P2.1b 增加向前迁移 `V8__p2_1_durable_knowledge_ingestion.sql`，并为以下对象建立内部 jOOQ 持久化边界：

- `knowledge_base`；
- `knowledge_source`；
- `knowledge_source_revision`；
- `knowledge_document`；
- `knowledge_chunk`；
- `knowledge_ingestion_job`。

每张表都重复保存 `tenant_id` 与 `workspace_id`。组合外键在 Base、Source、Revision、Document、Chunk 与 Job 血缘之间保持 Scope。每个 Repository 操作都必须把 `WorkspaceScope` 作为第一个参数；写入前比较 Row 与调用 Scope，读取时同时包含 Tenant 与 Workspace 条件。

Knowledge 继续默认关闭。本检查点不增加 Controller、真实产品数据、Source Command、Parser Endpoint、Job Runner、Embedding、Index、Retrieval、Application Binding、Release 或 Grounded Run。

## 数据库强制规则

- Base Slug 在 Workspace 内唯一，可变行具有正数乐观锁版本。
- Source Type、状态、Web Locator 形状、Latest Revision 形状和 Tombstone 元数据都有 Check Constraint。
- Source Snapshot Digest 使用标准 `sha256:<64 个小写十六进制字符>` 标识；已接受 Snapshot 的字节长度必须与实际字节一致。
- Source Revision Number 与 Digest 在同一 Source 内唯一。
- PostgreSQL Trigger 拒绝对 Source Revision、Document、Chunk 的普通 `UPDATE` 和 `DELETE`。
- Document 与 Chunk Ordinal 在各自不可变父对象内唯一。
- Chunk Offset 使用受检查的左闭右开区间，可选 Anchor 从 1 开始。
- Job 的 Status/Step 组合、尝试次数、重试时间、Lease、错误形状、完成时间和幂等标识都有约束。
- Job 到 Revision 的外键同时包含所属 Source 与 Tenant/Workspace Scope。

## 验证证据

以下检查已在带 pgvector 的 PostgreSQL 18 上通过：

- 空数据库从头执行 Flyway 到 V8；
- 明确迁移到 V7，再原地只执行一次 V8；
- 六张表、Scope 外键、Claim Index 和不可变 Trigger 全部存在；
- 六类持久化 Row 都能通过内部 jOOQ Repository 往返映射；
- 使用第二个 Tenant/Workspace 读取相同标识时不返回任何 Row；
- Scope 不匹配的写入在 SQL 前失败，跨 Scope 外键攻击在 PostgreSQL 中失败；
- 非法 Digest、Offset、Job State 和重复 Source Digest 均默认拒绝；
- Source Revision、Document、Chunk 的更新与删除均失败；
- Snapshot Byte Array 使用防御性复制；
- 架构测试强制所有 Repository 操作必须首先接收 `WorkspaceScope`；
- 完整 Gradle `test` 任务通过，包括 Spring Modulith、ArchUnit、P1 Governance、P2.1a 与 P2.1b 测试。

## 回滚与缓解

V8 是增量迁移，不提供破坏性 Down Migration。在任何 P2 API 或 RAG Release 真实上线前，回滚使用上一版 P2.1a/P1-compatible Binary，并保持 `APVERO_KNOWLEDGE_ENABLED=false`；旧 Binary 会忽略这六张新表。已持久化的 Knowledge Row 原样保留，用于诊断和后续向前恢复。运维回滚不得删除这些表或不可变 Trigger。

如果 V8 迁移自身失败，Flyway 与 PostgreSQL 事务型 DDL 会让 V8 保持未应用状态，上一版 Binary 仍是恢复目标。未来任何修改这些表的迁移都必须继续向前，并保留已经写入的不可变血缘。

## 尚未交付

P2.1b 不实现 P2.1c Source Command、P2.1d Safe Web Capture、P2.1e 生产 Worker Processing 或 P2.1f Durable Execution and Operations。P2 与 P2.1 继续为 `in-progress`，所有 P2 REST Contract 继续为 `contract-only`。
