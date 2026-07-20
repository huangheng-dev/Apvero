# Apvero

Apvero is an open-source, self-hosted platform for building, evaluating, releasing, operating, and continuously improving AI applications.

> Application Platform for Versioned, Evaluated, Reliable Operations.

[简体中文](README.zh-CN.md)

## Why Apvero

Apvero is application-first rather than agent-first. Chat, RAG, structured generation, tools, agents, and workflows are runtime modes of one versioned `AI Application` lifecycle:

```text
Design -> Test -> Evaluate -> Release -> Run -> Observe -> Feedback -> Improve
```

The architecture defines the full target while the first repository baseline implements a secure application/release/run spine:

- immutable release bundles that pin every runtime dependency;
- a provider-neutral runtime SPI and explicit deterministic local provider;
- typed trace, usage, cost and latency records for every completed run;
- versioned model providers, models, routes and prompts in a provider-neutral capability registry;
- environment-backed secret references, hashed API credentials and deny-by-default production security;
- modular-monolith boundaries enforced by architecture metadata and tests;
- English as the source locale with first-class Simplified Chinese support;
- a default self-hosted deployment that requires only PostgreSQL with pgvector.

## Repository status

This is the architecture-first foundation and the first runnable vertical slice:

```text
Configure route + prompt -> bind application -> preview -> immutable release -> run -> inspect trace and cost
```

The deterministic local provider is an explicit development provider, not a fake external-model response. OpenAI-compatible providers can be enabled explicitly through the Spring AI adapter; provider secrets are resolved from the environment and are never stored in the database.

The console exposes the complete approved 23-page primary product surface plus reserved secondary views for Agents, Workflows, MCP Servers, Memory Providers, Feedback, Budgets, identity administration, and the extension marketplace. This keeps the navigation compact without deleting planned capability. Every page identifies its data boundary:

- **Live data** — Applications, Releases, Runs, Models, Prompts, Playground, Usage & Costs, API Keys, and Secrets use the current server APIs.
- **Live + demo** — Overview and System Health combine confirmed state with labeled projections.
- **Demo data** — planned modules use rich local-only fixtures and never report a fake server success.

The authoritative page inventory and interaction requirements live in [`product/navigation.yaml`](product/navigation.yaml) and [`product/pages.yaml`](product/pages.yaml). The naming and consolidation decision is recorded in [`ADR-0004`](docs/adr/0004-international-product-navigation.md).

## Technology baseline

- Java 25, Spring Boot 4.1, Spring AI 2.0, Spring Modulith 2.1
- React 19.2, TypeScript, Vite 8, TanStack Query, i18next
- Python 3.14, FastAPI, Pydantic, uv
- PostgreSQL 18 with pgvector; Redis, MinIO, Kafka, and ClickHouse remain optional adapters
- Micrometer through Spring Boot Actuator; OpenTelemetry integration is planned
- Docker Compose and GitHub Actions

## Quick start

Prerequisites: Docker 29+ with Compose v2.

```bash
cp .env.example .env
docker compose -f deploy/compose/compose.yaml up --build
```

Then open:

- Console: <http://localhost:3000>
- Platform API: <http://localhost:8080/api/v1/platform>
- Health: <http://localhost:8080/actuator/health>
- AI worker health: <http://localhost:8090/health>

See [English quick start](docs/en/quick-start.md) or [中文快速开始](docs/zh-CN/quick-start.md).

Read the [complete architecture](docs/en/architecture.md) and [technology stack](docs/en/technology-stack.md). Target capabilities are explicitly marked as implemented, baseline, contract-only, or planned.

## Architecture authority

AI-assisted development and human contributions must read these files before changing code:

1. [AGENTS.md](AGENTS.md)
2. [architecture/invariants.yaml](architecture/invariants.yaml)
3. [`product/navigation.yaml`](product/navigation.yaml) and [`product/pages.yaml`](product/pages.yaml)
4. [architecture/modules.yaml](architecture/modules.yaml)
5. [architecture/dependency-rules.yaml](architecture/dependency-rules.yaml)
6. approved records under `docs/adr/`
7. public contracts under `contracts/`

If a change conflicts with a protected rule, implementation stops until a maintainer approves an ADR.

## License and brand

Source code is licensed under Apache License 2.0. The license does not grant unrestricted rights to the Apvero name or logo. See [NOTICE](NOTICE).
