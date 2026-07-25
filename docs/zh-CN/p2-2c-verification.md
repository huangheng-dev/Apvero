# P2.2c 受治理 Embedding 执行——验证证据

状态：已于 2026-07-25 完成并经维护者批准

目标：P2 / P2.2c

基线：[P2.2c 受治理 Embedding 执行](p2-2c-embedding-execution-baseline.md)

## 1. 已验收范围

P2.2c 已提供 P2.2d 所需的内部受治理 Embedding 批次接缝：

- 厂商无关的 Quote、Replay Policy 与执行契约；
- 具有冻结 Golden Vector 的确定性 Spring AI Embedding；
- 无隐藏重试、显式启用的 OpenAI-compatible Adapter；
- 持久化的 Governance Admission、Dispatch、Settlement 与 Reconciliation 状态；
- 确定性的 Knowledge 批次规划、原子 Entry 持久化与崩溃恢复决策。

它没有启用 Scheduled Build Runner、Build API、Index Publication、Retrieval Lab、前端页面或
生产 Knowledge 声明，也没有增加 Migration、新 Deployable、有状态依赖或第二套 AI 抽象。

## 2. 已验收检查点

| 检查点 | 持久实现 | Pull Request |
|---|---|---|
| P2.2c-1——Deterministic Adapter 与 Quote/Replay API | `0257574` | [#16](https://github.com/huangheng-dev/Apvero/pull/16) |
| P2.2c-2——真实 Governance Component Lifecycle | `5e7eeff` | [#17](https://github.com/huangheng-dev/Apvero/pull/17) |
| P2.2c-3——OpenAI-compatible Adapter 与 Protocol Stub | `3953ea6` | [#18](https://github.com/huangheng-dev/Apvero/pull/18) |
| P2.2c-4——Knowledge Batch Primitive 与 Crash Matrix | `f7c0ac8` | [#19](https://github.com/huangheng-dev/Apvero/pull/19) |

## 3. 验证门禁证据

| 门禁 | 证据 | 结果 |
|---|---|---|
| 架构与厂商中立 | `ModularArchitectureTest`、Repository Architecture Test，以及公开 Package 不含 Provider SDK Type | 通过 |
| Golden Vector | `DeterministicEmbeddingModelTest` 覆盖完整 256-float Vector、顺序、Locale 与 Timezone Variant | 通过 |
| 估算与限制 | `ConservativeUtf8EmbeddingInputUnitEstimatorTest`、`EmbeddingCostQuoteCalculatorTest`、`KnowledgeEmbeddingBatchExecutorTest` | 通过 |
| 真实 Adapter 协议 | `OpenAiCompatibleEmbeddingAdapterTest` 验证请求映射、有序输出映射、Timeout/Reject 归一化与零隐藏重试 | 通过 |
| 配置默认拒绝 | Capability 与 Platform Integration Test 覆盖 Route Shape、准确 Route Reference、Readiness、Endpoint、Secret 与 Profile | 通过 |
| Tenant 隔离 | Governance Component 与 Knowledge Persistence Integration Test 拒绝跨 Workspace Lookup、Lock、Chunk 与 Entry 访问 | 通过 |
| 调用前拒绝 | `ExecutionGovernanceCompatibilityTest` 与 Component Persistence Integration Test 证明在计费调用前拒绝 | 通过 |
| 崩溃矩阵 | `KnowledgeEmbeddingRecoveryDeciderTest` 覆盖全部 8 个已批准恢复 Row | 通过 |
| 幂等与冲突 | Governance Compatibility Test，以及 Knowledge Executor、Writer、PostgreSQL 并发集成测试覆盖相同重试、部分批次与冲突 | 通过 |
| P1 兼容性 | 累积构建中的完整 P1 Integration 与 Budget Suite 保持全绿 | 通过 |
| 构建、契约与部署 | Java、`bootJar`、OpenAPI、Compose 配置、两个 Container Build 与 Knowledge Compose Health/Restart Job 通过 | 通过 |
| 国际化 | 本文与英文对应文档章节一致；Console 校验报告每个必需 Locale 均有 405 个 Key | 通过 |

## 4. 可复现命令证据

2026-07-25 本地验证：

- `./gradlew test bootJar --no-daemon`：44 个 Suite、129 个 Test，无 Failure、Error 或 Skip；
- `pnpm typecheck`、`pnpm test`、`pnpm i18n:check`、`pnpm build`：通过；5 个 Test，
  每个必需 Locale 有 405 个 Leaf Key；
- `uv run pytest -q`：19 个通过；
- `uv run ruff check src tests benchmarks`：通过；
- `uv run pip-audit`：未发现已知第三方依赖漏洞；未发布到 PyPI 的本地
  `apvero-ai-worker` Package 被正确排除；
- 两种 Compose 配置模式：通过。

累积的 [PR #19 CI Run](https://github.com/huangheng-dev/Apvero/actions/runs/30140915816)
全部 7 个 Job 通过：`backend`、`console`、`worker`、`contracts`、`compose-config`、
`containers`、`knowledge-compose`。它验证了 Head
`f7c0ac8db40ab3600c5feaced7c2b9cf837e15ae` 对应的准确已验收 Tree
`47da0fed8fe1402535ced7be80bc2cd702f9474c`。

在本地 Windows 主机上，Redocly 2.13 完成两份 OpenAPI 描述验证后，在进程退出阶段触发
libuv 清理断言；同一个固定命令已在 Linux `contracts` CI Job 正常完成。这里将其如实记录为
Host Tool Exit Defect，不把它隐藏成契约失败，也不因此修改已批准契约或技术基线。

## 5. 安全、运维与回滚

- Provider Key 继续使用 Secret Reference；普通 API 不返回明文；
- Real Embedding 保持显式启用，确定性本地执行仍是默认验证路径；
- Dispatch Ambiguity 不会变成不安全的自动 Replay；
- 完整 Entry 批次原子提交，相同 Replay 不会创建第二份 Artifact；
- 稳定 `APVERO_*` Error 不泄露 Provider Body、Text、Vector、URL、Secret 或跨 Scope 信息；
- PostgreSQL 仍是唯一强制有状态依赖；
- 回滚部署上一版 Binary，停止并有界 Drain 新调用，保留 V9/V10 证据，不重写 Terminal
  Component 或 Immutable Entry。

## 6. 真实完成边界

P2.2c 完成的是内部执行原语，不是终端用户 Knowledge Workflow。P2.2d 仍需实现 Leased
Build Claim、持久 Step Transition、Retry/Cancel Command 与原子 Immutable Index
Publication；P2.2e 仍需实现 Exact Retrieval Lab。Knowledge 因此继续默认关闭，
P2.2 保持 `in-progress`。
