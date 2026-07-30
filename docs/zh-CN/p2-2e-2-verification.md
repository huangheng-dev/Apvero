# P2.2e-2 精确检索内核验证

状态：本地验证完成的实施检查点；里程碑推送与 GitHub CI 延后到完整 P2.2 验证候选。

## 范围

P2.2e-2 实现由数据库负责的确定性排序边界：

```text
作用域限定的 READY 索引版本
  -> 验证查询向量
  -> 一条精确 pgvector 余弦语句
  -> 阈值与精确 topK
  -> 稳定的距离/Chunk 排序
  -> 有界的不可变血缘候选
```

本检查点不调用查询 Embedding、不预留或结算成本、不应用重叠/上下文/保留策略投影、
不开放 Retrieval Lab 端点、不启用前端页面，也不创建生产 Run。这些仍属于 P2.2e-3
至 P2.2e-5。

## 架构结果

- P2 阶段与 P2.2e 切片继续保持 `in-progress`。
- Knowledge 仍是唯一归属模块。
- 实现没有增加 Identity、Capability Registry、Governance 这三个已批准边界之外的依赖。
- PostgreSQL 18 与 pgvector 仍是唯一有状态依赖。
- 没有修改迁移、表、索引、可部署单元、队列、框架、REST Schema 或页面。
- 公共 Java 结果与命中记录实现已批准的 `contract-only` OpenAPI 形状，不暴露 Provider、
  jOOQ、pgvector、数据库、Secret、路径或 URL 类型。

## 公共边界

Knowledge 现在声明：

- `KnowledgeRetrieval`；
- 包含 `MATCHES` 与 `NO_EVIDENCE` 的 `KnowledgeRetrievalResult`；
- 包含排名、规范化分数、不可变血缘、摘要、有界内容和有界锚点的
  `KnowledgeRetrievalHit`。

这些记录会复制命中集合，并拒绝状态/列表矛盾、负延迟、非法摘要、分数或排名越界、
超长内容/标题/Heading 以及畸形锚点。P2.2e-3 和 P2.2e-4 将在该边界后实现编排与
策略控制的投影。

## 精确排序语句

`JooqKnowledgeIndexPersistenceRepository` 执行一条包含以下约束的语句：

- Tenant ID、Workspace ID 与精确 Index Version ID 谓词；
- READY Version 与 READY Build 谓词；
- Build、Entry、Chunk、Source 的复合作用域连接；
- 查询向量与 Entry 维度均等于不可变 Version 维度；
- 在限制数量前应用余弦阈值；
- 余弦距离升序，再按不可变 Chunk UUID 升序；
- SQL `LIMIT` 精确等于策略 `topK`；
- 数据库生成连续排名；
- 对公开的 `1 - cosine_distance` 分数执行 `[0,1]` 钳制。

Java 不会全局读取候选、在排序后做 Workspace 过滤，也不会重新排序浮点分数。不存在
隐藏的过采样或回填。

查询故意不包含当前 Source 状态谓词。Source tombstone 会阻止未来 Build 选取，但不会
静默改写或使已经发布的 READY Index Version 失效。

## 输入与投影安全

排序前，内部内核会：

- 在调用者 Tenant/Workspace 作用域内加载精确 READY Version；
- 对不存在、跨 Workspace 和跨 Tenant ID 返回相同的作用域 not-found；
- 强制 `topK` 为 `1..100`，有限分数阈值为 `0..1`；
- 要求查询向量维度与发布维度完全一致；
- 拒绝 null、非有限值和零范数向量。

SQL 只投影公共血缘 ID、内容摘要、有界 Chunk 文本、有界 Source 名称与类型，以及
页码/Heading/段落/行锚点。它不投影向量、向量摘要、快照字节、捕获元数据、存储位置、
原始 URL、Route 数据或 Provider 数据。

## 确定性与隔离证据

PostgreSQL/Testcontainers 测试证明：

- 即使按反向顺序写入，相同余弦距离仍由 Chunk UUID 决定顺序；
- 阈值过滤发生在精确 SQL 限制之前；
- `topK` 没有被替换为应用层限制；
- `1.0` 分数、中间余弦分数与包含式 `0.0` 边界按预期规范化；
- 同 Tenant 的另一个 Workspace 与另一个 Tenant 都无法解析或排序该 Version；
- 发布后 tombstone Source 不会改变历史检索；
- 分析后的代表性计划包含有界 `Limit` 与作用域限定的 Index 访问路径。

计划夹具刻意保持较小，不代表规模承诺。P2.2f 负责测量语料与并发支持范围。

## 已执行验证

本地通过：

- Knowledge 模块编译与完整模块测试；
- 公共检索边界不变量测试；
- 精确内核校验与 SQL 形状测试；
- 真实 PostgreSQL 18/pgvector 排序、隔离、历史和计划测试；
- Platform 测试编译。

第一次集成执行因测试夹具使用非十六进制伪摘要而按预期失败。夹具已修正，没有削弱任何
生产约束。随后完整的目标集成测试通过。

依据仓库推送策略，里程碑级架构、OpenAPI、Compose、安全和完整 CI 验证延后到组装完成的
P2.2 候选。

## 回滚

- 回退 P2.2e-2 实现提交或运行上一个兼容二进制；
- 保留全部不可变 Index Version 与 Entry；
- 不需要迁移或数据回退；
- Knowledge 继续默认关闭；
- 没有端点或产品页面变为 Live。

## 退出声明

P2.2e-2 在以下条件满足时完成本地检查点：

> 一个精确 READY Index Version 可以通过一条 Tenant/Workspace 作用域限定的 pgvector
> 余弦语句排序其不可变 Entry，具备确定的距离/Chunk 顺序、精确阈值与 topK 行为、
> 有界血缘投影、跨作用域失败关闭行为，并保留 tombstone 历史。

下一检查点是 P2.2e-3 受治理查询执行。P2.2e 与 P2.2 继续保持 `in-progress`。
