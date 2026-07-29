# P2.2d-5 运维与最终验证

状态：实现检查点候选；仍需维护者验收

## 身份与证据边界

- 源基线：`ba488407c0a01406a204cd8b1d067ac399867fb0`
- 已验证实现提交：`485dd4926d557e4dd0f26251bf8dd7f09486a85a`
- 本地平台镜像：
  `sha256:c7f2614961989aa88ab29a618f72aa92611bb3df6451fde9df3606e68444f41f`
- 本地 AI worker 镜像：
  `sha256:84b4ce3bb3710db35fbe1e2cc0d5ca0abe55c9dad31f72c59ba8a468d289c440`

镜像身份只证明本地 Compose 运行。实现身份固定已测试代码和验收资产；后续证据提交
只修改文档。完整推送候选仍必须通过 GitHub CI，才能成为发布证据。

## 本次交付的验证面

P2.2d-5 不新增生产领域行为，只新增：

- 在基础 Compose 中显式声明默认关闭的 Build runner 变量；
- 确定性的双租户、双 workspace Knowledge、Route 与 Index fixtures；
- 仅使用标准库、只通过已接受 REST API 请求 Build 的验收驱动；
- 对关闭 runner、自动 READY、重放、隔离、遥测和崩溃恢复的断言；
- 并发度 1、4、8 下，20 个 workspace、100 个 Build 的调度参考测试；
- 1、100、1,000 条 Entry 的 PostgreSQL 验证与原子发布测试；
- 100 次重复 Build 观测下的指标基数回归测试；
- 接入 `knowledge-compose` GitHub Actions job 的同一套验收流程。

当前阶段还没有已接受的 Index 和 Embedding Route 创建 API，因此验收 fixture 只通过 SQL
加载这些确定性前置条件及摄取证据。它不会直接插入 Build、READY Version、向量 Entry
或发布审计事件。Build 由公开 API 请求，全部运行态和发布态由真实 runner 创建。

## 本地 Compose 环境

- 主机：Windows、PowerShell、Docker Desktop
- 隔离 Compose 项目：`apvero-d5-verification-local`
- PostgreSQL 镜像：`pgvector/pgvector:pg18`
- 发布端口：平台 `127.0.0.1:18080`，PostgreSQL `127.0.0.1:15432`
- Build runner：并发 `1`、claim batch `1`、租约 `6s`、外部超时 `2s`、
  commit margin `1s`、轮询 `200ms`、重试退避 `200ms..2s`、drain `2s`
- Provider：本地确定性 Embedding adapter，256 维，不使用付费密钥

证据捕获后已删除隔离容器、网络和 volume。镜像仅保留为本地构建缓存，不是已发布制品。

## Compose 结果

| 证明项 | 结果 |
| --- | --- |
| fixture Build 请求前关闭 runner | 通过；两个 Build 保持 `QUEUED`、attempt `0` |
| 关闭态健康契约 | 通过；`UP`、`runnerEnabled=false`，生命周期和 scan 为 `disabled` |
| 双 workspace 自动执行 | 通过；两个 Build 都到达不可变 `READY` |
| 相同请求重放 | 通过；返回相同 Build，未创建重复 Version |
| 发布持久化 | 通过；主 workspace 的 Version/Entry/audit 数为 `1:1:1` |
| 跨 workspace 读取与取消 | 通过；两者都以 `404` fail closed |
| 健康详情词汇 | 通过；全部必需且有界字段存在 |
| 指标名称和标签键 | 通过；已执行路径的 family 存在且标签有界；单元测试枚举全部必需 family |
| 指标脱敏 | 通过；不含租户、workspace、Build、端点、内容或 lease owner |
| in-flight 工作期间停止平台 | 通过 |
| 重启后的过期租约恢复 | 通过；Build 到达 `READY` |
| 恢复 attempt 语义 | 通过；恢复同一持久化 attempt，`attemptCount=1` |
| 恢复持久化 | 通过；Index/Version/新增 Entry 数为 `2:2:1` |

恢复的 Build 为 `8f80f853-9ed3-495c-9373-648cfcb429a9`，不可变 Version 为
`53cd27d1-3a58-5efd-9fd4-407413140435`。它们只是 fixture 运行身份，不是指标标签或产品默认值。

## 参考边界观测

以下数据是本地观测，不是通用延迟目标。

### 调度公平性

| 可领取 Build | Workspace | 并发度 | 观测完成时间 |
| ---: | ---: | ---: | ---: |
| 100 | 20 | 1 | 1,870 ms |
| 100 | 20 | 4 | 370 ms |
| 100 | 20 | 8 | 181 ms |

每个 workspace 都获得 claim，本地容量从未被突破，测试在显式 30 秒超时内完成。

### PostgreSQL 验证与发布

| Entry 数 | 排队至嵌入/索引完成 | 验证 | 原子发布 |
| ---: | ---: | ---: | ---: |
| 1 | 3,133 ms | 809 ms | 495 ms |
| 100 | 3,586 ms | 549 ms | 192 ms |
| 1,000 | 72,248 ms | 2,324 ms | 717 ms |

1,000 条 Entry 场景保持单一原子 Version，并在 120 秒超时内完成。本地测试的嵌入阶段
占主要时间，因为它按有界 batch 持久化 256 维向量。该结果支持已批准参考边界，但不构成
生产 SLA，也不支持超过 1,000 条 Entry 的规模声明。

