# P2.3a 契约校正与 Application 不透明绑定验证候选

状态：维护者已于 2026-07-31 验收。P2.3 继续保持 `in-progress`；P2.3b 不可变
Manifest 1.1 Release 固定现已启动。

## 范围

P2.3a 只实现以下有界工作流：

```text
列出精确不可变 Knowledge 版本
  -> 选择不透明的 Index 与 Retrieval Policy Version ID
  -> 使用乐观并发整体替换有序 Application Draft 绑定集
  -> 保留可变选择，供 P2.3b 在 Release 时执行权威校验
```

本阶段不发布 Manifest 1.1，不在 Draft 编辑时把绑定认证为 READY，不固定
ReleaseBundle，不检索生产证据，不编排 RAG，也不输出引用。

## 权威与架构

- 阶段：P2 / P2.3 / P2.3a。
- 归属：Application 拥有可变 Draft 选择；Knowledge 拥有不可变 Index 与 Retrieval
  Policy Version。
- Application 不依赖 Knowledge，只把 Knowledge 所有的 UUID 作为不透明值保存。
- Knowledge 继续只使用获准的 Identity、Capability Registry 与 Governance 依赖。
- PostgreSQL 仍是唯一必需的有状态依赖。
- 本次工作受 ADR-0006 覆盖，不改变不变量、可部署单元、框架、队列、发布语义、安全
  基线或模块边界。
- Manifest 1.1 继续保持 `contract-only`。在 P2.3b 实现权威解析与不可变固定之前，
  Release 创建仍只接受 Manifest 1.0。

## 契约校正

- Model Route 与 Prompt 引用使用当前实现的正整数版本格式。
- Knowledge Index 与 Retrieval Policy 引用使用当前实现的语义版本格式。
- 其他未来制品接受精确整数或语义版本；所有格式都拒绝 `latest`。
- ReleaseBundle 读取可投影 Manifest 1.0 或 1.1，创建请求仍只接受 Manifest 1.0。
- Application Draft 绑定读取返回 Application 身份、当前乐观版本、有序不透明 ID，
  不伪造 Knowledge 规范引用。
- 替换请求必须提供 `expectedApplicationVersion`，最多 16 项，并拒绝重复组合。

## 持久化、隔离与安全

V12 迁移只新增 Application 所有的 `application_draft_knowledge_binding` 表。复合外键把
每一行约束到同一 Application、Tenant 与 Workspace。它有意不对 Knowledge 表建立跨模块
外键。

数据库触发器拒绝非 RAG Application 的绑定，也拒绝把已有绑定的 RAG Application 改成
其他运行模式。服务层同时校验模式、空身份、重复项、数量限制和乐观并发。过期替换会整体
回滚，不会先删除当前绑定。跨 Workspace 读取使用相同的作用域 Application Not Found
行为。

后端使用稳定错误码供客户端本地化：

- `APVERO_APPLICATION_KNOWLEDGE_BINDING_INVALID`；
- `APVERO_APPLICATION_KNOWLEDGE_BINDING_MODE_INVALID`；
- `APVERO_APPLICATION_DRAFT_VERSION_CONFLICT`；
- `APVERO_KNOWLEDGE_INDEX_VERSION_NOT_FOUND`；
- `APVERO_KNOWLEDGE_RETRIEVAL_POLICY_VERSION_NOT_FOUND`。

端点继续受现有 Knowledge Feature Flag、平台认证与 Workspace 授权链保护。

## 验证

候选证明：

- 精确 Workspace 作用域 Knowledge Index Version 列表与 Retrieval Policy Version 查询；
- Application 绑定顺序保持与防御性不可变投影；
- 真实 V11 到 V12 Flyway 升级不改写已有 Application 行；
- 只有一个同作用域 Application 外键，不存在 Knowledge 外键；
- 随机不透明 Knowledge ID 可在不跨越模块边界的前提下保存；
- 过期乐观并发替换不会部分修改绑定集；
- 数据库双向执行 RAG-only 绑定约束；
- 同 Tenant 跨 Workspace 访问失败关闭；
- OpenAPI 操作与 Controller 方法保持一致；
- Manifest 1.1 格式规则拒绝 `latest` 与字段不兼容的版本格式。

本地已执行：

- Application 与 Knowledge 模块单元测试；
- P2.3a Controller、OpenAPI 校正和真实 PostgreSQL/Testcontainers 集成测试；
- 仓库完整测试以及 Spring Modulith/ArchUnit 验证；
- Platform Server 可启动 JAR；
- OpenAPI 3.1 与 JSON Schema 校验；
- 英文与简体中文语言校验；
- Compose 配置与 Source Diff 检查。

## 迁移与回滚

前向迁移为 V12。回滚时先关闭 Knowledge 绑定写入，再使用上一兼容二进制。新表与触发器
函数可以休眠保留；若要删除，必须先确认不再需要已保留的 Draft 选择，再通过独立前向清理
迁移执行。不提供破坏性 Down Migration。

## 已知限制

1. Draft 绑定只是选择，不证明引用的 Knowledge 制品存在或处于 READY。
2. P2.3b 必须在已认证 Workspace 内解析每个不透明 ID，并把规范精确引用固定进不可变
   Manifest 1.1 ReleaseBundle。
3. 生产检索证据、Grounded 编排、结构化答案与引用仍属于 P2.3c 至 P2.3f。
4. 产品页面在 P2.4 产品与运维门禁前保持非 Live。

## 退出声明

维护者确认以下事实后，P2.3a 可以验收：

> 获得授权的 Workspace 可以在 RAG Application Draft 上选择一个有序、有界的不透明
> Knowledge 精确版本 ID 集，并具备失败关闭的作用域与乐观并发保护；同时 Application
> 仍独立于 Knowledge，生产发布语义保持不变。

维护者已于 2026-07-31 验收本证据。P2.3a 已完成，P2.3 继续保持 `in-progress`，
P2.3b 现已启动。
