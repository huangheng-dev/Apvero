# Contributing to Apvero

Thank you for helping build Apvero.

1. Read `AGENTS.md` and the architecture YAML files completely.
2. Open an issue for significant product, contract, security, or module-boundary changes.
3. Add an ADR when a protected decision changes.
4. Keep changes scoped and include tests, telemetry, migrations, and bilingual documentation.
5. Sign commits using the Developer Certificate of Origin (`git commit -s`).

Use purpose-based, lowercase branch names:

- `docs/<scope>` for planning and documentation;
- `feature/<scope>` for business implementation;
- `fix/<scope>` for defect correction;
- `refactor/<scope>`, `chore/<scope>`, or `release/<scope>` for their corresponding maintenance work.

Do not use tool, model, agent, contributor, or vendor names as branch prefixes. Keep approved planning and implementation in separate branches and pull requests, and commit only coherent, verified checkpoints.

Pull requests must pass the configured backend, console, worker, contract, Compose, architecture, and i18n checks. Security-specific checks must be added with the feature that introduces the corresponding security boundary. Contributions are accepted under Apache License 2.0.
