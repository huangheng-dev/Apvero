# P2.2e-4 检索策略应用与披露验证

状态：本地验证完成的实施检查点；里程碑推送与 GitHub CI 继续延后到完整 P2.2 验证候选。

## 范围

P2.2e-4 在受治理排序之后闭合公共精确检索结果工作流：

```text
受治理的精确 SQL 排序
  -> 校验不可变 Retrieval Policy 身份与摘要
  -> 在 SQL topK 内折叠配置的重叠
  -> 读取当前 Governance Retention Policy
  -> 应用固定估算器上下文预算
  -> 只投影有界安全证据
  -> 返回 MATCHES 或成功的 NO_EVIDENCE
  -> 暴露要求 write 权限的 REST 操作
```

Knowledge 产品页面仍不启用。本检查点不增加答案生成、Application Draft 绑定、生产 Run
证据、掩码引擎、混合检索、隐藏过采样或新持久化模型。

## 架构与契约结果

- P2、P2.2 与 P2.2e 继续保持 `in-progress`。
- Knowledge 归属策略应用，只依赖获准的 Capability Registry 公共估算器与 Governance
  保留策略目录。
- PostgreSQL 仍是唯一必需的有状态依赖。
- 没有修改迁移、表、可部署单元、队列、框架、模块边界或 Schema 形状。
- `POST /api/v1/knowledge-retrieval-tests` 现在实现已有 OpenAPI 3.1 契约，因此只移除了
  该操作的 `contract-only` 标记。
- 该端点会产生计费用量，因此遵循平台 POST 规则：需要 `write` 或 `admin`；`read`
  密钥会在执行前被拒绝。

## 确定性策略行为

只有当存储策略满足以下条件时，结果服务才继续执行，否则失败关闭：

- `exact-cosine@1.0.0`；
- `apvero-utf8-byte@1.0.0`，实现为 `apvero-utf8-byte-v1`；
- 公共 `topK`、上下文预算与分数范围；
- 持久的发布时保留策略版本；
- `KEEP` 或 `COLLAPSE_ADJACENT`；
- `NO_EVIDENCE`；
- Tenant/Workspace 作用域匹配且规范摘要有效。

`KEEP` 保持 SQL 顺序。`COLLAPSE_ADJACENT` 把每个候选与同一不可变 Document 中已接受
的 Hit 比较。存储的半开字符范围只有实际相交才算重叠；首尾相接不算重叠。更早的 SQL
排名获胜，被丢弃的 Hit 永远不会从原始 SQL `topK` 之外补位。

上下文预算使用固定 UTF-8 估算器，对确实允许披露的正文计算。正文只能完整加入，不能
静默截断；超大 Hit 会跳过，但更晚且更小的 Hit 仍可进入。仅元数据 Hit 消耗零单位，
返回 Rank 最后连续重排。

## 当前保留策略与安全披露

系统在受治理排序后读取当前有效 Retention Policy：

- `retainPayloads=false` 时抑制正文；
- `maskSensitiveFields=true` 时同样抑制正文，因为 Apvero 尚无获准的共享非结构化文本
  掩码器；
- 抑制发生在预算计算与响应投影之前；
- Knowledge 不会自行发明正则表达式 DLP 词汇。

响应只包含契约规定的血缘 ID、分数、摘要、可选的有界正文、Source 标题/类型，以及有界
页码、标题、段落与行号锚点。它不能暴露存储字符偏移、原始 URL、路径、对象键、Secret、
向量、Provider 消息或 Provider Request Identity。

如果阈值、重叠、预算或披露规则使结果为空，系统成功返回带空列表的类型化
`NO_EVIDENCE`，绝不把它解释成可生成无依据答案。

## 验证证据

单元与边界测试证明：

- 超预算英文正文会被跳过，之后恰好达到预算边界的简体中文/ASCII Hit 可以进入；
- 同一 Document 的相交范围折叠，相接范围保留，另一 Document 的相同范围也保留；
- 禁止保留正文与要求掩码时均抑制正文，并且不消耗正文预算；
- 空排序结果返回类型化 `NO_EVIDENCE`；
- 被篡改的 Policy 摘要在读取当前保留策略前失败；
- 公共 Hit 契约接受 20,000 个 Unicode Code Point，拒绝 20,001 个；
- 最终 Rank 连续，且只投影安全定位信息。

真实 PostgreSQL/pgvector 集成还证明：

- REST 请求贯穿认证、Workspace 作用域、受治理 Embedding、精确排序、当前持久
  Retention Policy 与 JSON 投影；
- `read` API Key 收到 `APVERO_ACCESS_DENIED`；
- 不完整请求收到稳定的 `APVERO_KNOWLEDGE_IDENTIFIER_INVALID`；
- 管理员得到一个排序 Hit，其正文被当前掩码标志抑制；
- 响应中不存在不安全内部字段。

## 已执行验证

本地通过：

- 聚焦的 Knowledge 策略/披露测试；
- Controller 映射与 OpenAPI Controller 一致性测试；
- 真实 PostgreSQL 18、pgvector、Capability Registry、Governance、安全与 REST 集成；
- Knowledge 与 Platform 测试套件的 Java 编译。

完整模块、架构、OpenAPI Lint、打包、Compose 与安全套件将在 P2.2e-5 切片候选以及完整
P2.2 推送边界再次执行。

## 回滚

- 回退 P2.2e-4 本地实施提交，或使用上一个兼容二进制；
- OpenAPI Schema 保持兼容；恢复标记即可让端点重新成为 `contract-only`；
- 不需要数据或迁移回滚；
- Knowledge 继续默认关闭，产品页面继续保持非 Live。

## 退出声明

P2.2e-4 在以下条件满足时完成本地检查点：

> 一次授权的精确检索以确定方式应用不可变策略，遵守当前保留决策，只消耗固定上下文
> 预算，只披露安全有界证据，并通过已提交 REST 契约返回连续排序的匹配项或类型化
> NO_EVIDENCE。

下一检查点是 P2.2e-5 切片验证。P2.2e 与 P2.2 继续保持 `in-progress`。
