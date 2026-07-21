# Apvero 完整架构

## 产品边界

Apvero 是开源、自托管的 **AI 应用工程平台**。系统根对象是 `AI Application`；聊天、RAG、结构化输出、工具、Agent 和 Workflow 都只是运行模式。这样可以避免项目最后退化成 Agent 编排器，或者堆满互不连贯的后台 CRUD 页面。

唯一主闭环是：

```text
设计 -> 测试 -> 评测 -> 发布 -> 运行 -> 观测 -> 反馈 -> 改进
  ^                                                   |
  +---------------------------------------------------+
```

按照依赖排序的实施计划和阶段退出门禁定义在[分阶段交付路线图](roadmap.md)中。架构描述完整目标，路线图负责约束每项能力何时可以升级为真实功能。

## 完整能力树

```text
Apvero
├─ 体验层
│  ├─ Web Console（英文源语言，简体中文必须同步完整）
│  ├─ 公共 REST API / OpenAPI 3.1
│  ├─ SDK 与 CLI【规划】
│  └─ Playground【基线】；检索检查器、评测检查器【规划】
│
├─ 身份与租户【基线】
│  ├─ Tenant -> Workspace -> Environment
│  ├─ 人员身份：OIDC、OAuth2、LDAP/SCIM 适配器
│  ├─ 机器身份：只显示一次的 API Key【基线】；工作负载身份【规划】
│  ├─ API 粗粒度角色【基线】；资源/动作级策略决策【规划】
│  └─ SQL、向量过滤、对象路径、事件、指标、日志全链路租户隔离
│
├─ AI Application 生命周期（控制平面）
│  ├─ Application Center【已实现基线】
│  │  ├─ 运行模式、工作区归属【已实现】
│  │  ├─ 基础生命周期元数据【基线】
│  │  └─ 版本化草稿、类型化输入/输出契约【规划】
│  ├─ Build【基线】
│  │  ├─ Prompt：模板、声明变量、不可变版本【基线】；Diff、回滚【规划】
│  │  ├─ Model Route：版本化模型绑定与超时【基线】；权重、降级【规划】
│  │  ├─ Knowledge Binding：数据源/索引/检索策略版本
│  │  ├─ Capability Binding：Tool、MCP、Memory、Evaluator、Guardrail
│  │  └─ Policy 与运行参数版本
│  ├─ Test【预览基线】
│  │  ├─ 确定性预览、显式启用的真实模型预览【基线】
│  │  ├─ Tool/MCP 调用检查器
│  │  ├─ 检索 Chunk、Score 与引用检查器
│  │  └─ 将测试输入沉淀为数据集 Case
│  ├─ Evaluate【规划】
│  │  ├─ 版本化 Dataset、Case、Expected Behavior
│  │  ├─ 确定性评测、模型裁判、人工审核
│  │  ├─ 回归对比、A/B 实验、阈值门禁
│  │  └─ Release 引用不可变评测报告
│  ├─ Release【已实现基线】
│  │  ├─ 不可变 ReleaseBundle
│  │  ├─ 规范化 JSON + SHA-256 制品身份
│  │  ├─ Manifest 契约可以固定模型路由、Prompt、Schema、知识索引、Capability、
│  │  │  Policy、Memory、Evaluation 和运行参数版本
│  │  └─ 环境晋级与指针回滚【规划】
│  └─ Application API Identity
│
├─ 运行数据平面
│  ├─ AI Gateway【规划】
│  │  ├─ 认证、授权、限流、配额、预算
│  │  ├─ 输入校验、敏感信息脱敏、安全护栏
│  │  ├─ 受策略控制的精确缓存与语义缓存
│  │  ├─ 模型路由、熔断、降级、有限重试
│  │  └─ 流式输出、用量归一化、幂等键
│  ├─ Runtime Orchestrator【执行基线已实现】
│  │  ├─ Chat、RAG、Structured、Tool、Agentic、Workflow 模式【只有契约】
│  │  ├─ Spring AI 是 Java 核心唯一 AI 抽象
│  │  └─ 厂商无关 RuntimeProvider SPI
│  ├─ Capability Execution【规划】
│  │  ├─ JSON Schema 类型化输入输出
│  │  ├─ 方法级权限，默认拒绝
│  │  ├─ 超时、配额、幂等、审计事件
│  │  └─ 隔离运行 Tool、MCP 和 Plugin
│  └─ Run Ledger【已实现基线】
│     ├─ Application 与不可变 Release 身份
│     ├─ 输入/输出、Provider、用量、成本、延迟
│     └─ Trace 身份与状态
│
├─ 知识数据平面【规划；已实现无状态 Worker 工具基线】
│  ├─ 确定性切块与精确匹配评测工具【已实现】
│  ├─ 数据源：文件、网页、Git、数据库和企业适配器【规划】
│  ├─ Pipeline：解析 -> OCR -> 标准化 -> 切块 -> 增强 -> Embedding -> Index
│  ├─ 不可变索引版本与 pgvector 租户过滤检索
│  ├─ 增量同步、删除传播、数据血缘
│  └─ 检索评测、引用和事实依据检查
│
├─ 观测、治理与持续改进【当前运行账本之外均为规划】
│  ├─ OpenTelemetry Trace：Gateway -> Runtime -> Model/Tool/Retrieval【规划】
│  ├─ Token、成本、延迟、缓存、质量、安全策略指标
│  ├─ Tenant/Workspace/Application/Release/Capability 多级预算
│  ├─ 追加式审计事件、可配置留存和脱敏
│  ├─ 用户反馈与审核后的生产 Trace 筛选
│  └─ Feedback -> Dataset 版本 -> 候选版本评测 -> Release Gate
│
└─ 平台与扩展层
   ├─ 默认自托管部署唯一强制有状态依赖：PostgreSQL 18 + pgvector
   ├─ Redis、MinIO、Kafka、ClickHouse 都是可选适配器
   ├─ Transactional Outbox 与幂等后台任务【规划】
   ├─ Secret 只保存引用；明文不返回、不落库
   ├─ Plugin 清单、兼容性、权限、摘要、签名策略
   └─ Plugin 进程外运行；控制平面禁止任意 JAR 执行
```

