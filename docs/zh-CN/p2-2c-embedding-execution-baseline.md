# P2.2c 受治理 Embedding 执行——实施基线

状态：维护者已批准实施基线；P2.2c-1 实施候选

目标：P2 / P2.2c

权威依据：ADR-0006、维护者批准的 P2.2 计划，以及已实现的 P2.2a/P2.2b 契约

推理强度：高

## 1. 目标与边界

P2.2c 交付一个可复用、受治理的 Embedding Batch Primitive：

```text
准确 Workspace 与不可变 Embedding Route
  -> 确定性有序 Input
  -> 厂商无关 Cost Quote 与 Replay Policy
  -> 幂等 Governance Reservation/Component
  -> 持久化 DISPATCHED Transition
  -> 一次 Spring AI EmbeddingModel Call
  -> 严格 Output Validation
  -> 原子持久化 Knowledge Entry Batch
  -> 幂等结算 Component 与 Parent Reservation
  -> 安全重放、只恢复结算，或明确要求对账
```

它是 P2.2d 后续使用的执行接缝。P2.2c 不增加 Scheduled Build Runner、Build REST、Build
状态推进、Publication、Retrieval、Application Binding、Frontend 或 Live 产品声明。

只有同一个持久化 Batch Identity 不会造成不安全的重复 Provider Dispatch、重复 Entry、
重复 Governance Component 或重复 Apvero Ledger Charge，P2.2c 才算完成。

## 2. 变更声明

| 项目 | 决策 |
|---|---|
| 阶段 | P2 / P2.2，`in-progress` |
| 模块 | `capability-registry`、`governance`、`knowledge` |
| 依赖 | 只使用现有已批准依赖图 |
| REST / JSON Schema | 不变 |
| Java 模块 API | 增量增加厂商无关 Quote/Replay 契约 |
| Migration | 无；V9 与 V10 已足够 |
| Stateful Dependency / Deployable | 无 |
| AI 抽象 | 只使用 Spring AI 2.0 |
| Python Worker | 不变且不参与 |
| 暴露 | 内部能力；Knowledge 与页面继续 Disabled/Non-live |
| 回滚 | 上一版 Compatible Binary；保留 V9/V10 Row |

本切片不改变不变量、依赖规则、REST 契约、Release 语义、安全策略或技术基线。增量 Java API
用于实现 ADR-0006 已批准的行为。

## 3. 编码前必要勘误

P2.2a 已冻结有序执行输入/输出，但尚未暴露获批恢复协议需要的两个事实。

### 3.1 调用前 Cost Quote

Governance 在付费 Dispatch 前需要 Estimated Unit、Cost 与 Currency。Capability Registry
增加厂商无关 Quote，Knowledge 不读取 Model Table：

```text
quote(workspaceId, routeId, estimatedInputUnits)
  -> 准确 Route Snapshot
  -> Estimated Cost Micros
  -> Currency
  -> Replay Policy
```

Cost 使用不可变 Route 对应 Model Input Price，并防溢出地向上取整：

```text
ceil(estimatedInputUnits * inputCostMicrosPerMillion / 1_000_000)
```

P2.2c 保持已实现的 USD-only Baseline，不虚构多币种支持。

### 3.2 Replay Policy

Adapter Replay Safety 必须显式且厂商无关：

```text
SAFE_REPLAY
RECONCILIATION_REQUIRED
```

默认是 `RECONCILIATION_REQUIRED`。P2.2c 只有
`apvero-deterministic-embedding@1.0.0` 属于 `SAFE_REPLAY`。通用
OpenAI-compatible Adapter 不声称 Provider 幂等，不盲目重试结果不确定的付费调用。

### 3.3 Provider Request Identity 的时间

Provider Request ID 通常在 Response 后才产生。因此 Governance 必须：

1. I/O 前以空 Provider Identity 标记 `DISPATCHED`；
2. 校验 Response 后幂等补录 Identity；
3. Knowledge Entry 持久化后才 Settlement；
4. 绝不把首次 Dispatch Transition 延迟到 Call 之后。

V10 已允许 `DISPATCHED -> DISPATCHED`，无需 Migration。

## 4. 事实与事务边界

| 关注点 | Owner |
|---|---|
| Route/Profile/Readiness/Adapter/Quote | Capability Registry |
| Secret Resolution | Governance Secret API，仅在 Capability Registry 内消费 |
| Reservation/Component/Settlement | Governance |
| Batch Membership 与 Entry | Knowledge |
| Build Lease/Lifecycle | P2.2d |
| External Response | 校验并持久化前不可信 |

外部 I/O 期间不保持数据库事务：

