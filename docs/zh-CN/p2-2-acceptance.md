# P2.2 不可变索引与检索实验室验收

状态：维护者已于 2026-07-31 验收。

目标：P2 里程碑 P2.2。P2 仍为 `in-progress`，下一里程碑为 P2.3。

## 已验收成果

P2.2 在其已测边界内完整满足以下结论：

> 在一个已授权工作空间内，Apvero 能把确定性、不可变 Chunk 转换为受治理且维度安全的
> 不可变 Knowledge Index Version，在持久化校验与恢复后原子发布，并通过 PostgreSQL
> 精确余弦检索检查有界血缘、当前留存披露和类型化无证据结果；全过程不发生跨工作空间
> 泄漏、生产引用可变、费用重复结算或无依据的规模声明。

P2.2 不绑定 Application 或 ReleaseBundle，不生成带引用答案，不上线 Knowledge 产品
页面，不增加近似或混合检索，也不把确定性本地 Embedding 适配器说成语义质量证明。
Application 到带引用 Run 的闭环属于 P2.3。

## 证据映射

| 门禁 | 已验收证据 |
|---|---|
| 架构 | Spring Modulith 与 ArchUnit 保持 Knowledge 边界及其获准的 Identity、Capability Registry 与 Governance 公共依赖 |
| 迁移 | V9–V11 全新安装与升级覆盖证明受作用域约束的 Embedding、不可变 Index 持久化、耐久 Build 状态、约束、触发器及前向缓解 |
| 不可变性 | Route、Policy、Build、Entry 与 READY Index Version 均版本化并受摘要保护，对修改和部分发布失败关闭 |
| Embedding | 报价、准入、派发、成功/失败结算、歧义与对账路径均持久化，防止盲目重放和费用重复 |
| 构建 | 覆盖持久化租约、有界并发、确定性重放、校验、重试、耗尽、取消、重启对账和原子发布 |
| 检索 | 精确余弦 SQL 在排序前完成作用域过滤，采用确定性同分排序与上下文预算，返回有界 `MATCHES` 或类型化 `NO_EVIDENCE` |
| 隔离 | Tenant/Workspace、Version、Build、Entry、Policy 与完整 REST 检索测试均失败关闭，包括真实外部标识 |
| 历史可复现性 | READY Version 在后续来源墓碑化后仍检索其不可变历史；新 Build 排除已墓碑化来源 |
| 治理与留存 | 费用只预留和结算一次；输出和遥测前应用当前留存披露；敏感数据不会被不安全导出 |
| 契约与安全 | 已验证 OpenAPI 3.1 一致性、稳定错误、write/admin 授权、密钥排除和 Provider 类型隔离 |
| 运维 | 保持健康状态、低基数指标、安全日志、持久化恢复、隔离 Compose 清理和默认禁用发布 |
| 国际化 | 英文与简体中文计划、验证、性能边界、候选证据及本验收记录一一对应 |
| 性能 | 可执行本地证据把完整工作流保守上限定为 256 维 1,000 个 Entry，并证明参考机器精确检索矩阵 p95 低于 300 ms |
| 端到端 | 干净环境 Compose 证明五种来源、重同步、墓碑拒绝、重启重试、自动 Build 发布及持久化 `IN_FLIGHT` 恢复 |

详细证据：

- [`p2-2a-verification.md`](p2-2a-verification.md)
- [`p2-2b-verification.md`](p2-2b-verification.md)
- [`p2-2c-verification.md`](p2-2c-verification.md)
- [`p2-2d-5-verification.md`](p2-2d-5-verification.md)
- [`p2-2e-5-verification.md`](p2-2e-5-verification.md)
- [`p2-2f-performance-envelope.md`](p2-2f-performance-envelope.md)
- [`p2-2-acceptance-candidate.md`](p2-2-acceptance-candidate.md)

## Git 与 CI 证据

- 候选：[PR #37](https://github.com/huangheng-dev/Apvero/pull/37)。
- 累计实现提交：
  `67127305662b51ddf3ac669ebf28c16c161d504f`。
- 带 CI 证据的已验收候选头：
  `1e3ba62f22ce7f55f3d80aa1ae96c674ecf1d1b8`。
- 已验证源码 Tree：
  `79cb316645ac4879088dc46f4c38951039f9c06d`。
- 初始候选 CI：
  [Run 30564010885](https://github.com/huangheng-dev/Apvero/actions/runs/30564010885)。
- 已验收候选头 CI：
  [Run 30564605515](https://github.com/huangheng-dev/Apvero/actions/runs/30564605515)。

两次干净环境执行均通过 Backend、Console、Worker（含依赖审计）、Contracts、Compose
配置、容器构建/安全以及 `knowledge-compose`。

## 验收后状态

- `architecture/delivery-stages.yaml` 把 P2.2a–P2.2f 与 P2.2 记录为 `completed`。
- P2 保持 `in-progress`；P2.3 仍为 `planned`。
- Knowledge 模块保持 `in-progress`，产品页面仍未上线。
- `APVERO_KNOWLEDGE_ENABLED=false` 仍是默认值。
- PostgreSQL 与 pgvector 仍是唯一强制有状态依赖。
- 本次转换不会上线 Application 绑定、ReleaseBundle Knowledge 固定或带引用生产 Run。
- 验收更新没有改变不变量、模块边界、公共 Schema 形状、发布语义、安全策略、国际化
  策略、有状态依赖或技术基线。

## 回滚与后续

运维回滚仍然失败关闭：禁用并排空 Knowledge Runner，恢复上一兼容版本，并保留追加写入
的不可变记录用于诊断和前向恢复。不得修改 READY Version、删除不可变表或手工清除活动
租约。

P2.3 接下来必须关闭以下完整工作流：

`Application draft -> immutable knowledge binding -> ReleaseBundle pin -> governed retrieval -> cited Run evidence`

现有 `pnpm/action-setup@v4` Node 运行时弃用提示属于独立维护事项。它没有削弱或导致
P2.2 验收证据失败。
