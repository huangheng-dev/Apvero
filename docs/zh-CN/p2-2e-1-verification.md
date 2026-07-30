# P2.2e-1 检索策略发布验证

状态：实现候选；维护者验收与 GitHub CI 尚未完成。

## 范围

P2.2e-1 实现 Exact Retrieval Lab 的第一个检查点：

```text
持久 Retention Policy 来源
  -> 发布不可变 Retrieval Policy
  -> 确定性策略摘要
  -> 幂等重放或类型化冲突
  -> 作用域限定列表 API
  -> 带摘要的管理审计
```

本检查点不实现查询 Embedding、向量排序、上下文投影、Retrieval Lab 查询端点、前端页面
或生产 RAG Run。

## 架构结果

- P2 保持 `in-progress`；P2.2e 现在为 `in-progress`。
- Knowledge 继续默认禁用。
- Knowledge 仍然只依赖 Identity、Capability Registry 和 Governance。
- PostgreSQL 仍是唯一必需的有状态依赖。
- 没有新增模块、数据表、迁移、部署单元、队列、框架、公开 REST Schema 或页面。
- 现有 `contract-only` 检索策略路由与 Schema 在无漂移情况下得到实现。

## 已实现行为

### 持久 Retention Policy 来源

`RetentionPolicyCatalog.getOrCreate` 在工作区没有数据行时，将现有有效默认值原子落地为
版本 `1`。并发首次调用收敛到一行。Knowledge 只使用该 Governance 公共边界，绝不
访问 Governance 数据表。

已有持久策略保持不变。工作区 Retention Policy 升级后，重放已经发布的 Retrieval
Policy 仍保留原始来源版本。

### 不可变策略发布

Knowledge 公共边界提供不可变发布和工作区作用域列表。发布严格执行 OpenAPI 范围：

- slug 与语义版本；
- `topK` 为 1 至 100；
- 上下文预算为 128 至 200,000；
- 分数为 0 至 1，并确定性规范到存储层六位小数精度；
- `KEEP` 或 `COLLAPSE_ADJACENT`。

Apvero 分配：

- `exact-cosine@1.0.0`；
- 公开估算器身份 `apvero-utf8-byte@1.0.0`；
- 当前持久 Retention Policy 版本；
- `NO_EVIDENCE`；
- 规范 SHA-256 摘要、创建者和 UTC 时间。

公开估算器身份会与已批准的内部实现身份 `apvero-utf8-byte-v1` 核验；冻结的估算器
没有被改名或改变。

### 幂等与并发

- slug/版本和调用方行为都相同时返回已有不可变策略；
- 后续 Retention Policy 更新不会破坏旧策略重放；
- 相同 slug/版本使用不同行为时返回
  `APVERO_KNOWLEDGE_RETRIEVAL_POLICY_VERSION_CONFLICT`；
- 另一身份产生相同摘要时返回
  `APVERO_KNOWLEDGE_RETRIEVAL_POLICY_DUPLICATE`；
- 并发相同的首次发布收敛为一个策略、一行 Retention Policy 和一条显式发布审计；
- 存储摘要不一致时以
  `APVERO_KNOWLEDGE_RETRIEVAL_POLICY_INTEGRITY_INVALID` 失败关闭。

仓储使用 `INSERT ... ON CONFLICT DO NOTHING`，随后执行作用域身份解析。它不会尝试在
已经中止的 PostgreSQL 事务内从唯一约束异常恢复。

### 审计与回滚

Governance 新增窄接口 `appendWithDigest`。它只接受
`sha256:[a-f0-9]{64}` 摘要，并且只写入这一个安全详情；不会向业务模块开放任意审计
详情 Map。

策略插入、默认 Retention Policy 落地和发布审计加入同一个事务。强制审计失败会同时
回滚两个新数据行。

## 安全与隔离证据

- GET 需要 read 或 admin，POST 需要 write 或 admin；
- Reader 凭据不能发布；
- 每个仓储条件都包含 tenant 和 workspace 作用域；
- 从其他工作区列举时看不到所有者策略；
- actor、源 IP 和 trace 都有长度边界；
- 审计详情只包含策略摘要；
- 没有引入查询、内容、提供商值、密钥、路径或 URL。

## 已执行验证

已通过：

- Governance 模块测试；
- Knowledge 模块测试；
- Retrieval Policy 控制器测试；
- P2.2e-1 PostgreSQL/Testcontainers 工作流测试；
- 八路并发首次发布测试；
- 审计失败事务回滚测试；
- Spring Modulith 与架构测试；
- platform-server 可启动 JAR 构建；
- JSON Schema 解析检查；
- Redocly OpenAPI 校验；
- 默认与 Knowledge Profile Compose 配置检查；
- `git diff --check`。

本地已成功启动与 backend CI 等价的命令并构建 JAR。完整 platform-server 套件执行时，
一个与本次变更无关的旧 P1 Testcontainers 测试类在高负载 Windows Docker 上超过了
默认 PostgreSQL 启动时间，因此本地全量运行被停止。新的 P2.2e-1 容器测试保留健康
检查，将启动上限设为三分钟，并已重复通过。该候选的干净环境全量套件以 GitHub CI
为最终依据。

Redocly 只报告两个既有警告：公共平台健康端点和内部 Worker 健康端点没有定义 4XX
响应。

## 回滚

- 切换回上一兼容二进制；
- 保留不可变策略、Retention Policy 和审计数据；
- 不需要向下迁移；
- 禁用 Knowledge 后，通过现有能力门禁拒绝策略访问；
- 不修改或删除任何已发布数据行。

## 退出声明

满足下述条件时，P2.2e-1 可提交维护者审查：

> 获授权工作区能够发布和列举带有持久 Retention Policy 来源、确定性摘要、稳定幂等/
> 冲突行为和摘要审计证据的不可变 Retrieval Policy；并发首次发布能够安全收敛，所有
> 失败都保持 tenant 作用域和事务性。

验收只完成 P2.2e-1。下一个检查点是 P2.2e-2 精确检索内核。
