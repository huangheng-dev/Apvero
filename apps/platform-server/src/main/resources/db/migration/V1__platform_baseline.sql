CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE tenant (
    id UUID PRIMARY KEY,
    slug VARCHAR(80) NOT NULL UNIQUE,
    name VARCHAR(160) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE workspace (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    slug VARCHAR(80) NOT NULL,
    name VARCHAR(160) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, slug)
);

CREATE TABLE ai_application (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    workspace_id UUID NOT NULL REFERENCES workspace(id),
    slug VARCHAR(80) NOT NULL,
    name VARCHAR(160) NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    runtime_mode VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    version BIGINT NOT NULL DEFAULT 1 CHECK (version > 0),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE (workspace_id, slug)
);

CREATE INDEX idx_ai_application_workspace_updated
    ON ai_application(workspace_id, updated_at DESC);

CREATE TABLE release_bundle (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    workspace_id UUID NOT NULL REFERENCES workspace(id),
    application_id UUID NOT NULL REFERENCES ai_application(id),
    version VARCHAR(64) NOT NULL,
    artifact_digest CHAR(64) NOT NULL,
    manifest JSONB NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (application_id, version),
    UNIQUE (application_id, artifact_digest)
);

CREATE INDEX idx_release_bundle_workspace_created
    ON release_bundle(workspace_id, created_at DESC);

CREATE TABLE ai_run (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    workspace_id UUID NOT NULL REFERENCES workspace(id),
    application_id UUID NOT NULL REFERENCES ai_application(id),
    release_bundle_id UUID NOT NULL REFERENCES release_bundle(id),
    status VARCHAR(32) NOT NULL,
    provider_id VARCHAR(120) NOT NULL,
    input JSONB NOT NULL,
    output JSONB NOT NULL,
    latency_ms BIGINT NOT NULL CHECK (latency_ms >= 0),
    prompt_tokens INTEGER NOT NULL CHECK (prompt_tokens >= 0),
    completion_tokens INTEGER NOT NULL CHECK (completion_tokens >= 0),
    cost_micros BIGINT NOT NULL CHECK (cost_micros >= 0),
    trace_id VARCHAR(80) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_ai_run_workspace_created
    ON ai_run(workspace_id, created_at DESC);
CREATE INDEX idx_ai_run_application_created
    ON ai_run(application_id, created_at DESC);

COMMENT ON TABLE release_bundle IS 'Immutable application release artifact; rows are insert-only by invariant.';
COMMENT ON TABLE ai_run IS 'Typed source of truth for runtime execution, usage, cost and trace identity.';