## 失败与恢复矩阵

自动化单元、PostgreSQL 和 Compose 断言覆盖：

- 两层功能开关、dispatcher 不可用和防重叠轮询；
- 有界准入、执行拒绝和优雅 drain；
- 空 workspace 扫描、轮转公平和运维扫描失败；
- 活跃租约排他、到期接管、续租和 stale owner fencing；
- Entry 持久化、Governance 结算、Build 进度、验证、发布与审计的崩溃边界；
- retryable、permanent、validation、security、internal 和 ambiguous 归一化失败；
- partial、extra、missing、错误 lineage、错误维度和错误 digest 制品；
- 相同重放、并发发布者、独立 Index 发布者和审计回滚；
- 自动 `EMBEDDING -> INDEXING -> VALIDATING -> READY`；
- Compose 关闭态、进程重启恢复和跨 workspace 拒绝。

## 累计门禁

最终本地运行使用：

```text
gradlew clean check bootJar
pnpm typecheck
pnpm test
pnpm i18n:check
pnpm build
uv run pytest -q
uv run ruff check src tests benchmarks
uv run pip-audit
python -m ruff check deploy/compose/verify_index_build.py
git grep -n -I -E '<credential-signature-set>'
docker scout cves <tested-image> --only-severity critical,high
npx --yes @redocly/cli@2.13.0 lint \
  contracts/openapi/platform-api.yaml contracts/openapi/ai-worker-internal.v1.yaml
docker compose --profile knowledge \
  -f deploy/compose/compose.yaml \
  -f deploy/compose/compose.knowledge.yaml config --quiet
git diff --check
```

已记录的本地结果：

- Java 模块：133 个测试通过，无失败或跳过；`bootJar` 通过；
- 平台累计运行：执行 88 个测试，84 个通过；四个 P2.2d-1 测试因 PostgreSQL 初始化
  超过 Testcontainers 日志等待窗口而无法启动；
- 隔离预热 PostgreSQL 重跑：26 个测试通过，包括全部四个 P2.2d-1 测试、最终源码状态下
  全部 15 个 P2.2d-3 测试和七个模块架构测试；
- Console：5 个测试通过；严格类型检查、405 个 key 的语言覆盖和生产构建通过；
- AI worker：19 个测试通过；Ruff 通过；`pip-audit` 未发现已知第三方依赖漏洞；
- 验收驱动：Ruff、字节码编译和真实 Compose 断言通过；
- 契约：两个 OpenAPI 文件有效，保留两个既有 health/info 缺少 4xx response 警告；
- Compose 配置、凭据特征和空白检查通过；
- AI worker 镜像扫描完成，报告 Debian `perl 5.40.1-6` 存在
  `CVE-2026-12087`（CRITICAL）、`CVE-2026-48959`（HIGH）和
  `CVE-2026-48962`（HIGH）；Docker Scout 标记暂无修复版本；
- 平台镜像扫描因从 GHCR 下载 Trivy Java 数据库时出现 `unexpected EOF` 而未完成；
  这是未解决验证项，不算通过。

候选提交后的 GitHub CI 是权威的全新环境确认。

## 安全、兼容与回滚

- 证据不新增 secret、provider key、源内容、向量或原始 provider payload。
- fixture 凭据是既有非生产 bootstrap token，只用于隔离的 development security mode。
- 未改变 REST/OpenAPI/JSON Schema、Flyway migration、模块、deployable、依赖、locale key、
  Release 语义或生产默认值。
- Knowledge 仍只依赖已批准的 Identity、Capability Registry 和 Governance 公开 API。
- 基础 Compose 和 Knowledge overlay 都不会在未显式开启时运行 Build runner。

回滚优先使用配置：设置
`APVERO_KNOWLEDGE_INDEX_BUILD_RUNNER_ENABLED=false` 并重新创建平台服务。已有 Build 和
不可变 Version 保持持久化。之后可回退验证脚本、fixture 和 CI step；本切片没有 migration，
无需数据回滚。

## 限制与未解决风险

- 确定性 adapter 证明编排，不证明远端 provider 延迟、配额或故障行为。
- PostgreSQL 轮询与 O(entries) 验证仍是 P2.2d 已接受设计；声明支持更大 corpus 前必须重新测量。
- 共享 runner 的 CI 时间是证据，不是 SLA。
- 在正式创建 API 被接受前，Compose fixture 仍通过 SQL 准备 contract-only 的 Index 和 Route。
- Worker 基础镜像发现项需要维护者批准修复方案或明确安全处置；本切片不会静默改变
  已批准的基础镜像基线。
- 外部漏洞数据库可用后必须重试平台镜像扫描。
- 全新的 Linux Compose 运行仍由 GitHub CI 负责。
- Windows Docker 资源竞争导致单次全平台调用无法保持全绿；分区重跑已关闭全部失败测试，
  但 GitHub CI 仍必须证明一次全新的 Linux 累计运行。

## 阶段状态建议

记录候选提交、完成镜像扫描发现项处置且全部 GitHub checks 通过后，维护者可以验收
P2.2d-5，并将 P2.2d 标记为完成。
本文档不会修改 `architecture/delivery-stages.yaml`；在明确验收前，P2.2d 仍为进行中。
验收前仍禁止开始 P2.2e。
