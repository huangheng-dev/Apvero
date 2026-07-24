# P2.1 验收候选

状态：已于 2026-07-24 验收；本文件作为候选证据记录保留。

验收后 P2.1 为 `completed`，P2 继续为 `in-progress`。Knowledge 默认继续关闭，
全部 P2 REST 操作继续为 `contract-only`。

## 已关闭的审计缺口

| 验收领域 | 可重复证据 |
|---|---|
| 崩溃与重启 | PostgreSQL 集成测试覆盖新 Runner 在 `SNAPSHOTTING`、`PARSING`、`CHUNKING` 崩溃后重新领取同一任务；Compose 在已持久化重试时重启 Platform Server |
| 自动重试 | 瞬态故障持久化 `RETRY_WAIT` 与有界退避，然后由新的 Runner Identity 领取 |
| 五类来源端到端 | 启用 Knowledge 的 Compose 通过真实 Worker 把文本、Markdown、PDF、DOCX 和公开网页 HTML 处理到 `READY` |
| 来源操作 | Compose 检查 Revision 与内容，验证网页未变化同步、Tombstone 以及 Tombstone 后拒绝修改 |
| Worker 契约 | Python 读取仓库内 OpenAPI 3.1 文件，验证运行时 Multipart 字段、成功响应和 Problem 响应 |
| Platform 契约 | Java 反射比较所有 P2.1 Controller Method/Path 与已提交的 `contract-only` OpenAPI 子集 |
| 隔离 | 跨 Workspace 测试覆盖 Base 列表、Inline/Upload 创建、Revision 列表/创建/上传、Web Sync、内容、Tombstone、Job 读取和命令 |
| 运维 | Logback 捕获测试证明原始异常内容、URL 凭证和文档文本不会进入 Runner 日志 |
| 部署 | Knowledge Compose Overlay 让 Platform Server 依赖 Worker 健康状态；CI 使用 `--wait` 启动、验证恢复、输出证据并删除测试 Volume |

## 已取得的验收证据

候选 PR #10 与合并后的 `main` Commit
`f259de456aa9e902b82ad84460e6fd6185a0a289` 全部 Job 均通过，其中包括新增的
`knowledge-compose`。随后维护者明确批准 P2.1 状态迁移。最终证据映射记录在
[`p2-1-acceptance.md`](p2-1-acceptance.md)。

本机隔离 Compose 验证已经成功完成，且没有修改维护者默认的 Apvero Stack 或 Volume。
文本、Markdown、PDF、DOCX 和公开网页 HTML 均通过真实 Platform Server 与 Worker
处理到 `READY`。独立恢复验证先停止 Worker，观察到同一任务第 1 次尝试持久化为
`RETRY_WAIT`，随后重启 Platform Server、恢复 Worker，并观察到该任务在第 2 次尝试时
自动完成。本机结果属于辅助证据，不能替代候选 PR 与合并后 `main` 必须提供的 CI 证据。

真实集成运行还发现并关闭了两个仅靠单元测试没有暴露的问题：Java 调用 Worker 时现在
强制使用 HTTP/1.1，以兼容 Uvicorn；持久化媒体类型使用契约规定的规范值，同时在抓取
元数据中保留服务端声明的完整 Content-Type。

## 回滚

本次补强只增加测试、CI、可选 Compose Overlay 和文档，没有迁移、领域状态、公开操作
或新部署单元。回滚时删除这些文件和检查即可；默认 Compose Profile 与已有 V8 数据不变。