## 源码模块与依赖方向

| 模块 | 数据与业务所有权 | 允许依赖 | 状态 |
|---|---|---|---|
| `application` | 应用根对象、运行模式、基础生命周期元数据 | 无 | 基线 |
| `release` | 不可变发布制品与摘要 | `application`、`capability-registry` | 基线 |
| `runtime` | 运行账本、Trace 身份、Provider SPI | `application`、`release`、`capability-registry` | 基线 |
| `identity` | 租户、工作区、主体、API 粗粒度角色、哈希凭证 | 无 | 基线 |
| `capability-registry` | Provider、模型、路由、Prompt；其他能力元数据后续实现 | `identity`、`governance` | 基线 |
| `knowledge` | 数据源、摄取任务、Chunk、索引版本 | `capability-registry` | Worker 基线 |
| `evaluation` | 数据集、评测运行、实验、门禁 | `application`、`release`、`runtime` | 规划 |
| `governance` | 环境变量型 Secret Reference；预算、审计、留存后续实现 | `identity` | 基线 |
| `extension` | Plugin 兼容性、权限、签名 | `capability-registry` | 只有契约 |

模块不能查询其他模块拥有的数据表；只能调用公开接口或消费版本化事件。系统首先采用模块化单体。只有 ADR 用真实证据证明某个模块需要独立扩缩容、隔离、运行时或故障边界，才允许拆成独立部署单元。

## 当前部署拓扑

```text
Browser
  -> Console / Nginx :3000
       -> Platform Server :8080
            -> PostgreSQL 18 + pgvector :5432
       -> AI Worker :8090
```

- **Platform Server：** Java 25、Spring Boot 4.1、Spring Modulith 2.1、Spring AI 2.0、jOOQ、Flyway。
- **Console：** Node.js 24、React 19.2、严格 TypeScript、Vite 8、TanStack Query、i18next。
- **AI Worker：** Python 3.14、FastAPI、Pydantic。它是无状态工作单元，不拥有核心业务真相。
- **默认数据层：** PostgreSQL 18 + pgvector；任何可选基础设施都不能成为隐藏依赖。

## 发布与运行真相

每次生产运行必须引用一个不可变 Release ID。Manifest 会先规范化，再计算 SHA-256 并入库。数据库复合外键保证 Tenant、Workspace、Application 归属一致；触发器拒绝 Release 的更新与删除。回滚是把环境指针切到旧 Release，不是修改旧 Release。

当前 `ai_run` 是 Release 身份、Trace 身份、输入输出、Token 用量、成本、延迟、Provider 适配器身份与状态的运行事实账本。可配置留存与脱敏属于规划中的治理能力，不是当前行为。

## 当前可执行基线的安全边界

开发模式会提供本地管理员身份，只能用于回环地址。Enforced 模式会认证 Bootstrap 管理员 Token 或只显示一次、哈希存储的 API Credential，并拒绝跨工作区使用。资源级细粒度策略、OIDC/LDAP/SCIM 与工作负载身份仍在规划中。真实 Provider 调用和私有模型端点分别采用显式开关；Secret 值只从环境变量解析，不返回、不落库。

## 技术约束

Spring AI 是 Java 核心唯一 AI 抽象。LangChain4j、Spring AI Alibaba 和厂商 SDK 只能在批准 ADR 后进入隔离兼容适配器；任何厂商类型、名称或特有参数都不能泄漏到 Application、Release、Runtime 领域契约。