```text
TX-A: Quote + Admit + RESERVED Component
TX-B: RESERVED -> DISPATCHED
Spring AI/Provider Call，无数据库 Transaction
TX-C: 校验并原子插入完整 Entry Batch
TX-D: 补录 Provider Identity，结算 Component/Parent
```

TX-C 已提交而 TX-D 失败时只恢复结算。TX-B 已提交而 TX-C 未提交时，根据准确固定不可变
Route 解析出的 Replay Policy 决定是否重放。

## 5. 确定性 Spring AI Adapter

Identity：`apvero-deterministic-embedding@1.0.0`

- Dimension `256`；
- L2 Normalization；
- 进程内、无 Credential、`SAFE_REPLAY`；
- `apvero-utf8-byte-v1` Unit，标记 `ESTIMATED`；
- Cost 为 0 USD；
- 只证明开发/CI 编排，不声称语义质量。

冻结算法：

1. 保留准确 Java String，不 Trim、不 Unicode Normalize、不改变换行；
2. 编码 UTF-8；
3. 对 Block Ordinal `0..7`，以 SHA-256 计算
   `apvero-deterministic-embedding@1.0.0` 的 UTF-8 Byte、NUL、Big-endian 32-bit Block
   Ordinal、NUL 与准确 Input Byte；
4. 拼接八个 Digest，得到 256 个 Signed-byte-derived Component；
5. 把每个 Signed Byte `b` 映射为非零 Double `(b + 0.5d) / 128d`；
6. 按 Ordinal 使用 `StrictMath` 累加 Norm，拒绝非有限/零 Norm，归一化后一次转换为
   IEEE-754 float32；
7. 保持 Request Order，不读取 Locale、Timezone、Random、Filesystem 或 Network。

Golden Test 冻结全部 256 个 Float Bit、Vector SHA-256、L2 Tolerance 与 Order，覆盖 English、
简体中文、混合语言、Combining Character、Emoji、CRLF 与 Long Input。行为变化必须发布新
Adapter Version。

Adapter 实现 Spring AI `EmbeddingModel`，Apvero 调用 `call(EmbeddingRequest)`，不调用
可能触发真实模型请求的默认 `dimensions()`。

## 6. OpenAI-compatible Adapter

Adapter 位于 Capability Registry Internal `adapters.springai`，从不可变 Route 手工构造
Spring AI `OpenAiEmbeddingModel`：

- 准确 Base URL、Secret Reference、Model Key、Dimension、Timeout；
- `maxRetries(0)`，防止绕过 Governance 的隐藏重试；
- 有序 Batch Input 与 Response Index Validation；
- 不使用全局自动配置 Provider Bean；
- Application Config 不保存明文 Key。

Secret 在构造前即时解析；Owned `ResolvedSecret` Character Buffer 在构造后关闭并清零。
Spring AI 当前要求 String API Key，因此最后一个不可变短生命周期副本无法原地清零。该副本
只属于一次 Call，不缓存、不返回、不记录、不打 Tag、不持久化。

本地 HTTP Stub 验证 Path、Authorization、Model、Dimension、Input Order、Timeout、
Response Mapping、Usage Extraction 与安全 Error Normalization。CI 不需要付费 Credential。
自定义 Provider Idempotency 配置必须等独立、可测试契约形成后再设计。

## 7. Capability Registry 行为

真实 `EmbeddingCapability` Service：

1. 以 Tenant/Workspace 条件解析 Route、Model、Provider；
2. 要求准确 `name@N`、`EMBEDDING`、`PUBLISHED`、Model/Provider Enabled、Model Capability
   匹配、不可变 Profile 有效；
3. Real Provider 必须有可用 Secret，Deterministic Local 不需要 Secret；
4. Governance Admission 前提供 Quote；
5. 在 Internal 选择 Adapter，不泄漏 Provider Type；
6. Unsupported/Disabled Execution 返回稳定 Code；
7. 在 Transaction 外调用 Adapter；
8. Facade 再次校验 Route/Execution Identity、Count、Order、Item/Digest、Dimension、
   Finite Value 与 Non-zero Norm；
9. 真实保留 `ACTUAL`、`ESTIMATED`、`UNAVAILABLE` Usage Quality；
10. 有 Actual Unit 时按 Actual Cost，否则按 Versioned Estimate。

Readiness 不执行付费 Probe；Real Execution 继续显式 Opt-in。

## 8. Governance 行为

`ExecutionGovernance.admit(ExecutionReservationRequest)` 对获批组合变为真实能力，同时保持
P1 单 CHAT API 兼容。

Admission 原子地：

