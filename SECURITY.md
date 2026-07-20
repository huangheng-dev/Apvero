# Security policy

## Reporting a vulnerability

Do not open a public issue for an undisclosed vulnerability. Send a private report through the GitHub Security Advisory feature of the canonical repository. Include the affected version, reproducible steps, impact, and any suggested mitigation.

## Current executable baseline

- published Compose ports bind to `127.0.0.1` by default;
- database constraints enforce tenant/workspace/application consistency;
- released artifacts are immutable and identified by digest;
- the workspace header is data scope only and **is not authentication**;
- API keys, secret management, authorization, Tool/MCP execution, retention, and masking are not implemented yet.

Do not expose the current baseline to an untrusted network.

## Required guarantees before production exposure

- secrets are referenced by identifier and are never returned by normal read APIs;
- API key plaintext is shown once and only a verifier is retained;
- authorization is deny-by-default and tenant scope is mandatory;
- tool and MCP calls require method-level permissions, schemas, timeouts, quotas, and audit events;
- prompt and response retention is configurable and sensitive fields are masked before analytics export;
- release artifacts remain immutable and identified by digest.

Security fixes receive priority over feature work. Supported versions will be listed after the first tagged release.
