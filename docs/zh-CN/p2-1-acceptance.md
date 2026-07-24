# P2.1 持久化摄取主干验收

状态：维护者已于 2026-07-24 批准验收。

目标：P2 里程碑 P2.1。P2 继续保持 `in-progress`。

## 已验收结果

P2.1 验收陈述已经无保留成立：

> 在已授权 Workspace 中，Apvero 能安全捕获受支持 Source、保留不可变 Revision、
> 在失败后恢复持久化处理、恰好一次生成确定且可追踪的 Chunk，并提供真实的检查、
> 重试、重新同步和 Tombstone 行为；整个过程不增加基础设施，也不虚假宣称已完成 RAG。

P2.1 只交付持久化摄取主干，不交付 Embedding、不可变 Knowledge Index Version、
Retrieval Lab、Application Binding、Release Binding、带引用 Run 或 Live Knowledge
产品页面。这些仍属于 P2.2–P2.4。

## 证据映射

| 门禁 | 已验收证据 |
|---|---|
| 架构 | Spring Modulith 与 ArchUnit 验证 `knowledge` 边界和获批依赖 |
| 迁移 | V8 Clean Install、V7 Upgrade、Scoped Foreign Key、Constraint、Index、不可变 Trigger 与 Forward-only Mitigation |
| 隔离 | Repository 与 REST Command/Query 在跨 Tenant/Workspace 时默认拒绝 |
| 不可变 | Source Revision、Document、Chunk 变更保护，以及确定性重放/非确定性拒绝 |
| 任务 | 持久化 Lease、独占 Claim、每个持久步骤崩溃、过期恢复、重试、耗尽、取消、优雅关闭与重启恢复 |
| 来源 | Text、Markdown、PDF、DOCX 与公开网页 HTML 的成功路径和有边界失败路径 |
| 来源安全 | Media Detection、MIME Spoof、Executable、Macro、Encryption、Malformed、Archive、Size Limit 与固定地址 SSRF 控制 |
| 契约 | Worker Payload/Response 的 OpenAPI 3.1 校验，以及 Platform P2.1 Method/Path 一致性 |
| 运维 | 原子 Audit Mutation、低基数 Metric、安全错误、日志脱敏测试、Health 与默认关闭发布 |
| 部署 | Worker 仅内部可达、非 Root、只读，无公共 Parser Route，可选 Knowledge Compose Overlay，PostgreSQL 是唯一强制有状态依赖 |
| 国际化 | 中英文计划、验证、运维、Roadmap 与验收证据一一对应 |
| 端到端 | 真实 Compose 创建五类 Source 并到达 `READY`，检查血缘、验证未变化重同步和 Tombstone 拒绝，再证明 Platform 重启后的持久重试 |

切片证据：

- [`p2-1a-verification.md`](p2-1a-verification.md)
- [`p2-1b-verification.md`](p2-1b-verification.md)
- [`p2-1c-verification.md`](p2-1c-verification.md)
- [`p2-1d-verification.md`](p2-1d-verification.md)
- [`p2-1e-verification.md`](p2-1e-verification.md)
- [`p2-1f-verification.md`](p2-1f-verification.md)
- [`p2-1-acceptance-candidate.md`](p2-1-acceptance-candidate.md)

## Git 与 CI 证据

- 候选：[PR #10](https://github.com/huangheng-dev/Apvero/pull/10)，Head 为
  `7f529f0e65e9ae0550526b9b1ff6ad555458f5e6`。
- 验收 Merge：`f259de456aa9e902b82ad84460e6fd6185a0a289`。
- 候选 CI：[运行 30028841073](https://github.com/huangheng-dev/Apvero/actions/runs/30028841073)。
- 合并后 `main` CI：[运行 30029244123](https://github.com/huangheng-dev/Apvero/actions/runs/30029244123)。
- 两次运行的 Backend、Console、Worker、Contracts、Compose Configuration、Container
  Build 和 `knowledge-compose` 均通过。

## 验收后的状态

- `architecture/delivery-stages.yaml` 把 P2.1 记录为 `completed`。
- P2 继续为 `in-progress`；下一里程碑是 P2.2。
- `modules/knowledge` 继续为 `in-progress` 和 `contract-only`。
- `APVERO_KNOWLEDGE_ENABLED=false` 继续作为默认值。
- 本次里程碑迁移不会让任何产品页面或 REST Operation 变为 Live。
- 本次验收更新不修改 Invariant、依赖边界、公共契约、发布语义、安全策略、Migration、
  有状态依赖或技术基线。

## 回滚与后续治理

运维回滚继续默认拒绝：关闭 Knowledge Runner，等待有界 Drain，使用上一版兼容 Binary，
保留增量 V8 Row 用于诊断和向前恢复。不得手工删除不可变表或清理活动 Lease。

两项非阻断 CI Annotation 留作独立维护工作：`pnpm/action-setup@v4` 报告其 Node Action
Runtime 已弃用，GitHub Actions 会修复 `gradlew` 的 Linux 可执行位。它们没有改变成功的
P2.1 证据，也不会被悄悄夹带进本次阶段迁移提交。
