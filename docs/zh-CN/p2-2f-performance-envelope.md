# P2.2f 精确检索性能边界

状态：P2.2 验收候选的本地实测证据，不是可移植 SLA。

测量日期：2026-07-31

## 范围与诚实边界

可执行 Harness 为 `P22fExactRetrievalBenchmark`。它把空 PostgreSQL 数据库迁移到 V11，
创建符合生产形状的不可变 Knowledge Artifact，执行
`JooqKnowledgeIndexPersistenceRepository.EXACT_RETRIEVAL_SQL`，消费全部返回行，并保存
`EXPLAIN (ANALYZE, BUFFERS)`。

显式运行：

```powershell
$env:APVERO_P22F_BENCHMARK='true'
.\gradlew.bat :apps:platform-server:test --tests '*P22fExactRetrievalBenchmark'
```

该类不符合 Gradle 普通 `*Test` 命名模式，同时还要求环境开关，因此普通单元测试和 CI
不会意外执行本地性能测试。

Harness 测量数据库/JDBC 检索路径，包括建立连接和消费结果，但不包括认证、授权、
Governance 准入、Query Embedding Provider、HTTP 序列化与网络延迟。英文、简体中文和
混合 Query Profile 会选择可复现 Vector Input；这验证多语言路径等价性，不证明语义相关性。

每次 Shared-buffer-cold Query 前使用 `pg_buffercache_evict_all()`。这是 PostgreSQL 18
用于驱逐未固定 Shared Buffer 的开发测试机制，不会清除宿主机操作系统页缓存。该扩展只在
一次性 Benchmark 数据库中创建，不属于生产 Migration 或依赖。

## 参考机器

| 项目 | 数值 |
|---|---|
| Host CPU | Intel Core i9-9900KF，8 Core / 16 Logical Processor |
| Host Memory | 34,290,765,824 Byte |
| Docker Desktop Allocation | 16 Logical CPU，16,732,790,784 Byte |
| Host OS | Windows 10 10.0 |
| JVM | OpenJDK 25.0.3 |
| Database | PostgreSQL 18.4 |
| Vector Extension | pgvector 0.8.5 |
| 所有 Benchmark Fixture 的 Entry + Chunk Storage | 143,302,656 Byte |

这些数值只标识一次参考运行，不是最低硬件要求。

## 精确 Retrieval 结果

每个场景执行 5 次 Warmup 和 30 次测量。测量请求轮换英文、简体中文、混合 Profile，
并覆盖低 Threshold、No-evidence/High-threshold 和 `topK=100`。

| 场景 | Dimension | Entry | Shared-buffer-cold | p50 | p95 | p99 |
|---|---:|---:|---:|---:|---:|---:|
| Small | 256 | 1,000 | 42.709 ms | 47.256 ms | 109.455 ms | 143.274 ms |
| Medium | 256 | 5,000 | 113.704 ms | 73.840 ms | 115.945 ms | 119.505 ms |
| Retrieval 测量上限 | 256 | 10,000 | 134.826 ms | 117.931 ms | 168.562 ms | 169.334 ms |
| Medium | 384 | 5,000 | 100.885 ms | 88.235 ms | 145.967 ms | 147.369 ms |
| Medium | 768 | 5,000 | 139.569 ms | 152.910 ms | 282.444 ms | 297.067 ms |
| Medium | 1,536 | 5,000 | 241.053 ms | 142.761 ms | 238.059 ms | 241.701 ms |

Query Plan 保留精确完整召回，并在 Cosine Ordering 前使用带 Scope 的 B-tree 访问。
10,000×256 Plan 的数据库执行时间为 94.780 ms；代表性 5,000 Entry Plan 在 384、768、
1,536 Dimension 下分别为 42.876、89.262、134.088 ms。系统没有 ANN Index，也没有先做
全局 Vector Scan 再用 Java 过滤。

同一机器紧邻的上一次有效运行明显更快：10,000×256 p95 为 62.020 ms，5,000×768 p95
为 78.626 ms，5,000×1,536 p95 为 75.446 ms。表格有意记录较慢的重复结果，而不是选择
更好看的数字。Docker Desktop 与 Host Load 使它只能作为参考边界，不能作为确定性延迟承诺。

## 并发读写压力

8 个客户端对 10,000×256 的 Acceptance-limit 不可变 Version 完成 80 次读取，同时另一个
未发布 Build 插入 1,000 条 Entry。

| 指标 | 结果 |
|---|---:|
| Read p50 | 149.708 ms |
| Read p95 | 178.894 ms |
| Read p99 | 196.346 ms |
| Failed Read | 0 |
| Direct Fixture Entry Write | 0.554 s |

Direct Fixture Write 只证明并发 Table/Trigger 压力。由于它有意绕过 Embedding Execution
和 Runner，不能称为受治理 Build Throughput。

## 受治理 Build 证据与支持边界

已验收的 P2.2d Runner 证据继续作为完整 Build Throughput 的事实来源：

| Entry | Queue 至 Embedding/Indexing | Validation | Atomic Publication |
|---:|---:|---:|---:|
| 1 | 3.133 s | 0.809 s | 0.495 s |
| 100 | 3.586 s | 0.549 s | 0.192 s |
| 1,000 | 72.248 s | 2.324 s | 0.717 s |

因此 P2.2 自托管支持声明必须比单独 Retrieval 测量更保守：

- 支持的完整确定性 Build-to-Retrieval Workflow：每个不可变 Index Version 最多
  **1,000 条 Entry，256 Dimension**；
- 支持的 Runner Concurrency Baseline：**4**，已验收 Scheduler 证据也覆盖 1 和 8；
- Retrieval-only 实测证据：256 Dimension 下 10,000 Entry，以及 384/768/1,536
  Dimension 下 5,000 Entry；
- 参考机器上的 Retrieval 测量目标：声明矩阵内数据库/JDBC p95 小于 300 ms，包括
  8 Reader + Write Pressure；
- 在更大规模重新执行受治理 Runner、Recovery、Publication、Retrieval 矩阵前，不声明
  完整 Workflow 支持超过 1,000 Entry。

这是有意设置的安全边界，不是数据库最大值。

## 拒绝与重测规则

P2.2 不会为了改善数字而增加 ANN、Hybrid Search 或其他 Vector Store。未来扩大边界必须
用同样的 Isolation、History、Governance、Restart、Publication、Retrieval Gate 重新实测。
只要出现请求失败、Query Plan 丢失 Workspace/Version Scope、声明矩阵 p95 超过 300 ms、
Publication 变成 Partial，或者没有记录软硬件版本，本次结果就无效。

本地执行后的完整 Plan 位于
`apps/platform-server/build/reports/p22f/exact-retrieval-benchmark.md`。它包含临时 Fixture
Identity 和大量 Vector Literal，因此不提交仓库。
