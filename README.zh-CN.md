# Apvero

Apvero 是一个开源、自托管的 AI 应用工程平台，用于构建、评测、发布、运行并持续改进 AI 应用。

> Application Platform for Versioned, Evaluated, Reliable Operations。

[English](README.md)

## 为什么是 Apvero

Apvero 以应用为中心，而不是以 Agent 为中心。聊天、RAG、结构化生成、工具、Agent 和工作流，都是同一个版本化 `AI Application` 的运行模式：

```text
设计 -> 测试 -> 评测 -> 发布 -> 运行 -> 观测 -> 反馈 -> 改进
```

完整架构定义目标能力；首个工程基线实现一条安全的 Application / Release / Run 主干：

- 不可变发布包固定所有运行依赖版本；
- 厂商无关 Runtime SPI 和明确标识的本地确定性 Provider；
- 每次完成的运行记录类型化 Trace、用量、成本和延迟；
- 通过厂商无关的 Capability Registry 管理版本化的 Provider、模型、路由与 Prompt；
- 使用环境变量型 Secret Reference、哈希 API Credential 与生产环境默认拒绝策略；
- 通过架构元数据和测试约束模块化单体边界；
- 英文为源语言，简体中文为一级支持语言；
- 默认自托管部署只强制依赖 PostgreSQL 与 pgvector。

## 当前状态

当前仓库已经落地架构优先的工程基础，以及第一条可运行纵向链路：

```text
配置路由与 Prompt -> 绑定应用 -> 预览 -> 生成不可变发布包 -> 运行 -> 查看 Trace 与成本
```

本地确定性 Provider 是明确标识的开发 Provider，不会伪装成外部大模型调用。OpenAI-compatible Provider 可通过 Spring AI 适配器显式启用；密钥只从环境变量解析，不写入数据库。

控制台已经展示经过确认的 23 个一级产品页面，并为 Agent、工作流、MCP 服务、记忆服务、反馈、预算、身份管理和扩展市场保留二级视图。这样既压缩了导航，也没有删除规划能力。每个页面都会明确标识数据边界：

- **真实数据**：Application、Release、Run、模型、Prompt、Playground、用量与成本、API Key、Secret 使用当前服务端 API。
- **真实 + 演示**：概览和系统健康把确认状态与明确标识的规划数据并排展示。
- **演示数据**：规划模块使用丰富的本地数据，只更新原型状态，绝不会伪装成服务端成功。

页面清单和交互要求以 [`product/navigation.yaml`](product/navigation.yaml) 与 [`product/pages.yaml`](product/pages.yaml) 为准；命名和合并决策记录在 [`ADR-0004`](docs/adr/0004-international-product-navigation.md)。

## 技术基线

- Java 25、Spring Boot 4.1、Spring AI 2.0、Spring Modulith 2.1
- React 19.2、TypeScript、Vite 8、TanStack Query、i18next
- Python 3.14、FastAPI、Pydantic、uv
- PostgreSQL 18 + pgvector；Redis、MinIO、Kafka、ClickHouse 均为可选适配器
- Spring Boot Actuator 提供 Micrometer 基线；OpenTelemetry 集成处于规划状态
- Docker Compose、GitHub Actions

## 快速启动

前置条件：Docker 29+ 与 Compose v2。

```bash
cp .env.example .env
docker compose -f deploy/compose/compose.yaml up --build
```

启动后访问：

- 控制台：<http://localhost:3000>
- 平台 API：<http://localhost:8080/api/v1/platform>
- 健康检查：<http://localhost:8080/actuator/health>
- AI Worker：<http://localhost:8090/health>

详细说明参见[中文快速开始](docs/zh-CN/quick-start.md)。

完整设计参见[中文完整架构](docs/zh-CN/architecture.md)与[完整技术栈](docs/zh-CN/technology-stack.md)。所有目标能力都会明确标注为“已实现、基线、只有契约或规划”，不会把设计图冒充成成品。

具体实施顺序和阶段退出条件参见[分阶段交付路线图](docs/zh-CN/roadmap.md)，当前阶段同时记录在机器可读的 [`architecture/delivery-stages.yaml`](architecture/delivery-stages.yaml) 中。

## 架构权威顺序

AI 编程和人工贡献在修改代码前必须依次阅读：

1. [AGENTS.md](AGENTS.md)
2. [architecture/invariants.yaml](architecture/invariants.yaml)
3. [architecture/delivery-stages.yaml](architecture/delivery-stages.yaml)
4. [`product/navigation.yaml`](product/navigation.yaml) 与 [`product/pages.yaml`](product/pages.yaml)
5. [architecture/modules.yaml](architecture/modules.yaml)
6. [architecture/dependency-rules.yaml](architecture/dependency-rules.yaml)
7. `docs/adr/` 中已经批准的决策
8. `contracts/` 中的公共契约

如果修改与受保护规则冲突，必须停止实现，等待维护者批准 ADR。

## 许可证与品牌

源代码采用 Apache License 2.0。软件许可证不代表 Apvero 名称和标识可以被不受限制地使用，详见 [NOTICE](NOTICE)。
