# Apvero AI Development Constitution

This file governs every AI-assisted and human-authored change in this repository.

## Mission

Build Apvero as an open-source, self-hosted AI application engineering platform with one coherent lifecycle:

`design -> test -> evaluate -> release -> run -> observe -> feedback -> improve`

Optimize for reproducibility, safety, extensibility, operability, local self-hosting, and complete user workflows. Do not optimize for feature count, vendor marketing, or framework trends.

## Authority order

When instructions conflict, follow this order:

1. maintainer-approved ADRs;
2. `architecture/invariants.yaml`;
3. `product/navigation.yaml` and `product/pages.yaml`;
4. `architecture/modules.yaml` and `architecture/dependency-rules.yaml`;
5. public contracts under `contracts/`;
6. architecture and product documentation;
7. current implementation;
8. an AI-generated suggestion or task interpretation.

Never silently resolve a conflict against a higher-authority source.

## Product invariants

1. `AI Application` is the root product entity. Agent is one runtime mode.
2. Every application owns Build, Test, Evaluate, Release, Run, and Observe behavior.
3. Production always references an immutable `ReleaseBundle`.
4. A release bundle pins model route, prompt, schema, knowledge index, capability, policy, memory policy, evaluation report, and runtime parameter versions.
5. Runs are reproducible from release identity, inputs, policy decisions, trace events, and retained artifacts.
6. Production traces and feedback can become versioned datasets evaluated against candidate releases.
7. Provider-specific concepts never enter core domain APIs. Providers implement declared SPI contracts.
8. Tool and MCP execution requires method permissions, typed schemas, timeouts, quotas, and audit events.
9. Tenant isolation applies to storage, authorization, caches, vector filters, object paths, events, metrics, and logs.
10. English is the source locale. Simplified Chinese has equal required feature coverage.

## Product surface rules

1. The approved navigation and page inventory in `product/` is mandatory and may not be silently reduced, renamed, or regrouped.
2. Agent and Workflow are runtime-mode views inside Applications; they remain discoverable secondary capabilities and never become competing root aggregates.
3. Product architecture documentation belongs in the documentation footer, not the primary business navigation.
4. Demo fixtures are allowed only in the product prototype and must carry an explicit Demo data label.
5. Demo interactions update preview state only and never claim a server-confirmed success.
6. Live, mixed, and demo data modes remain visually distinguishable on every page.
7. Cache belongs to AI Gateway, Webhook belongs to Integrations, Token statistics belongs to Usage and Costs, and administrative events belong to Audit.
8. A page is not removed because its backend is planned; its prototype remains the approved product contract.
9. Primary navigation uses short international task names. Hub, Center, and Management are not added unless they distinguish a real product boundary.
10. Legacy page routes resolve to their canonical parent so bookmarks remain usable while reserved capabilities move to secondary views.

## Architecture rules

1. Start as a modular monolith. Split a deployable only after an ADR proves a scaling, isolation, runtime, or failure boundary.
2. Modules may depend only on public APIs or events allowed by `architecture/dependency-rules.yaml`.
3. Cross-module database access is forbidden.
4. Business logic must not move into generic `common`, `shared`, or `utils` packages.
5. Synchronous cross-module calls use declared public interfaces; asynchronous collaboration uses versioned events.
6. Database changes require forward Flyway migrations plus rollback or mitigation documentation.
7. Public REST contracts use OpenAPI 3.1. Structured AI outputs and plugin payloads use JSON Schema.
8. Long-running and retryable work is idempotent and persists execution state.
9. Logs are not a source of truth for runs; typed run and span events are.
10. Optional infrastructure stays behind adapters. The default self-hosted deployment remains usable with PostgreSQL as the only mandatory stateful dependency.
11. Spring AI is the single core Java AI abstraction. LangChain4j and Spring AI Alibaba may appear only in isolated compatibility adapters approved by ADR.

## Security rules

1. Never store provider keys, API keys, passwords, tokens, or webhook secrets in plaintext.
2. API key plaintext is displayed once; only a verifier and metadata remain.
3. Secrets are referenced by ID and never returned by normal read APIs.
4. SQL capabilities are read-only by default with schema allowlists, statement controls, row limits, and timeouts.
5. Plugins never execute inside the control-plane process without an approved isolation ADR.
6. Plugin packages require manifest validation, compatibility checks, declared permissions, an integrity digest, and a signature policy.
7. Prompt and response retention is configurable; sensitive fields are masked before analytics export.
8. Authorization is deny-by-default and emits auditable policy decisions.

## Internationalization rules

1. Never hard-code user-visible frontend text or backend error messages.
2. Backend errors use stable codes; clients localize messages.
3. Every feature change adds English and Simplified Chinese keys together.
4. Dates, numbers, currencies, time zones, and plural forms use locale-aware APIs.
5. Core English documents have matching `zh-CN` documents.
6. CI fails on missing keys, orphan keys, placeholder mismatches, or incomplete required-locale coverage.

## Required workflow before editing

1. Read this file completely.
2. Read `architecture/invariants.yaml` completely.
3. Read the target module entry in `architecture/modules.yaml`.
4. Read applicable dependency rules and public contracts.
5. State target modules, allowed dependencies, affected contracts, migration needs, and tests.
6. Determine whether the request changes an invariant, boundary, contract, release semantic, security policy, or technology baseline.

If a protected area changes, stop implementation and propose an ADR containing context, decision, alternatives, compatibility, migration, security, operability, open-source impact, and rollback plan. Implementation starts only after maintainer approval.

## Forbidden behavior

Do not:

- create a module, deployable, database, queue, framework, or stateful dependency without an approved ADR;
- import another module's internal implementation;
- add a second abstraction framework that overlaps an adopted core framework;
- make production configuration mutable after release;
- reference `latest` provider resources from a release bundle;
- leak provider SDK types into core domain APIs;
- weaken tenant filters, authorization, audit, retention, or secret handling for convenience;
- hide incomplete behavior behind mock success states;
- mark work complete without relevant tests, migrations, contracts, telemetry, i18n, and documentation;
- rewrite unrelated code while implementing a scoped change;
- change architecture rules because implementation is difficult.

## Required verification

Run the applicable subset of:

- Spring Modulith and ArchUnit verification;
- Java unit, module integration, and Testcontainers tests;
- OpenAPI and JSON Schema compatibility checks;
- Flyway migration tests;
- TypeScript strict typecheck, unit tests, and Playwright critical paths;
- i18n key and placeholder validation;
- Python tests, Ruff, and type checks;
- secret, dependency, image, and source security scans;
- container builds and health checks;
- gateway performance tests for runtime-path changes.

## Definition of done

A feature is complete only when it closes a real workflow, preserves module boundaries, documents compatible contracts, migrates data safely, defines security and telemetry behavior, includes English and Chinese UI/docs, tests success and failure paths, keeps Compose healthy, and can be rolled back or disabled safely.

## Final enforcement statement

AI may propose implementation changes. AI may not change product invariants, module boundaries, public contracts, release semantics, security policy, internationalization policy, or the approved technology baseline without a maintainer-approved ADR.
