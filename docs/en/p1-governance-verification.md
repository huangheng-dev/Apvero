# P1 Governance Closure Verification

Status: implementation candidate verified locally; stage transition is not yet approved.

## Closed workflow

`credential -> pinned route -> pre-call admission -> provider -> settlement -> run -> usage -> audit -> retention`

- API credentials are verifier-only after one-time plaintext display.
- Workspace, application, and model-route policies enforce monthly cost and requests per minute before provider execution.
- Accepted calls reserve estimated cost and settle actual cost; stale reservations are reconciled.
- Runs record actor, route, release, reservation, trace, outcome, tokens, cost, and latency.
- Retention controls payload storage and recursive sensitive-field masking.
- Audit captures successful and failed mutations, policy denials, and cross-workspace decisions without request bodies or secrets.
- Audit update/delete is database-rejected; only the governance-owned retention transaction may delete expired events.
- Model route readiness and Micrometer runtime metrics are server-confirmed and require no billable probe.

## Verification evidence

Verified on 2026-07-21:

- `gradle test --no-daemon`: passed, including an empty PostgreSQL V1-to-V7 migration and P1 integration workflow.
- P1 integration boundaries: unauthenticated `401`; cross-workspace `403`; budget and rate rejection `429`; no reservation on rejection; denial audit present.
- Payload masking, actor attribution, reservation settlement, route readiness, audit immutability, controlled retention purge, and stale-state maintenance: passed.
- `pnpm typecheck`, `pnpm test`, `pnpm i18n:check`, and `pnpm build`: passed; 405 English and 405 Simplified Chinese leaf keys.
- AI Worker tests and Ruff: passed.
- OpenAPI 3.1 lint: valid. One existing warning remains for the intentionally public Platform information operation having no artificial 4XX response.
- Compose configuration: valid.

## Remaining exit gate

The standard Platform Server image build could not fetch the Docker Hub authorization token for `eclipse-temurin:25-jre`. The adopted JDK, image, Dockerfile, and architecture were not changed. A clean commit and green GitHub CI run are still required before the maintainer can approve the P1-to-P2 transition.
