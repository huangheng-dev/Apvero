# Apvero Plugin SDK contract

The plugin surface is contract-first and not executable in-process. A future plugin package must:

1. validate against `../schemas/plugin-manifest.schema.json`;
2. declare every permission explicitly;
3. include a SHA-256 integrity digest and satisfy the active signature policy;
4. run behind an isolated adapter boundary;
5. exchange only versioned JSON Schema payloads with the platform.

No JAR upload or arbitrary code execution is enabled by the current executable baseline.
