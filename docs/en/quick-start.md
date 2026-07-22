# Quick start

## Prerequisites

- Docker 29 or newer
- Docker Compose v2
- At least 4 CPU cores and 6 GB available memory

No local Java, Node.js or Python installation is required for the default self-hosted stack.

## Start

From the repository root:

```bash
cp .env.example .env
docker compose -f deploy/compose/compose.yaml up --build
```

Open <http://localhost:3000>. The database includes a local tenant, a default workspace, three applications, three release bundles and twelve deterministic runs so that every screen has inspectable data.

All published ports bind to `127.0.0.1` by default. Development mode supplies a local administrator identity for loopback use. Before exposing any port, set `APVERO_SECURITY_MODE=enforced`, provide a high-entropy `APVERO_BOOTSTRAP_ADMIN_TOKEN`, and place the service behind a trusted TLS reverse proxy.

## Complete the first workflow

1. Open **Applications** and create an application.
2. Open **Playground**, bind the seeded deterministic route and prompt, and run a preview.
3. Open **Releases**, select the application and create version `1.0.0`.
4. Select the release, enter an input and choose **Execute**.
5. Open **Runs** or **Usage & Costs** and inspect provider, tokens, latency, cost and trace ID.
6. In **Usage & Costs**, create a monthly cost and request-rate budget; enforcement occurs before provider execution.
7. Open **Audit Logs** to inspect attributed mutations and configure payload retention and masking.
8. Open **System Health** to inspect server-confirmed model route readiness without a paid probe.

This flow uses `local-deterministic@1.0.0`; it requires no provider key and never claims to be an external model.

## Endpoints

| Service | URL |
|---|---|
| Console | <http://localhost:3000> |
| Platform info | <http://localhost:8080/api/v1/platform> |
| Platform health | <http://localhost:8080/actuator/health> |

The AI worker is intentionally absent from the default profile. It has no host port, Console proxy, or public OpenAPI page. P2 implementation verification may start it with `docker compose --profile knowledge ...`; only the Platform Server can then reach its health and internal contract on `knowledge-internal`.

If upgrading a checkout that previously published port `8090`, stop the legacy container before the first new deployment so the old port mapping cannot survive an inactive profile:

```bash
docker compose --profile knowledge -f deploy/compose/compose.yaml stop ai-worker
```

When Knowledge verification is explicitly required, `docker compose --profile knowledge -f deploy/compose/compose.yaml up -d --build ai-worker` recreates it with the private-network-only configuration.

## Stop and reset

Stop without deleting data:

```bash
docker compose -f deploy/compose/compose.yaml down
```

Delete the local Compose database only when you intentionally want a clean seed:

```bash
docker compose -f deploy/compose/compose.yaml down --volumes
```

## Enable a real provider

Real network providers are opt-in. Set `APVERO_REAL_PROVIDER_ENABLED=true`, provide the provider key in an environment variable such as `OPENAI_API_KEY`, then create the Secret Reference, Provider, Model, Route and Prompt Version in the console. Bind the versioned Route and Prompt to an Application and verify it with a preview before releasing it. Private or loopback model endpoints remain blocked unless `APVERO_ALLOW_PRIVATE_MODEL_ENDPOINTS=true` is an intentional self-hosting decision.

## Security warning

Development mode is a local engineering convenience and must not be exposed to an untrusted network. Enforced mode accepts the bootstrap administrator token or a hashed, scoped API credential. Secret values are supplied through the process environment; the database and API contain only references.