1. 校验 Workspace Scope 并获取已有 Advisory Lock；
2. 评估 Workspace 与准确 Route Limit；
3. 非 Application Subject 跳过 Application Policy，不进行 Null Comparison；
4. 查找相同 Subject/Component Idempotency Identity，或插入一个 Reservation 及其 Components；
5. 相同重复请求返回同一个 Admission；
6. 冲突重复请求返回稳定 Code。

Transition：

- 相同 `RESERVED -> DISPATCHED` 重复调用为 No-op；
- Provider Identity Enrichment 不得替换另一个非空 Identity；
- Terminal Settlement 使用 Compare-and-set，相同重复为 No-op；
- 冲突 Settlement 默认拒绝；
- Parent Actual Cost 是 Terminal Components 的防溢出合计；
- 全部 Component Success 时 Parent 才 Success，任一失败则 Parent Failure；
- Reconciliation 保持显式，不能伪装为零成本成功/失败。

Stale Reconciler 可以失败/释放从未 Dispatch 的 Reservation，但不得把 Dispatched Work 以 0
结算；不安全的 Stale Dispatch 进入 `RECONCILIATION_REQUIRED`。

## 9. Knowledge Batch Primitive

增加 Internal `KnowledgeEmbeddingBatchExecutor`；它不是 Scheduler 或 Public Catalog。输入为
完整 `WorkspaceScope`、准确 Build、确定性 Batch Ordinal 与准确有序 Chunk Identity。

Dispatch 前：

1. 重载 Build Route ID/Reference/Profile；
2. 要求 Build 已处于持久化 `EMBEDDING` Step，但不推进其状态；
3. 只读取相同 Scope、属于 Build Revision 且缺少 Entry 的 Chunk；
4. 按 Source-set Ordinal、Document Ordinal、Chunk Ordinal、Chunk UUID 排序；
5. 重算 Content Digest；
6. 使用 `apvero-utf8-byte-v1` Estimate；
7. 拒绝 Oversized Chunk，并在 Aggregate Unit/Item Limit 前停止；
8. 从 Build ID、Batch Ordinal、Route 与规范有序 `(Chunk ID, Content Digest)` Manifest 派生
   Idempotency Identity。

校验后，一个 Knowledge Transaction 插入完整 Batch，包括确定性 Entry/Batch Ordinal、准确
Lineage、Normalized-input Digest、float32 Vector Digest 与 Route Reference。完全相同的
Entry 可幂等接受；Vector/Digest/Ordinal/Lineage/Route 任一不同都属于 Conflict。

P2.2c Test 用持久 Fixture 直接调用该 Primitive。P2.2d 才负责 Claim Build、选择 Next Batch
与推进 Build State。

## 10. Crash 与 Retry Matrix

| 持久点 | 恢复 |
|---|---|
| Reservation 前 | 重算并正常 Admit |
| Component `RESERVED` | 复用 Admission，只 Dispatch 一次 |
| `DISPATCHED`、无 Entry、`SAFE_REPLAY` | 以同一 Identity 重放 |
| `DISPATCHED`、无 Entry、Unsafe | 要求对账，不进行第二次调用 |
| 完整相同 Entry、Component 未终态 | 只 Settlement |
| Partial Entry Batch | Integrity Failure，不绕过补齐 |
| Component Success 且 Entry 相同 | 幂等返回 Completed |
| Terminal Component 但 Entry 不同/缺失 | Ledger/Artifact Inconsistency |

这不是 “Exactly Once”，而是 At-least-once Local Execution、幂等持久化 Effect，以及对不安全
External Dispatch 的明确 Ambiguity。

## 11. 实施位置

### Capability Registry

- 只在 `modules/capability-registry` 增加 Spring AI Model/OpenAI Dependency；
- 在公开模块 Package 增加厂商无关 Quote/Replay Record；
- 在 `.internal` 实现 Resolution/Quote/Facade Validation；
- 在 `.internal.adapters.springai` 实现两个 Adapter；
- 增加 Golden Vector 与 HTTP Stub Test；
- 公开 API 禁止 Spring AI/Provider Import。

### Governance

- 在 `DefaultGovernanceCatalog` 启用 Component Overload；
- 扩展 Scoped Persistence：Find-by-idempotency、CAS Dispatch、Identity Enrichment、
  Settlement、Reconciliation；
- 保持 P1 Behavior 并增加 Regression Test；
- Knowledge Orchestration 不得移入 Governance。

### Knowledge

- 增加 Scoped Deterministic Batch Selection 与 Atomic Entry-batch Persistence；
- 增加 Internal Executor 与 Normalized Error Mapping；
- 不增加 Controller、Runner、Build Catalog 或 Publication。

### Platform

