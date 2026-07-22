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

## Compatibility rules

1. Manifest 1.0 remains readable and executes with historical CHAT behavior.
2. Existing Manifest 1.0 rows are never rewritten to manufacture Knowledge pins.
3. The existing release-create endpoint continues to advertise Manifest 1.0 until P2.3 implements full 1.1 validation and runtime behavior.
4. A new RAG release must use Manifest 1.1 and contain at least one exact `indexVersion + retrievalPolicyVersion` binding.
5. Manifest 1.1 CHAT releases must contain no Knowledge binding.
6. Integer shorthand such as `none@1` is legacy-only. Manifest 1.1 requires semantic-version references and forbids `latest`.
7. Once a Manifest 1.1 RAG release exists, a P1-only runtime is below the supported rollback floor.

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

The current P1 Compose and Nginx configuration still exposes the legacy worker service on the host and through a general `/worker/` proxy. Before P2.1 enables the parser operation, implementation must remove that general proxy and host-level business endpoint exposure; only health may remain externally observable. The P2 internal contract must never be reachable through the Console origin.

## Implementation order

1. P2.1 implements the physical Knowledge module, persistence, jobs, safe source snapshots, and worker contract.
2. P2.2 implements governed embeddings, pgvector builds, atomic publication, and Retrieval Lab.
3. P2.3 implements Application bindings, Manifest 1.1, grounded runtime, evidence, and citation validation.
4. P2.4 makes the bilingual product surface live only after all universal gates pass.

Contract-only status must be removed operation by operation only when implementation, security, telemetry, i18n, failure tests, and Compose evidence exist.
