# P2.2a Embedding Contract and Corpus Decision

Status: implemented contract shell; business execution remains disabled

Decision baseline: ADR-0006 and the maintainer-approved P2.2 implementation plan

## Decision

P2.2a freezes the provider-neutral Java boundary before Route persistence or provider execution is
enabled:

- CHAT and EMBEDDING remain capabilities of one immutable Model Route aggregate.
- An Embedding Route uses the existing `name@positive-integer` identity.
- Its immutable profile is `dimension`, `maximumInputTokens`, `maximumBatchSize`, and
  `normalization`.
- Vector dimensions are limited to `1..16000`.
- Ordered execution inputs carry an immutable item ID, SHA-256 content digest, and bounded text.
- Ordered outputs must preserve item identity and position, match the pinned dimension, contain only
  finite values, and have non-zero norm.
- Spring AI and provider SDK types do not cross the public module boundary.

Migration V9 backfills every existing Route as CHAT. For compatibility with the old P1 create path,
it also adds CHAT to a referenced legacy Model that did not explicitly declare it, without removing
any existing capability. V9 then adds the discriminated Embedding profile, checks that the
referenced Model declares the same capability, and prevents Route mutation. The shell intentionally
has no `EmbeddingCapability` execution implementation. P2.2c supplies the deterministic Spring AI
adapter and the explicitly configured real adapter only after P2.2b persists the approved Knowledge
and Governance shapes.

## Input-unit estimator

The frozen initial estimator is:

```text
algorithmVersion = apvero-utf8-byte-v1
estimatedUnits = max(1, UTF-8 byte length of the exact bounded input)
```

It is deliberately conservative. It is a deterministic admission and batching unit, not a claim
about provider billing Tokens. When a provider returns actual usage, actual usage is the settlement
source; otherwise the estimate remains labeled `ESTIMATED`.

The algorithm has no locale, timezone, process, tokenizer-library, or provider dependency. It does
not normalize, trim, or silently rewrite the text. Canonical source normalization belongs to the
already versioned parser/chunker pipeline.

## Corpus

The executable corpus is
`modules/capability-registry/src/test/resources/p2-2a-embedding-corpus.json`. It covers:

| Category | Purpose |
|---|---|
| English | Stable ASCII byte accounting |
| Simplified Chinese | Multi-byte CJK accounting |
| Mixed language | One deterministic rule across languages |
| Empty and whitespace | Explicit minimum and preserved bytes |
| Long unbroken token | No tokenizer-dependent shortcut |
| Combining characters and precomposed text | No hidden Unicode normalization |
| Emoji and format characters | Adversarial multi-byte input |
| CRLF | Exact byte identity, including line endings |

The test binds every corpus entry to an expected unit count and the algorithm version. Changing
either requires a new estimator version; old published policies and Builds retain the old identity.

## Deterministic adapter profile

P2.2c will implement `apvero-deterministic-embedding@1.0.0` with a fixed dimension of `256` and L2
normalization. Dimension 256 is intentionally small enough for public CI and Quick Start while still
exercising non-trivial vector persistence and ranking. It is not selected as a semantic-quality
claim and must remain labeled development-only.

Golden vectors in P2.2c will freeze canonicalization, hashing, float32 conversion, dimension,
normalization, and output order. P2.2f separately measures exact-search envelopes at 256, 384, 768,
and 1,536 dimensions. This decision does not pre-approve ANN or a production corpus limit.

## Governance compatibility

The new public Governance request shape contains:

- one `ExecutionSubject`: `APPLICATION_RUN`, `KNOWLEDGE_INGESTION`, or `KNOWLEDGE_QUERY`;
- one or more typed components: `CHAT_GENERATION`, `EMBEDDING_INDEX`, or `EMBEDDING_QUERY`;
- exact Route ID/reference, deterministic idempotency identity, estimated units/cost, and currency.

The existing P1 single-CHAT API remains unchanged. The new overload adapts only an
`APPLICATION_RUN` with exactly one `CHAT_GENERATION` component to that live P1 method. Knowledge
reservation, dispatch, and component settlement fail with stable disabled codes until P2.2b adds
the approved tables and implementation. No mock reservation or server-confirmed success is
returned.

## Rejected alternatives

- Provider tokenizer libraries were rejected because they would make the core provider-specific and
  still would not cover every configured provider.
- Unicode code-point counts were rejected because one code point can occupy several tokenizer
  bytes and would be a weaker conservative bound for adversarial input.
- A global production Embedding dimension was rejected because each immutable Route owns its
  profile.
- Implementing a provider call in P2.2a was rejected because component persistence and ambiguous
  outcome recovery are not yet present.

## Rollback

The slice adds one forward-only, additive Route-shape migration. Previous compatible binaries
continue inserting CHAT Routes through the `CHAT` default and ignore the new nullable Embedding
columns. Rollback deploys the previous binary while retaining V9 and all rows; no destructive down
migration is provided. The slice adds no REST live claim, provider traffic, or stateful dependency,
and P1 CHAT execution remains unchanged.