- 增加 Testcontainers Crash-boundary 与双 Workspace Suite；
- 复用显式 Real-provider Enablement；
- Compose Dependency 不变；
- 实施时生成匹配的 English/简体中文 Verification Evidence。

## 12. 安全、错误与遥测

稳定错误覆盖 Route/Profile/Readiness、Adapter/Secret/Endpoint、Input/Digest/Limit、Provider
Timeout/Reject、Output Mapping/Vector Validation、Budget/Rate Denial、Idempotency/Settlement
Conflict、Ambiguity、Partial Entry 与 Scope/Lineage Mismatch。

Error 只暴露稳定 `APVERO_*` Code 与安全 Metadata；不暴露 Provider Body、Source Text、
Vector、Secret、Base URL 或跨 Workspace 存在性。

Metric 覆盖 Call/Latency/Outcome、Item/Unit、Dimension、Algorithm、Usage Quality、
Admission/Dispatch/Settlement、Replay、Settlement-only Recovery、Reconciliation。只允许有界
低基数 Tag。Tenant/Workspace/Build/Route/Chunk ID、Request ID、Content、URL 禁止作为 Label。
Spring AI Observation 只作补充，Content Export 继续关闭。

## 13. Verification Gate

1. Modulith/ArchUnit 保持依赖，并禁止公开 Package 出现 Provider Type。
2. 完整 Golden Vector 在 Process、Locale、Timezone Variant 下通过。
3. Estimator 与 Deterministic Batching Limit Test 通过。
4. HTTP Stub 证明 Real Adapter Protocol、零隐藏重试与 Normalized Failure。
5. Readiness、Exact Version、Provider、Secret、Profile Failure 默认拒绝。
6. 双 Workspace Test 阻止 Quote/Reservation/Component/Chunk/Entry 跨 Scope。
7. Admission Denial 必须先于 Adapter Invocation。
8. Crash Test 覆盖第 10 节全部 Row。
9. 相同 Duplicate Operation 幂等；冲突 Operation 失败。
10. P1 CHAT Execution 与 Budget 不变。
11. Java Test、`bootJar`、OpenAPI、Compose、Container 与安全/依赖扫描通过。
12. English 与简体中文 Evidence 匹配。

本切片不修改前端、不启用页面，因此不要求 TypeScript/Playwright。

## 14. 实施检查点

1. **P2.2c-1——Deterministic Adapter 与 Quote/Replay API**
2. **P2.2c-2——真实 Governance Component Lifecycle**
3. **P2.2c-3——OpenAI-compatible Adapter 与 Protocol Stub**
4. **P2.2c-4——Knowledge Batch Primitive 与 Crash Matrix**
5. **P2.2c-5——完整验证与双语 Evidence**

每个检查点是完整、已验证的 Commit Candidate，不强制拆为单独 PR；均不启用 P2.2d。

## 15. 回滚与自我批判

- Knowledge 与 Real Provider 默认关闭；
- P2.2c 没有 Migration；
- 回滚部署上一版 Binary，并保留全部 V9/V10 Evidence；
- 回滚前停止新 Call 并有界 Drain；
- 不删除或重写 Dispatched/Terminal Component。

明确限制：

1. Deterministic Vector 证明编排，不证明语义。
2. UTF-8 Unit 保守但不是 Provider Token。
3. 通用 OpenAI Compatibility 不能诚实保证幂等。
4. Entry-before-settlement 会产生可恢复的 Settlement-only Window；反向顺序会产生更糟的
   “已计费但无 Artifact”窗口。
5. 直接调用 Primitive 的测试不是最终用户 Build Workflow；后者属于 P2.2d。
6. Spring AI 位于 Capability Registry 会增加 Adapter Weight，但保持 Knowledge 厂商无关。
7. USD-only Cost 是明确限制。
8. Provider Usage 不可用时，用 Versioned Estimate 与 `ESTIMATED` 结算，不能发明 Actual。
9. Spring AI 的 String API-key 边界无法保证最终临时副本原地清除；P2.2c 必须缩短其生命周期
   和可达范围，不能虚假声称完全 Zeroization。

## 16. 批准门禁

只有维护者批准后才能开始业务编码。批准只授权上述五个检查点，不授权 P2.2d Runner/
Publication、P2.2e Retrieval Lab、Frontend Activation、新 Migration/Table/Deployable、第二套
AI Framework、ANN/Hybrid Retrieval 或 Live Knowledge 声明。

主要实施参考：

- Spring AI 2.0 Embedding Model API：
  <https://docs.spring.io/spring-ai/reference/api/embeddings.html>
- Spring AI 2.0 OpenAI Embeddings：
  <https://docs.spring.io/spring-ai/reference/api/embeddings/openai-embeddings.html>
