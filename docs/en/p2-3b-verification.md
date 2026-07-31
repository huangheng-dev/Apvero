# P2.3b Immutable Manifest 1.1 Release Pinning Verification Candidate

Status: accepted by the maintainer on 2026-07-31. P2.3 remains `in-progress`; P2.3b is
`completed`.

## Scope

P2.3b closes this bounded Release workflow:

```text
load one scoped Application draft
  -> snapshot matching optimistic Application and binding versions
  -> resolve exact model route and Prompt references
  -> resolve every ordered opaque Knowledge pair
  -> require READY Index and executable Retrieval Policy
  -> construct and fully validate a server-authoritative manifest
  -> calculate its canonical digest
  -> insert one immutable ReleaseBundle or roll back
```

It does not perform production retrieval, write Run evidence, orchestrate grounded generation, or
validate citations.

## Authority and boundaries

- Stage: P2 / P2.3 / P2.3b.
- Owner: Release.
- Allowed dependencies used: Application, Capability Registry, and Knowledge public APIs.
- Release never reads another module's tables or imports internal packages.
- Client-supplied manifests were removed from the standard Release request. Only the semantic
  Release version is accepted.
- No database migration is required. The existing scoped, insert-only `release_bundle.manifest`
  JSONB and artifact digest satisfy this slice.
- ADR-0006 authorizes Manifest 1.1 pinning and the Release-to-Knowledge dependency. No new ADR is
  required.

## Complete offline schema validation

Apvero pins `com.networknt:json-schema-validator:3.0.2`, compatible with Java 21 and Jackson 3. It
supports JSON Schema Draft 2020-12 and is Apache-2.0 licensed.

Only Manifest 1.0 and 1.1 schema IDs are registered from packaged classpath resources. The
validator selects from this allowlist and never resolves a caller-provided or remote schema URI.
Format assertions, closed objects, conditional CHAT/RAG rules, bounds, cardinality, patterns, and
`latest` rejection are enforced. Unknown versions fail before schema lookup.

Validation failures expose only stable codes:

- `APVERO_RELEASE_MANIFEST_INVALID`;
- `APVERO_RELEASE_MANIFEST_UNSUPPORTED`;
- `APVERO_RELEASE_KNOWLEDGE_BINDING_INVALID`.

## Authoritative construction and compatibility

- CHAT releases continue to be generated as Manifest 1.0 and preserve historical P1 execution.
- RAG releases are generated as Manifest 1.1 with one to 16 ordered Knowledge pins.
- Model Route, Prompt, runtime temperature, and maximum output tokens come from the scoped
  Capability Registry projection.
- Index and Retrieval Policy canonical references come from scoped Knowledge projections.
- Retrieval Policy execution support is decided by Knowledge from pinned algorithm, estimator,
  retention provenance, and empty-evidence behavior.
- The Application version and binding-set version must match so a mixed draft snapshot cannot be
  released.
- Policy references are de-duplicated in first-binding order; Knowledge bindings retain exact
  Application order.
- Manifest 1.0 and 1.1 are validated when read. Unknown stored schema versions fail closed.
- Existing providers support Manifest 1.0 CHAT and Manifest 1.1 CHAT. Manifest 1.1 RAG is rejected
  until P2.3d supplies grounded orchestration; it never silently executes as CHAT.

## Persistence, failure, and telemetry

The Release transaction inserts only after all resolutions and complete validation succeed. A
failure in any pin creates no partial ReleaseBundle. The existing database trigger continues to
reject every Release update or delete.

`apvero.release.pin.validation` and `apvero.release.pin.validation.latency` expose only bounded
runtime-mode, outcome, and failure-family tags. Tenant, workspace, Application, Release, model,
Prompt, Index, Policy, digest, and content identities are never metric labels.

## Verification

Evidence covers:

- complete valid Manifest 1.0 and RAG 1.1 acceptance;
- incomplete, additional-property, `latest`, conditional-shape, and unknown-schema rejection;
- server-only standard Release request contract;
- ordered multi-binding resolution and canonical reference pinning;
- unsupported policy, empty selection, stale draft snapshot, and out-of-order selection rejection;
- exact artifact digest persistence and immutable database enforcement;
- rollback when a later binding fails after earlier binding resolution;
- historical CHAT construction and no Knowledge access on its path;
- stored Manifest validation on read;
- RAG-to-CHAT fallback prevention in deterministic and Spring AI providers;
- bounded telemetry tags;
- Spring Modulith and ArchUnit dependency verification.

Executed locally:

- complete Gradle test suite and bootable Platform Server JAR;
- P2.3b unit, contract, provider compatibility, and real PostgreSQL/Testcontainers integration
  suites;
- TypeScript strict typecheck and Console unit tests;
- English and Simplified Chinese key/placeholder validation;
- OpenAPI 3.1 lint and complete Manifest 1.1 example validation;
- default and Knowledge-profile Compose configuration validation;
- packaged-schema presence and source-diff checks.

The production retrieval performance benchmark remains explicitly opt-in and was skipped, as in
the established P2.2 verification policy; P2.3b does not change the retrieval hot path.

## Dependency and rollback

Gradle dependency insight resolves exactly `json-schema-validator:3.0.2`. The upstream project
identifies its Apache-2.0 license. The milestone pull request must still pass clean-host dependency
resolution and repository security review before P2.3 acceptance.

Before any Manifest 1.1 RAG row exists, rollback may use the previous P1-compatible binary. After
one exists, the rollback floor is a P2-compatible binary that preserves the additive immutable
row. Disabling Knowledge blocks new RAG release resolution; it never rewrites or downgrades stored
manifests.

## Known limits

1. Manifest 1.1 RAG is immutable and inspectable but intentionally not executable before P2.3d.
2. Runtime retrieval evidence belongs to P2.3c.
3. Grounded orchestration and `NO_EVIDENCE` behavior belong to P2.3d.
4. Structured answer and citation validation belong to P2.3e.
5. The schema retains Runtime `contract-only` status until complete P2.3 closure.

## Exit statement

The maintainer accepted P2.3b on 2026-07-31 and confirmed:

> Apvero can turn one consistent RAG Application draft into a completely validated,
> content-addressed, immutable Manifest 1.1 ReleaseBundle using only authoritative exact
> workspace-scoped projections, while preserving Manifest 1.0 CHAT and preventing ungrounded
> fallback execution.
