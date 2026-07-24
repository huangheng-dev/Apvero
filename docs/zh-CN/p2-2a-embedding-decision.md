# P2.2a Embedding 契约与语料决策

状态：已实现契约外壳；业务执行继续禁用

决策基线：ADR-0006 与维护者已批准的 P2.2 实施计划

## 决策

P2.2a 在启用 Route 持久化或 Provider 执行之前，先冻结厂商无关 Java 边界：

- CHAT 与 EMBEDDING 继续属于同一个不可变 Model Route 聚合。
- Embedding Route 沿用现有 `name@正整数` Identity。
- 不可变 Profile 包含 `dimension`、`maximumInputTokens`、`maximumBatchSize` 与
  `normalization`。
- Vector Dimension 限制为 `1..16000`。
- 有序执行输入携带不可变 Item ID、SHA-256 Content Digest 与有边界文本。
- 有序输出必须保持 Item Identity 与位置、匹配固定 Dimension、只包含有限值且具有非零范数。
- Spring AI 与 Provider SDK 类型不得跨越公开模块边界。

V9 Migration 把所有现有 Route 回填为 CHAT。为了兼容旧 P1 Create Path，如果被引用的
Legacy Model 没有显式声明 CHAT，V9 会在不删除任何现有 Capability 的前提下补充 CHAT。
随后 V9 增加可判别的 Embedding Profile，检查引用的 Model 声明相同 Capability，并阻止
Route Mutation。本切片有意不提供 `EmbeddingCapability` 执行实现。P2.2b 持久化已批准的
Knowledge/Governance Shape 后，P2.2c 才提供确定性 Spring AI Adapter 与显式配置的真实
Adapter。

## 输入 Unit 估算器

首个冻结算法为：

```text
algorithmVersion = apvero-utf8-byte-v1
estimatedUnits = max(1, 准确有边界输入的 UTF-8 Byte Length)
```

它有意保持保守，只是确定性的准入与分批 Unit，并不声称等于 Provider 计费 Token。
Provider 返回实际 Usage 时，以实际 Usage 结算；否则 Estimate 必须继续标记为 `ESTIMATED`。

算法不依赖 Locale、Timezone、Process、Tokenizer Library 或 Provider；它不会自行 Normalize、
Trim 或静默改写文本。规范 Source Normalization 仍属于已有版本的 Parser/Chunker Pipeline。

## 语料

可执行语料位于
`modules/capability-registry/src/test/resources/p2-2a-embedding-corpus.json`，覆盖：

| 类别 | 目的 |
|---|---|
| 英文 | 稳定 ASCII Byte 计数 |
| 简体中文 | 多 Byte CJK 计数 |
| 中英混合 | 所有语言使用同一确定性规则 |
| Empty 与 Whitespace | 明确最小值并保留原始 Byte |
| Long Unbroken Token | 不依赖 Tokenizer Shortcut |
| Combining Character 与 Precomposed Text | 不进行隐藏 Unicode Normalization |
| Emoji 与 Format Character | 对抗性多 Byte 输入 |
| CRLF | 包含换行在内的准确 Byte Identity |

测试把每条语料固定到预期 Unit Count 与 Algorithm Version。任一行为变化都必须产生新的
Estimator Version；旧 Published Policy 与 Build 继续保留旧 Identity。

## 确定性 Adapter Profile

P2.2c 将实现 `apvero-deterministic-embedding@1.0.0`，固定 Dimension 为 `256`，使用 L2
Normalization。256 足够小，适合公开 CI 与 Quick Start，同时仍能验证非平凡 Vector 持久化与
Ranking。它不是语义质量选择，必须继续明确标记为仅供开发验证。

P2.2c 的 Golden Vector 将冻结 Canonicalization、Hash、Float32 Conversion、Dimension、
Normalization 与输出顺序。P2.2f 再分别测量 256、384、768、1,536 Dimension 的 Exact-search
Envelope。本决策不预先批准 ANN，也不承诺生产 Corpus 上限。

## Governance 兼容

新的公开 Governance Request Shape 包含：

- 一个 `ExecutionSubject`：`APPLICATION_RUN`、`KNOWLEDGE_INGESTION` 或 `KNOWLEDGE_QUERY`；
- 一个或多个类型化 Component：`CHAT_GENERATION`、`EMBEDDING_INDEX` 或
  `EMBEDDING_QUERY`；
- 准确 Route ID/Reference、确定性 Idempotency Identity、Estimated Unit/Cost 与 Currency。

现有 P1 单 CHAT API 保持不变。新 Overload 只把“一个 `APPLICATION_RUN` 加恰好一个
`CHAT_GENERATION` Component”适配到真实 P1 Method。Knowledge Reservation、Dispatch 与
Component Settlement 在 P2.2b 增加已批准表和实现之前，始终返回稳定 Disabled Code；
不会返回 Mock Reservation 或虚假的服务端成功。

## 拒绝的替代方案

- 拒绝 Provider Tokenizer Library，因为它会让 Core 绑定厂商，而且仍不能覆盖所有配置的
  Provider。
- 拒绝 Unicode Code Point Count，因为一个 Code Point 可能占多个 Tokenizer Byte，对对抗性
  输入不是同等保守的上界。
- 拒绝全局 Production Embedding Dimension，因为每个不可变 Route 拥有自己的 Profile。
- 拒绝在 P2.2a 发起 Provider Call，因为 Component 持久化与不确定结果恢复尚未存在。

## 回滚

本切片增加一个 Forward-only、Additive 的 Route Shape Migration。旧 Compatible Binary
继续通过 `CHAT` Default 插入 CHAT Route，并忽略新增的 Nullable Embedding Column。回滚时
部署旧 Binary 并保留 V9 与全部 Row；不提供破坏性 Down Migration。本切片不增加 REST Live
声明、Provider Traffic 或 Stateful Dependency，P1 CHAT 执行保持不变。
