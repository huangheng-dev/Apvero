# P2.2e-4 Retrieval Policy Application and Disclosure Verification

Status: locally verified implementation checkpoint; milestone publication and GitHub CI remain
deferred until the complete P2.2 verification candidate.

## Scope

P2.2e-4 closes the public exact-retrieval result workflow after governed ranking:

```text
governed exact SQL ranking
  -> validate the immutable Retrieval Policy identity and digest
  -> collapse configured overlaps within the SQL topK
  -> read the current Governance Retention Policy
  -> apply the pinned estimator context budget
  -> project only bounded safe evidence
  -> return MATCHES or successful NO_EVIDENCE
  -> expose the write-authorized REST operation
```

The Knowledge product page remains non-live. This checkpoint does not add answer generation,
Application draft bindings, production Run evidence, a masking engine, hybrid search, hidden
oversampling or a new persistence model.

## Architecture and contract result

- P2, P2.2 and P2.2e remain `in-progress`.
- Knowledge owns policy application and depends only on the approved public Capability Registry
  estimator and Governance retention catalog.
- PostgreSQL remains the only mandatory stateful dependency.
- No migration, table, deployable, queue, framework, module boundary or schema shape changed.
- `POST /api/v1/knowledge-retrieval-tests` now implements the already committed OpenAPI 3.1
  contract, so only its `contract-only` marker was removed.
- The endpoint is a billable execution and therefore follows the platform POST rule: `write` or
  `admin`; a `read` key is denied before execution.

## Deterministic policy behavior

The result service fails closed unless the stored policy has:

- `exact-cosine@1.0.0`;
- `apvero-utf8-byte@1.0.0` backed by `apvero-utf8-byte-v1`;
- public `topK`, context-budget and score bounds;
- a durable publication retention version;
- `KEEP` or `COLLAPSE_ADJACENT`;
- `NO_EVIDENCE`;
- matching tenant/workspace scope and a valid canonical digest.

`KEEP` preserves SQL order. `COLLAPSE_ADJACENT` compares each ranked candidate with already
accepted hits from the same immutable Document. Stored half-open character ranges overlap only
when they intersect; touching ranges do not overlap. The earlier SQL rank wins, and discarded
hits are never replaced from outside the original SQL `topK`.

The context budget uses the pinned UTF-8 estimator on the exact content eligible for disclosure.
Content is included only in full. An oversized hit is skipped while later smaller hits remain
eligible. Metadata-only hits consume zero units. Returned ranks are reassigned consecutively.

## Current retention and safe disclosure

The current effective Retention Policy is read after governed ranking:

- `retainPayloads=false` suppresses content;
- `maskSensitiveFields=true` also suppresses content because Apvero has no approved shared
  unstructured-text masker;
- suppression occurs before budgeting and response projection;
- no local regular-expression DLP vocabulary is invented.

The response contains only contracted lineage IDs, score, digest, optional bounded content,
source title/type and bounded page, heading, paragraph and line anchors. It cannot expose stored
character offsets, raw URLs, paths, object keys, secrets, vectors, provider messages or provider
request identities.

If thresholding, overlap, budget or disclosure leaves no hit, the response is successful typed
`NO_EVIDENCE` with an empty list. It never authorizes an ungrounded fallback.

## Verification evidence

Unit and boundary tests prove:

- oversized English content is skipped and a later exact-budget Simplified Chinese/ASCII hit is
  accepted;
- overlapping same-Document ranges collapse, touching ranges remain, and equal ranges in another
  Document remain;
- payload-disabled and masking-required policies suppress content and consume no content budget;
- empty ranking returns typed `NO_EVIDENCE`;
- tampered policy digests fail before the current retention read;
- 20,000 Unicode code points are accepted by the public hit contract and 20,001 are rejected;
- final ranks are consecutive and only safe locators are projected.

The real PostgreSQL/pgvector integration now additionally proves:

- the REST request traverses authentication, workspace scope, governed Embedding, exact ranking,
  current durable Retention Policy and JSON projection;
- a `read` API key receives `APVERO_ACCESS_DENIED`;
- an incomplete request receives stable `APVERO_KNOWLEDGE_IDENTIFIER_INVALID`;
- an administrator receives one ranked hit with content suppressed by the current masking flag;
- unsafe internal fields are absent from the response.

## Verification executed

Passed locally:

- focused Knowledge policy/disclosure tests;
- controller mapping and OpenAPI controller conformance tests;
- real PostgreSQL 18, pgvector, Capability Registry, Governance, security and REST integration;
- Java compilation for Knowledge and the platform test suite.

The complete module, architecture, OpenAPI lint, packaging, Compose and security suite is executed
again at the P2.2e-5 slice candidate and at the complete P2.2 publication boundary.

## Rollback

- revert the P2.2e-4 local implementation commit or use the prior compatible binary;
- the OpenAPI schema remains compatible; restoring the marker returns the endpoint to
  `contract-only`;
- no data or migration rollback is required;
- Knowledge remains disabled by default and the product page remains non-live.

## Exit statement

P2.2e-4 is locally complete when:

> An authorized exact retrieval applies the immutable policy deterministically, honors the
> current retention decision, spends only the pinned context budget, discloses only safe bounded
> evidence, and returns either consecutive ranked matches or typed NO_EVIDENCE through the
> committed REST contract.

The next checkpoint is P2.2e-5 slice verification. P2.2e and P2.2 remain `in-progress`.
