# P2 contract baseline

## Status

P2 is in progress. ADR-0006 is accepted. The contracts in this document are approved design authority but remain `contract-only` until their corresponding P2 implementation slice is verified. Existing P1 APIs remain live; no P2 endpoint may report server-confirmed success yet.

## Contract inventory

| Contract | Status | Purpose |
|---|---|---|
| `release-bundle-manifest.schema.json` | legacy-live | Accurately describes recognized Manifest 1.0 CHAT forms, including P1 integer shorthand and generated runtime metadata. |
| `release-bundle-manifest.v1.1.schema.json` | contract-only | Strict CHAT/RAG release pins with explicit runtime mode and exact Knowledge bindings. |
| `citation.v1.schema.json` | contract-only | Citation identity validated against immutable Run retrieval evidence. |
| `grounded-answer.v1.schema.json` | contract-only | `GROUNDED` or `NO_EVIDENCE` structured RAG output. |
| `platform-api.yaml` Knowledge operations | contract-only | Workspace-scoped source, job, index, retrieval, binding, and Run-evidence workflow. |
| `ai-worker-internal.v1.yaml` | contract-only, internal-only | Stateless bounded parsing and deterministic chunking between Java and the worker. |

The existing Model Route contract keeps its live P1 CHAT request. P2 adds a `contract-only` EMBEDDING route variant with fixed dimension, maximum input tokens, maximum batch size, and normalization metadata. It remains the same provider-neutral Model Route aggregate rather than a second model system.

## P2.2 approved contract correction

The maintainer approved the P2.2 pre-implementation correction on 2026-07-24:

1. CHAT and EMBEDDING Model Routes retain the existing immutable positive-integer version and
   canonical `name@N` reference. `KnowledgeIndexVersion` pins both the exact Embedding Route ID and
   that reference. Knowledge Index and Retrieval Policy references remain semantic versions.
2. pgvector `vector` dimensions are limited to `1..16000`. Stored and query vectors must match the
   pinned Build dimension, contain only finite values, and have non-zero norm for cosine ranking.
3. Current Source tombstone state is checked when a new Build source set is selected. It is not a
   retrieval-time filter for an already published Index Version. Current authorization and
   retention/masking policy still apply at read time.
4. A published Retrieval Policy includes platform-assigned retrieval-algorithm and token-estimator
   versions, retention-policy version at publication, and a canonical policy digest. Current
   stricter disclosure policy always takes priority over historical policy provenance.

These are corrections to contract-only P2.2 fields; they migrate no live client or stored P2.2 row.
The separate Manifest 1.1 Model/Prompt reference mismatch remains an explicit P2.3 prerequisite.

## Compatibility rules

1. Manifest 1.0 remains readable and executes with historical CHAT behavior.
2. Existing Manifest 1.0 rows are never rewritten to manufacture Knowledge pins.
3. The existing release-create endpoint continues to advertise Manifest 1.0 until P2.3 implements full 1.1 validation and runtime behavior.
4. A new RAG release must use Manifest 1.1 and contain at least one exact `indexVersion + retrievalPolicyVersion` binding.
5. Manifest 1.1 CHAT releases must contain no Knowledge binding.
6. Integer shorthand such as `none@1` is legacy-only. Manifest 1.1 requires semantic-version references and forbids `latest`.
7. Once a Manifest 1.1 RAG release exists, a P1-only runtime is below the supported rollback floor.

Rule 6 describes the current contract-only Manifest 1.1 schema and does not redefine the existing
Model Route or Prompt aggregates. P2.3 must reconcile that schema with their implemented integer
version identities before Manifest 1.1 becomes live.

## Public workflow

```text
Knowledge Base
  -> source snapshot
  -> persisted ingestion job
  -> immutable source revision
  -> index build
  -> READY immutable index version
  -> retrieval test
  -> Application draft binding
  -> Manifest 1.1 RAG ReleaseBundle
  -> Run retrieval evidence
  -> validated citations
```

All P2 operations require `X-Apvero-Workspace-Id` plus authenticated authorization. Cross-workspace resource identifiers fail closed. Uploaded and fetched content is bounded; raw storage paths and unrestricted source URLs never appear in normal read contracts.

## Worker boundary

The Java control plane owns authentication, authorization, source fetching, SSRF protection, snapshot persistence, job state, retries, identity, audit, and billing. The worker receives already captured bytes, verifies their digest, parses and chunks them, and returns deterministic ordinal output with anchors. It has no database credentials, does not fetch URLs, and is not routed to browsers.

Source ingestion and index construction use separate persisted lifecycles. A P2.1 ingestion job ends after deterministic parsing/chunking; P2.2 owns `EMBEDDING`, `INDEXING`, and `VALIDATING` through `KnowledgeIndexBuildStatus`. Worker chunk offsets are zero-based Unicode code-point offsets into normalized document text with a half-open `[startOffset, endOffset)` interval; page, paragraph, and line anchors are one-based.

P2.1a removed the legacy worker host port and general `/worker/` proxy. The worker now starts only with the `knowledge` profile on a private internal network; even health is not exposed through the host or Console origin. The parser operation remains contract-only and disabled.

## Implementation order

1. P2.1 implements the physical Knowledge module, persistence, jobs, safe source snapshots, and worker contract.
2. P2.2 implements governed embeddings, pgvector builds, atomic publication, and Retrieval Lab.
3. P2.3 implements Application bindings, Manifest 1.1, grounded runtime, evidence, and citation validation.
4. P2.4 makes the bilingual product surface live only after all universal gates pass.

Contract-only status must be removed operation by operation only when implementation, security, telemetry, i18n, failure tests, and Compose evidence exist.
