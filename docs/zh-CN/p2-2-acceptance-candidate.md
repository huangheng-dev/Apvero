# P2.2 不可变索引与检索实验室验收候选

状态：候选记录于 2026-07-31 汇总，尚未验收。

P2.2 与 P2.2f 均保持 `in-progress`。Knowledge 仍默认禁用，Knowledge 产品页面仍未上线。
只有维护者批准且候选分支的干净环境 CI 全绿后，才能把这两个状态改为 `completed`。

## 候选成果

当前实现拟支持以下有边界的结论：

> 在一个已授权工作空间内，Apvero 能把确定性、不可变 Chunk 转换为受治理且维度安全的
> 不可变 Knowledge Index Version，在持久化校验与恢复后原子发布，并通过 PostgreSQL
> 精确余弦检索检查有界血缘、当前留存披露和类型化无证据结果；全过程不发生跨工作空间
> 泄漏、生产引用可变、费用重复结算或无依据的规模声明。

本里程碑不绑定 Application 或 ReleaseBundle，不生成带引用答案，不上线 Knowledge
页面，不增加近似或混合检索，也不把确定性本地 Embedding 适配器说成语义质量证明。
这些边界仍属于 P2 后续里程碑。

## 已实现证据

| 范围 | 候选证据 |
|---|---|
| 架构 | Knowledge 保持单一模块化单体边界，只使用获准的 Identity、Capability Registry 与 Governance 公共 API |
| 持久化 | 受作用域约束的不可变 Index、Build、Entry、Embedding Route 与 Retrieval Policy 状态由 PostgreSQL 约束、触发器及前向 Flyway 迁移保护 |
| Embedding | 报价、准入、派发、结算、歧义与对账路径均持久化，绝不盲目重放结果不明的 Provider 调用 |
| 构建 | 覆盖持久化租约、有界并发、校验、失败分类、重试、耗尽、取消、重启对账和原子发布 |
| 检索 | 精确余弦 SQL 在排序前完成作用域过滤，采用确定性顺序与预算，并返回有界 `MATCHES` 或类型化 `NO_EVIDENCE` |
| 隔离 | Tenant/Workspace、Index Version、Build、Entry、Policy 与 Retrieval 路径均失败关闭，包括使用真实外部标识的场景 |
| 历史 | READY 不可变 Version 在后续来源被墓碑化后仍可复现；新 Build 会排除已墓碑化来源 |
| 治理 | 费用只预留和结算一次；读取时应用当前留存策略；原始查询、内容、URL、Provider 身份与密钥值不会进入不安全遥测 |
| 契约 | 已实现的 Knowledge Controller 方法符合已提交 OpenAPI 3.1 契约；结构化载荷保持稳定 Schema 与错误码 |
| 运维 | 保留健康状态、低基数指标、安全日志、默认禁用发布方式以及 PostgreSQL 唯一强制有状态依赖 |
| 国际化 | 英文与简体中文的计划、验证记录、运行边界和本候选记录一一对应 |

## 本地完整门禁证据

2026-07-31 已通过：

- `.\gradlew.bat test :apps:platform-server:bootJar`；
- Console 冻结安装、严格类型检查、Vitest、语言覆盖、占位符校验与生产构建；
- Worker 锁定依赖同步、19 个 Python 测试与 Ruff；
- 全部九个 JSON Schema、两份 OpenAPI 3.1 文档与两套 Compose 配置；
- 核心层 Provider 禁止依赖扫描；
- Platform Server 与 AI Worker 镜像构建、非 root 用户、Worker 基础镜像摘要锁定及禁止运行时模块检查；
- PostgreSQL、Platform、Worker 隔离栈全部健康；
- 跨 Platform 重启的持久化摄取重试，同一任务在第 2 次尝试完成；
- Index Runner 禁用行为、两个待构建 Build 自动发布，以及持久化 `IN_FLIGHT` Build 的崩溃恢复；
- 隔离栈清理，包括其临时容器、网络和 PostgreSQL 卷。

完整 Java 执行覆盖 Spring Modulith、ArchUnit、单元、集成、Testcontainers、Flyway 以及
PostgreSQL/pgvector 验证。整个过程没有使用维护者现有的默认 Apvero 栈或数据卷。

## 已测性能边界

[`p2-2f-performance-envelope.md`](p2-2f-performance-envelope.md) 记录了可执行基准、参考机器
和完整执行计划。保守支持的完整工作流上限为每个不可变 Index Version 最多 1,000 个
Entry、256 维。仅检索证据覆盖 256 维 10,000 个 Entry，以及 384、768、1,536 维各
5,000 个 Entry。包括八读者写入压力在内的所有声明场景，其本地数据库/JDBC p95 均低于
300 ms。这是参考环境实测边界，不是可移植 SLA。

## 尚未关闭的外部门禁

当前本地环境无法完成两项依赖公网的检查：

1. `pip-audit` 无法稳定访问 PyPI 或 OSV。最后一次 OSV 请求以
   `SSL: UNEXPECTED_EOF_WHILE_READING` 结束；它没有生成漏洞结论，因此不能记为通过。
2. 五来源 Compose 流程已完成文本、Markdown、PDF 和 DOCX，但要求的真实
   `https://example.com/` 抓取在三次有界重试后失败；宿主机也出现相同外网连接失败。
   产品按设计持久化了类型化 `APVERO_KNOWLEDGE_WEB_FETCH_TIMEOUT` /
   `APVERO_KNOWLEDGE_WEB_FETCH_FAILED` 结果。

这些失败不足以成为修改代码、超时、架构或测试夹具的理由。维护者验收前，干净环境候选
CI 必须通过这两项检查。

## 验收流程

1. 提交并发布 P2.2 累计候选分支。
2. 只创建一个 P2.2 候选 Pull Request。
3. 要求全部干净环境 CI 任务通过，包括依赖审计与 `knowledge-compose`。
4. 记录候选 PR、头提交及 CI Run 标识。
5. 请求维护者明确验收 P2.2。
6. 只有批准后，才把 P2.2f 与 P2.2 标为 `completed` 并建立最终验收记录。

## 回滚

Knowledge 保持可选启用。运维回滚应禁用 Knowledge Runner、排空有界任务、恢复上一兼容
版本，并保留追加写入的不可变记录用于诊断与前向恢复。不得删除不可变表、修改 READY
Version 或手工清除活动租约。候选文档与测试可独立回滚，不改变生产数据。
