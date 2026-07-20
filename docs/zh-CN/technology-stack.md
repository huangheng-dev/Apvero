# 完整技术栈

基线刻意保持收敛：一个技术只有在拥有明确职责时才引入。

| 范围 | 采用技术 | 职责 |
|---|---|---|
| Java 运行时 | Java 25 LTS | 平台服务语言和运行基线 |
| 应用框架 | Spring Boot 4.1 | 配置、HTTP、校验、健康检查、生产打包 |
| 模块边界 | Spring Modulith 2.1 | 模块发现、依赖校验、模块测试 |
| AI 抽象 | Spring AI 2.0 | Java 核心唯一 AI 抽象 |
| 弹性 | Resilience4j（规划中的 Provider 适配器阶段） | 外部 Provider 超时、熔断、有限重试 |
| 持久化 | jOOQ | 显式 SQL 与强制工作区过滤 |
| 数据迁移 | Flyway | 可审查、向前演进的数据库迁移 |
| 数据库 | PostgreSQL 18 | 事务与默认自托管部署的全部业务事实 |
| 向量检索 | pgvector | 不新增强制数据库的租户向量检索 |
| 可观测性 | Actuator 提供 Micrometer 基线；OpenTelemetry 待实现 | 当前健康/指标基础与未来分布式 Trace 归一化 |
| 架构测试 | Spring Modulith + ArchUnit | 依赖边界与禁止 Provider SDK 导入 |
| 控制台运行时 | Node.js 24 | 前端工具链 |
| 控制台 UI | React 19.2 | 组件模型 |
| 构建与类型 | Vite 8 + TypeScript 5.9 strict | 构建与编译期契约安全 |
| 服务端状态 | TanStack Query 5 | API 缓存、Pending/Error 与刷新 |
| 客户端校验 | Zod 4 | 后续表单契约校验 |
| 国际化 | i18next + react-i18next | 英文源语言、简体中文强制完整覆盖 |
| UI 样式 | 仓库原生 CSS | 控制复杂度，避免重型组件库锁定 |
| Worker 运行时 | Python 3.14 | 文档与评测生态 |
| Worker API | FastAPI + Pydantic 2 | 类型化、无状态任务契约 |
| Worker 环境 | uv | 可复现 Python 依赖与环境 |
| 前置代理 | Nginx | 静态控制台、API 代理、安全响应头 |
| 打包 | Docker + Compose v2 | 可复现的默认自托管部署 |
| CI | GitHub Actions | 后端、前端、Worker、契约与 Compose 配置检查 |
| 许可证 | Apache-2.0 | 含专利授权的宽松开源许可证 |

## 可选适配器，不是默认依赖

- Redis 或 Valkey：分布式限流、短期协调、缓存适配器。
- MinIO 或 S3：大型文档和留存制品。
- Kafka：只有 Outbox 与事件规模得到证明后才用于集成事件。
- ClickHouse：长期、高容量 Trace 分析。
- 外部向量数据库：只有规模或企业集成需求得到证明后引入。

## 明确禁止进入核心的技术

- LangChain4j、Spring AI Alibaba：批准 ADR 后只能进入兼容适配器。
- OpenAI、Anthropic 等厂商 SDK：只能是适配器实现细节。
- Kubernetes、Service Mesh、多套独立数据库：没有证据就不引入基线。
- 上传 JAR 并在控制平面进程内执行：违反隔离与权限模型。
