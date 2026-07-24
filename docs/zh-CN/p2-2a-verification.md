# P2.2a Capability 与 Governance 外壳验证

状态：实现候选；等待维护者验收

目标：P2 / P2.2a

决策权威：ADR-0006 与维护者已批准的 P2.2 实施计划

## 已交付边界

P2.2a 建立受治理 Embedding 执行的前置条件，但不会声称 Indexing 或 Retrieval 已经 Live：

- P2.2 已记录为 `in-progress`。
- V9 把所有现有 Model Route 回填为 `CHAT`。
- V9 增加可判别、不可变的 EMBEDDING Route Profile，约束 Dimension、Aggregate Input Unit、
  Batch Size、Normalization、Model Capability、Status 与 Shape。
- 厂商无关 Java API 暴露准确 Route Snapshot、保持顺序且绑定 Digest 的 Input、已验证的有限
  非零 Vector、Usage Quality、Cost、安全 Request Identity 与 Latency。
- `apvero-utf8-byte-v1` 提供确定性、保守的 Input-unit Estimator。
- Governance 暴露类型化 Execution Subject 与 Billable Component，同时保留现有 P1 单 CHAT
  API。
- 在已批准 Persistence 交付前，Knowledge Component Reservation、Dispatch 与 Settlement
  继续默认拒绝。
- Spring Modulith 与 ArchUnit 保护模块 Internal，并阻止 Provider Abstraction 进入
  Embedding 公开 API。

匹配的设计记录是 `docs/zh-CN/p2-2a-embedding-decision.md`；可执行的双语与对抗性语料位于
`modules/capability-registry/src/test/resources/p2-2a-embedding-corpus.json`。

## Migration 与兼容证据

V9 Testcontainers 检查证明：

- Clean Install 能到达 V9；
- 真实 V8 Schema 只执行一次 Migration 即升级到 V9；
- 被引用但未显式声明 CHAT 的 Legacy Model 会保留原有 Capability 并补充 CHAT；
- 现有 CHAT Route Identity、Output-token 配置与 Null Embedding Field 保持不变；
- 合法 EMBEDDING Profile 可以存储；
- Dimension `16001` 会被拒绝；
- EMBEDDING Route 不能引用只声明 CHAT 的 Model；
- 已发布 Route 的 Update 与 Delete 会被拒绝。

P2.1 的 V7→V8 测试现在明确固定 Target V8，后续增加 Migration 时不会让历史里程碑验证漂移。

旧 Compatible Binary 继续通过 `route_capability` 的 `CHAT` Default 插入 CHAT Route，并忽略
新增 Nullable Field。Forward Rollback 部署旧 Binary，同时保留 V9 与数据；不提供破坏性
Down Migration。

## 验证

| 检查 | 结果 |
|---|---|
| `gradlew test :apps:platform-server:bootJar --no-daemon` | 通过；94 个测试，0 Failure、0 Error、0 Skip |
| Spring Modulith 与 ArchUnit | 通过 |
| Capability 与 Governance Unit Contract | 通过 |
| Clean Flyway Migration 与 V8→V9 Upgrade | 通过 |
| 现有 P1 Governance 与 P2.1 Ingestion 回归 | 通过 |
| 两份契约的 Redocly OpenAPI 3.1 Lint | Valid；只有两条既有 Operation-4xx Warning |
| Default 与 Knowledge Profile Compose 配置 | 通过 |
| 中英文决策文档结构 | 七个匹配 Section |
| Corpus Fixture 解析与冻结 Expected Unit | 十个 Case 通过 |
| `git diff --check` | 通过 |

## 真实限制

本切片不会：

- 把 Contract-only EMBEDDING REST Create Operation 标记为 Live；
- 使用 Spring AI 或任何 Provider 实现 `EmbeddingCapability`；
- 持久化 Governance Component；
- 创建 Knowledge Index/Build/Entry/Version Table；
- 执行付费 Traffic、构建 Vector、发布 Index 或运行 Retrieval Lab；
- 修改 Console 或把 Knowledge 页面标记为 Live。

这些边界是有意保留的。下一步 P2.2b 增加有 Scope 的不可变 Knowledge Persistence 与
Governance Component Ledger；随后 P2.2c 才实现受治理 Embedding Execution。
