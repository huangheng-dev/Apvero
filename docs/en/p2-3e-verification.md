# P2.3e Structured Answer and Citation Validation Verification Candidate

Status: accepted by the maintainer on 2026-07-31. P2.3 remains `in-progress`; P2.3e is
`completed`.

## Scope

P2.3e closes the successful grounded-answer path introduced by P2.3d:

```text
immutable Run evidence
  + strict provider draft containing answer text and evidence markers
  -> parse and validate an exact internal draft shape
  -> reject malformed, duplicate, unknown, or fabricated markers
  -> derive Grounded Answer 1.0 and Citation 1.0 identity from Run evidence
  -> atomically validate cited evidence and complete the Run
  -> expose workspace-scoped validated citations with read-time locators
```

This slice does not let a model author source identity, lineage, score, anchors, or locators.

## Authority and boundaries

- Stage: P2 / P2.3 / P2.3e.
- Owner: Runtime.
- Allowed dependencies: Application, Release, Capability Registry, and Knowledge public APIs.
- Runtime accesses only its own Run and retrieval-evidence tables.
- No cross-module SQL, new deployable, queue, database, framework, or stateful dependency was
  added.
- No migration is required. The accepted V13 transition from unvalidated to validated evidence
  already provides the persistence floor.
- ADR-0006 already approves structured answer validation, Citation 1.0, and read-time locators.

## Provider draft and public output

The Provider is instructed to return one JSON object with exactly four fields:

```json
{
  "schemaVersion": "1.0",
  "status": "GROUNDED",
  "answer": "Employees may claim up to 500 CNY per night.",
  "citationMarkers": ["[K1]"]
}
```

Runtime rejects wrappers, extra fields, invalid status or schema version, blank or oversized
answers, non-string markers, empty or oversized marker sets, duplicate markers, malformed markers,
and markers absent from the retained evidence for that Run.

The successful public Grounded Answer is rebuilt by Runtime. Citation metadata comes from the
immutable evidence hit and its exact index-version reference. The stored answer does not contain a
locator. Only the evidence hits cited by the validated answer move to `citation_validated=true`.

## Atomicity, lifecycle, and cost

Validation locks the workspace-scoped Run and reads its retained evidence. Citation validation and
the Run transition to `SUCCEEDED` share one transaction. A database failure cannot leave a
successful citation flag attached to a non-successful Run.

The governed Provider call is settled before answer validation because the external usage and cost
are already known. A malformed answer therefore fails the Run with
`APVERO_GROUNDED_OUTPUT_INVALID`; an invalid evidence marker fails with
`APVERO_CITATION_VALIDATION_FAILED`. Both preserve known token usage and cost, retain no untrusted
raw output on the failure path, and use bounded telemetry outcomes.

## Citation read and locator safety

`GET /api/v1/runs/{runId}/citations` is now a Runtime baseline. It:

- requires the authenticated workspace scope;
- returns only evidence already marked as citation-validated;
- preserves deterministic evidence order by retrieval sequence and hit rank;
- constructs a relative platform locator from source-revision identity and anchors at read time;
- never returns a local path, object-store key, provider URL, or model-supplied locator;
- fails closed as Run not found when the workspace does not own the Run.

Dereferencing the relative locator remains subject to the Knowledge source-revision content
endpoint's workspace authorization.

## Contracts and retention

Grounded Answer 1.0 and Citation 1.0 change from `contract-only` to Runtime `baseline`, and the
citation-list OpenAPI operation changes to `baseline`. `NO_EVIDENCE` remains the typed P2.3d result.
Retention policy still controls whether the structured Run output is retained. Citation lineage
remains independently reproducible in the evidence ledger; no durable locator is stored.

## Verification evidence

The candidate tests cover:

- strict valid provider-draft parsing;
- evidence-derived Citation identity in requested marker order;
- malformed and extra-field draft rejection;
- duplicate and fabricated marker rejection;
- successful atomic citation validation and grounded Run completion;
- known usage and cost preservation on citation-validation failure;
- absence of persisted locators in the grounded output;
- authorized locator generation from retained anchors;
- citation-list cross-workspace failure closure;
- citation endpoint and JSON Schema baseline declarations;
- controller delegation without accepting tenant input.

Executed locally:

- complete Gradle test suite: 325 tests across 94 suites, 0 failures, 0 errors, and the expected
  opt-in exact-retrieval benchmark skipped;
- bootable Platform Server JAR;
- TypeScript strict typecheck, 5 Console unit tests, and production build;
- English and Simplified Chinese validation with 405 leaf keys in each required locale;
- OpenAPI 3.1 lint with only the two established platform-info and worker-health 4xx warnings;
- default and Knowledge-profile Compose configuration validation;
- clean Git whitespace validation.

## Rollback

Before the P2.3 milestone is published, disable Manifest 1.1 RAG execution or restore the P2.3d
binary while retaining V13. A downgraded binary continues to read Runs and evidence but cannot
claim newly validated citations as a completed supported workflow. Existing terminal Runs remain
immutable.

## Known limits

1. P2.3f still owns complete compatibility, restart, retention, injection, and end-to-end
   hardening.
2. Product-page live-state closure remains P2.4.
3. A verified citation proves lineage to retained evidence; it does not independently prove that
   the model's prose is semantically correct.
4. Evaluation and answer-quality scoring remain the Evaluation stage rather than hidden P2.3
   scope.

## Exit statement

The maintainer accepted P2.3e on 2026-07-31 and confirmed:

> Apvero rejects model-authored citation identity, derives every accepted citation from immutable
> Run evidence, completes the Run and citation flags atomically, preserves known external cost on
> invalid output, and exposes only workspace-scoped read-time source locators.
