# P2.1a 模块与安全外壳验证

状态：本地验证完成的实施检查点
日期：2026-07-22
阶段：P2 / 里程碑 P2.1a

## 已交付边界

- 增加物理 `modules/knowledge` Gradle 模块和 Platform Server 依赖。
- 声明 Spring Modulith 模块 ID，以及获批的 `identity`、`capability-registry`、`governance` 依赖。
- 增加 Provider-neutral Core、Knowledge 允许依赖和 Knowledge Internal 私有性的 ArchUnit 检查。
- 增加默认值 `APVERO_KNOWLEDGE_ENABLED=false`、公开的默认拒绝 Gate、稳定错误 `APVERO_KNOWLEDGE_DISABLED` 和感知 Worker 状态的 Actuator Health Contributor。
- 所有 Knowledge REST 操作继续保持 contract-only；没有增加 P2 Migration、Repository、业务端点、Live 产品页、Index 或 RAG 可用声明。
- 删除 Worker 宿主机端口和通用 Nginx 代理，把它放到 `knowledge` Compose Profile 的 internal-only 网络，并应用非 Root、只读、Capability 和资源限制。
- 建立版本化 Parser 候选语料、确定性摘要基线、可执行 Benchmark、中英文依赖决策和 CI Lint 覆盖。
- 把 FastAPI、Starlette、pytest 和测试客户端升级到已修复且兼容的版本，并增加固定版本的 `pip-audit` CI 门禁。

## 验证证据

| 领域 | 命令/证据 | 结果 |
|---|---|---|
| Java | `gradle test --no-daemon` | 通过 |
| Knowledge 边界 | Modulith、ArchUnit、Knowledge 单元/部署测试 | 通过 |
| 打包 | `gradle :apps:platform-server:bootJar --no-daemon` | 通过 |
| Worker | `uv run pytest -q` | 9 项通过 |
| Python Lint | `uv run ruff check src tests benchmarks` | 通过 |
| 依赖锁 | `uv lock --check` | 通过 |
| 依赖安全 | `uv run pip-audit` | 无已知漏洞；本地未发布包按预期跳过 |
| Parser 烟雾基准 | 五类媒体各执行 25 次 | 输出摘要稳定 |
| 契约 | JSON 解析与两个 OpenAPI 的 Redocly Lint | 有效；保留两个既有 Health/Info 4xx Warning |
| Compose | 默认与 `knowledge` Profile 配置验证 | 通过 |
| 暴露面 | 渲染后的 Compose 与 Java 部署策略测试 | 默认不启动 Worker；无宿主机端口；Console 无依赖/代理 |
| 本地运行迁移 | 停止旧 `apvero-ai-worker-1`，验证 8090 关闭 | 通过；Console、Platform Server、PostgreSQL 继续健康 |
| 容器镜像 | Worker 与 Platform Server 的 Compose Build | 通过 |
| Worker 容器 | 独立只读/无网络/无端口运行验证 | UID 10002，删除全部 Capability，Health 通过 |
| Platform 容器 | 独立只读/无端口并连接 PostgreSQL 的运行验证 | UID 10001，Health 通过，Knowledge 显示 `enabled=false` |

直接访问 Docker Hub 认证端点发生超时。缺失的 Docker Hub 官方基础镜像通过 Google Artifact Registry 官方文档说明的同步缓存 `mirror.gcr.io` 补入本地，并在本机恢复为原始镜像名；仓库 Dockerfile 和镜像引用没有变化。随后两个原始 Dockerfile 均构建成功。CI 也具备明确的双镜像构建 Job，用于在远端检查点独立复验标准构建。

## 安全解释

Knowledge 禁用时不会请求 Worker，Platform 保持健康并明确显示 `enabled=false`。Knowledge 启用后，Worker 不可用会让 Knowledge Health Contributor 进入 `DOWN`；业务命令也必须在执行前调用公开 Availability Gate。ADR-0006 允许的外部 Worker Health 只通过 Platform Actuator 聚合观察，绝不提供直接 Worker 浏览器路由。

仍在运行旧 `8090` 映射容器的已有环境必须停止或重建该精确服务。本机旧 Worker 已停止但未删除；可以通过显式启动已经重建的 `knowledge` Profile 恢复。

## 尚未交付

P2.1a 不包含 P2.1b Migration 与 Scoped Repository、P2.1c Source Command、P2.1d Web Capture、P2.1e 生产 Parser 端点或 P2.1f 持久 Runner。Knowledge 继续保持非 Live，P2 继续为 `in-progress`。

## 回滚

回退功能分支并运行上一个 Platform Binary。当前没有数据库 Migration 或 P2.1 持久化状态。如果旧 Worker 会发布宿主机端口，应继续保持停止；回滚不能重新引入不必要的公开处理面。
