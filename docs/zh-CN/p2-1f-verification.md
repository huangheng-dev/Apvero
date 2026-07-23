# P2.1f 持久化摄取 Runner 验证

状态：实施候选，不代表完整 P2 功能已经可用。

## 已交付边界

P2.1f 在现有 `knowledge` 模块内闭合持久化源处理循环：

`QUEUED -> SNAPSHOTTING/PARSING -> CHUNKING -> READY`

模块化单体按已授权 Workspace Scope 轮询 PostgreSQL。Claim 事务使用 `FOR UPDATE SKIP LOCKED`，持久化唯一进程租约，增加有上限的尝试次数，并在 Web 或 Worker I/O 前提交。步骤输出由短事务提交。只有租约 Owner、乐观锁版本仍匹配且租约没有过期，结果才可以修改持久化状态。

`READY` 只表示确定性的 Document 和 Chunk 已存在，不表示已经完成 Embedding、Knowledge Index、检索、Application 绑定、Release 或可信 Run。因此完整 P2 验收门禁通过前，所有 P2 REST 操作继续保持 `contract-only`。

## 恢复与控制行为

- 执行语义为至少一次；确定性的 Document/Chunk Identity 让重放成为比较后 no-op。
- 已过期的活动租约可以被回收；达到最大尝试次数后，任务进入可人工重试的终态 `FAILED`。
- 瞬态故障使用带确定性抖动的有界指数退避。只持久化稳定错误码和分类，不存原文、URL、文件名或异常文本。
- 只有可重试的 `FAILED` 任务允许人工重试，并从最后一个持久化步骤重新获得尝试预算。
- 只有 `QUEUED` 和 `RETRY_WAIT` 允许取消；正在进行外部调用的任务不会被虚假标记为已取消。
- 关闭时先停止 Claim，再对有界执行器进行限时排空，最后中断剩余本地工作；持久化租约保证中断任务可以恢复。
- Web 快照完成后，在独立的解析步骤开始前重置尝试预算。

## Workspace 隔离与 API

Runner 只能通过 `WorkspaceScopeCatalog` 枚举 Workspace Identity；每个 Knowledge Repository 调用仍然把完整 Tenant/Workspace Scope 作为第一个参数。实现复用现有 OpenAPI 3.1 路由，没有新增或改名：

- `GET /api/v1/knowledge-ingestion-jobs`
- `GET /api/v1/knowledge-ingestion-jobs/{jobId}`
- `POST /api/v1/knowledge-ingestion-jobs/{jobId}/retry`
- `POST /api/v1/knowledge-ingestion-jobs/{jobId}/cancel`

API 失败返回稳定 `APVERO_*` 错误码。重试、取消、就绪、耗尽和 Web 快照状态变更，会在业务变更的同一个数据库事务中追加审计事件。

## 运维配置

完整 P2 验收前，Knowledge 默认仍关闭。只应在隔离的 P2 环境中显式启用：

| 环境变量 | 默认值 | 用途 |
|---|---:|---|
| `APVERO_KNOWLEDGE_ENABLED` | `false` | 启用 Knowledge 命令、查询和后台能力 |
| `APVERO_KNOWLEDGE_RUNNER_ENABLED` | `true` | Knowledge 已启用时允许 Claim；维护时设为 `false` |
| `APVERO_KNOWLEDGE_RUNNER_CLAIM_BATCH` | `4` | 每次按 Scope 轮询最多 Claim 数 |
| `APVERO_KNOWLEDGE_RUNNER_CONCURRENCY` | `4` | 有界本地工作线程数 |
| `APVERO_KNOWLEDGE_RUNNER_LEASE_DURATION` | `60s` | 独占结果提交窗口；应大于外部步骤最坏耗时 |
| `APVERO_KNOWLEDGE_RUNNER_POLL_INTERVAL` | `1s` | 轮询间隔 |
| `APVERO_KNOWLEDGE_RUNNER_BACKOFF_BASE` | `2s` | 初始重试等待 |
| `APVERO_KNOWLEDGE_RUNNER_BACKOFF_MAXIMUM` | `5m` | 重试等待上限 |
| `APVERO_KNOWLEDGE_RUNNER_GRACEFUL_DRAIN` | `30s` | 本地关闭排空上限 |

受支持的 Compose 启用方式使用 Knowledge Overlay。它在不改变默认禁用 Profile 的前提下启用
Knowledge，并让 Platform Server 启动依赖 Worker 健康状态：

```text
docker compose --profile knowledge \
  -f deploy/compose/compose.yaml \
  -f deploy/compose/compose.knowledge.yaml \
  up -d --build --wait
```

Runner 发布低基数 Micrometer 指标：

- `apvero.knowledge.ingestion.claimed`：只按步骤标记；
- `apvero.knowledge.ingestion.queue.wait`：只按步骤标记；
- `apvero.knowledge.ingestion.step.duration`：按步骤、结果和错误分类标记；
- `apvero.knowledge.ingestion.failures`：按步骤、分类和可重试性标记；
- 输入字节数、输出 Document/Chunk 数和 Worker Latency 只按受限的 Source 或算法维度标记。

Tenant、Workspace、Source、Revision、Job、URL、文件名和内容都不会成为指标标签。运维日志只包含步骤、稳定分类和稳定错误码。

安全回滚方式是设置 `APVERO_KNOWLEDGE_RUNNER_ENABLED=false`，然后等待配置的排空时间。不要手工删除 Job 或清理活动租约。P2.1f 没有新增迁移，因此旧二进制仍能查看全部 V8 状态。

## 验证证据

自动化覆盖包括：

- 确定性有界退避和非法配置；
- 按 Scope 的独占 Claim 与 `SKIP LOCKED`；
- 租约过期恢复、有界耗尽和人工重试；
- 真实的取消/重试状态冲突；
- 跨 Workspace 查询和命令拒绝；
- 从 Inline Source 到租约 Worker 执行、原子 Document/Chunk、`READY` 和审计的端到端闭环；
- 已有五种媒体类型、Worker 校验、Web SSRF 防护、不可变重放、迁移、架构和国际化测试。
- 在持久化 `SNAPSHOTTING`、`PARSING` 和 `CHUNKING` 每个步骤崩溃后的恢复；
- 瞬态故障由新 Runner Identity 自动领取重试；
- Worker 请求和响应直接依据仓库内 OpenAPI 3.1 契约验证；
- Platform REST Method/Path 与 P2.1 OpenAPI 子集的一致性验证；
- 使用故意包含敏感信息的异常消息验证日志脱敏；
- 启用 Knowledge 的 Compose 闭环，覆盖五种来源、血缘检查、网页未变化同步、
  Tombstone 拒绝、Worker 中断、Platform 重启、自动重试、健康恢复和最终 `READY`。
- 本机隔离 Compose 证据覆盖五类来源，并证明同一个持久化任务跨 Platform Server
  重启后从第 1 次尝试的 `RETRY_WAIT` 进入第 2 次尝试的 `READY`。
- HTTP/1.1 Worker 传输和规范媒体类型持久化的集成回归测试。

必跑命令：

```text
./gradlew test check
```

只有 CI 证据全部通过且维护者批准本阶段切片后，P2.1f 才可验收。
