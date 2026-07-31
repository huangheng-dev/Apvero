# P2.3c Scoped Run Retrieval Evidence Ledger Verification Candidate

Status: accepted by the maintainer on 2026-07-31. P2.3 remains `in-progress`; P2.3c is
`completed`.

## Scope

P2.3c closes this bounded Runtime evidence workflow:

```text
lock one workspace-scoped Run
  -> require the next contiguous retrieval sequence
  -> accept one already governed Knowledge retrieval result
  -> apply the execution retention decision before persistence
  -> assign deterministic global [K1] markers
  -> persist the retrieval and every ordered hit in one transaction
  -> read the retained projection only through Run and workspace scope
```

It does not invoke retrieval, construct model context, call a model, validate an answer, or mark a
Citation as valid. Those behaviors remain in P2.3d and P2.3e.

## Authority and boundaries

- Stage: P2 / P2.3 / P2.3c.
- Owner: Runtime.
- Allowed dependency added: Knowledge public API only.
- Runtime does not query Knowledge tables and stores opaque exact-version identities plus canonical
  references copied from the immutable Release execution context.
- V13 is the approved additive migration from ADR-0006. It adds `ai_run_retrieval`,
  `ai_run_retrieval_hit`, and nullable `ai_run.failure_code`.
- No new deployable, framework, queue, database, or mandatory dependency was added.

## Contract correction before implementation

The former contract-only Run retrieval response composed `KnowledgeRetrievalResult`. That shape
could not carry deterministic evidence markers, exact canonical references, retention provenance,
or citation-validation state. It also inherited a closed Knowledge hit object, so adding Run-only
fields through JSON Schema composition was invalid.

Before making the endpoint live, P2.3c replaced that composition with dedicated
`RunRetrievalExecution` and `RunRetrievalHit` projections. The implemented response now includes:

- global deterministic markers;
- opaque Index and Retrieval Policy IDs;
- their exact canonical references;
- query and content digests;
- ordered source lineage and anchors;
- bounded retention-filtered content;
- retention decision version;
- citation-validation state.

`GET /api/v1/runs/{runId}/retrieval` is now `baseline`.
`GET /api/v1/runs/{runId}/citations` remains explicitly `contract-only`.

## Persistence and integrity

V13 enforces:

- transitive tenant and workspace scope through a composite Run foreign key;
- contiguous application-level sequence checks plus unique `(run_id, sequence)`;
- unique `(run_id, marker)` and `(retrieval_id, rank)`;
- bounded sequence, rank, score, latency, hit count, anchors, source type, digest, and exact-reference
  shapes;
- consistency between `MATCHES`/`NO_EVIDENCE` and hit count;
- immutable retrieval rows and immutable hit identity/content;
- only the future false-to-true citation validation transition;
- deletion only during the existing transaction-scoped Runtime retention purge.

The repository locks the Run before calculating sequence and marker order, preventing concurrent
writers from assigning duplicate evidence identity. A parent row and all hits commit or roll back
together.

## Retention and security

The writer receives the execution-time retention decision and persists hit content only when
payload retention is enabled and sensitive-field masking is disabled. Otherwise content is
persisted as null while immutable digest, source lineage, rank, score, and anchors remain available
for reproducibility. The database stores no source path, object-store path, secret, credential, or
durable authorization URL.

Reads require both Run ID and workspace scope. A Run from another workspace is indistinguishable
from a missing Run. The API does not accept tenant identity from the caller.

## Failure semantics

V13 adds nullable `ai_run.failure_code` without rewriting historical Runs. New provider failures
record stable machine-readable Runtime codes separately from bounded category and display-safe
message fields. Evidence failures use stable bad-request, not-found, or conflict codes.

## Verification

Evidence covers:

- clean V1-to-V13 migration through the application context;
- real V12-to-V13 upgrade with historical Run count preservation;
- ordered multi-retrieval persistence with global `[K1]`, `[K2]` markers;
- retained and masked content decisions before insertion;
- typed `NO_EVIDENCE` persistence;
- wrong-workspace non-disclosure;
- sequence-gap rejection without partial rows;
- rollback of the parent and earlier hits when a later hit cannot be stored;
- database rejection of retrieval and hit identity mutation;
- controlled retention cascade and protection from ordinary deletion;
- controller-to-public-boundary mapping;
- OpenAPI implementation status and dedicated evidence schema;
- Spring Modulith dependency verification.

Executed locally:

- complete Gradle test suite and bootable Platform Server JAR;
- final focused Runtime, V13 clean/upgrade, controller, error, delivery-stage, and module-boundary
  suites after the deferred database integrity guard was added;
- TypeScript strict typecheck and Console unit tests;
- English and Simplified Chinese key/placeholder validation;
- OpenAPI 3.1 lint with only the two established platform-info and worker-health 4xx warnings;
- default and Knowledge-profile Compose configuration validation.

The production exact-retrieval benchmark remains explicitly opt-in and was skipped. P2.3c changes
evidence persistence after retrieval and does not change the P2.2 retrieval SQL hot path.

## Rollback

Before evidence rows exist, rollback may use the previous P2.3b binary. After evidence exists, the
rollback floor is a P2-compatible binary that preserves V13 and continues controlled Run retention.
The additive tables and nullable failure column remain safe when unused. Disabling grounded Runtime
execution prevents new evidence writes without mutating retained evidence.

## Known limits

1. P2.3c exposes no Console page.
2. Retrieval orchestration and bounded context construction remain P2.3d.
3. Citation validation and authorized locators remain P2.3e.
4. P2.3 remains incomplete until P2.3f compatibility and closure verification passes.

## Exit statement

The maintainer accepted P2.3c on 2026-07-31 and confirmed:

> Apvero can persist and inspect complete, ordered, immutable, retention-filtered retrieval evidence
> for one workspace-scoped Run, with stable global markers and transactional failure behavior,
> without claiming that grounded execution or citation validation is already live.
