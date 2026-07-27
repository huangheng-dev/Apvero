# P2.2d-1 Build API 与规范 Source Snapshot 验证

状态：实施检查点候选；P2.2d 仍为进行中

## 已交付范围

P2.2d-1 实现了已批准的 5 个 Knowledge Index Build 操作：

- 列出某个受 Workspace 约束的 Knowledge Index 的 Build；
- 使用精确语义版本、Embedding Route 与 Source Revision 集合创建排队中的 Build；
- 读取一个已持久化 Build；
- 重试允许重试的失败 Build；
- 取消未被租约占用的排队或等待重试 Build。

创建操作会锁定 ACTIVE Index，解析与 Provider 无关的已发布 Embedding Route 快照，只选择
ACTIVE 且已完整处理的 Source Revision，以确定性顺序组织 Source 集合，并在同一事务中持久化
Build、固定的 Build Revision 和审计事件。同一个 Index 版本使用相同 Route 与 Revision
集合重复请求时返回已有 Build；相同版本改用其他固定输入时返回稳定冲突。

本检查点不会启动 Build 执行，也不会发布 Index Version。这些工作仍属于 P2.2d-2 至
P2.2d-4。

## V11 数据库保护

`V11__p2_2d_build_state_and_publication_guards.sql` 是仅向前的保护迁移，不增加数据表或有状态
依赖。它会：

- 只允许匹配且未发布的 Build 处于 `EMBEDDING/EMBEDDING` 时写入 Entry；
- 在 Entry 写入与 Build 状态转换之间建立串行化约束；
- 强制执行已批准的 Build 状态矩阵、精确的乐观锁递增和单调进度；
- 保证 READY 与 CANCELLED Build 不可变；
- 只允许完整且字段完全匹配的 `VALIDATING` Build 插入 Index Version。

测试同时覆盖空库迁移至 V11 和真实 V10 原地升级至 V11，并验证升级不增加数据表。

## 安全与失败行为

所有读写都会先通过 Identity 解析受约束的 Workspace，然后访问 Knowledge 数据。不存在或
跨 Workspace 的 ID 统一以 Not Found 方式闭合失败。现有 HTTP 授权允许只读凭据列出和读取
Build，但拒绝 Build 变更。

Route Secret 仍位于 Governance 引用之后。Knowledge 不返回 Provider Credential，也不暴露
Provider SDK 类型。每个成功接受的创建、重试和取消命令，都在同一事务中写入受长度限制的审计
事件。强制制造审计写入失败的测试证明 Build 与 Build Revision 会一起回滚。

## 验证覆盖

本检查点包括：

- Java 编译与 Knowledge 单元测试；
- Locale 与时区变化下的确定性 Digest 测试；
- 仅针对 5 个已实现 Build 操作的 OpenAPI/Controller 一致性测试；
- PostgreSQL 空库与升级迁移检查；
- 数据库状态转换、发布、不可变性及并发 Entry 回归测试；
- HTTP 成功、冲突、权限、租户隔离、幂等、重试、取消和审计回滚路径；
- 验收前执行 Spring Modulith/ArchUnit 与仓库级验证套件。

## 发布与回滚

V11 不提供破坏性 Down Migration。在 P2.2d 尚未生成 READY Index Version 之前，回滚步骤是：

1. 使用 `APVERO_KNOWLEDGE_ENABLED=false` 关闭 Knowledge；
2. 停止接受新的 Build 命令；
3. 部署此前兼容 V10 的应用二进制；
4. 保留 V11、Build、Build Revision 与审计证据，用于诊断和后续向前恢复。

不要删除 V11 Function 或 Trigger，也不要删除持久化 Build。后续 P2.2d 检查点一旦发布 READY
Index Version，其验证文档必须在上线前明确兼容二进制下限。

## P2.2d 剩余工作

P2.2d-2 仍需实现 Lease 与 Transition Kernel，P2.2d-3 实现受治理的 Embedding Orchestration，
P2.2d-4 实现 Validation 与 Atomic Publication，P2.2d-5 实现 Operations 与最终双语证据。
本检查点不会把产品页面或 Worker Operation 标记为 Live。
