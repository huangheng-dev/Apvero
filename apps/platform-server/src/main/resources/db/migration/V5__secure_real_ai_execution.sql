CREATE TABLE api_credential (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    workspace_id UUID NOT NULL REFERENCES workspace(id),
    name VARCHAR(160) NOT NULL,
    key_prefix VARCHAR(24) NOT NULL,
    verifier CHAR(64) NOT NULL UNIQUE,
    scopes TEXT[] NOT NULL,
    status VARCHAR(24) NOT NULL,
    expires_at TIMESTAMPTZ,
    last_used_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_api_credential_workspace_scope
        FOREIGN KEY (workspace_id, tenant_id) REFERENCES workspace(id, tenant_id),
    UNIQUE (workspace_id, name)
);

CREATE TABLE secret_reference (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    workspace_id UUID NOT NULL REFERENCES workspace(id),
    name VARCHAR(160) NOT NULL,
    kind VARCHAR(32) NOT NULL CHECK (kind = 'ENVIRONMENT'),
    locator VARCHAR(160) NOT NULL,
    status VARCHAR(24) NOT NULL,
    rotated_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_secret_reference_workspace_scope
        FOREIGN KEY (workspace_id, tenant_id) REFERENCES workspace(id, tenant_id),
    UNIQUE (workspace_id, name),
    UNIQUE (id, tenant_id, workspace_id)
);

CREATE TABLE model_provider (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    workspace_id UUID NOT NULL REFERENCES workspace(id),
    name VARCHAR(160) NOT NULL,
    provider_type VARCHAR(40) NOT NULL,
    base_url VARCHAR(500) NOT NULL,
    secret_reference_id UUID,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    version BIGINT NOT NULL DEFAULT 1 CHECK (version > 0),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_model_provider_workspace_scope
        FOREIGN KEY (workspace_id, tenant_id) REFERENCES workspace(id, tenant_id),
    CONSTRAINT fk_model_provider_secret_scope
        FOREIGN KEY (secret_reference_id, tenant_id, workspace_id)
        REFERENCES secret_reference(id, tenant_id, workspace_id),
    UNIQUE (workspace_id, name),
    UNIQUE (id, tenant_id, workspace_id)
);

CREATE TABLE model_definition (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    workspace_id UUID NOT NULL REFERENCES workspace(id),
    provider_id UUID NOT NULL,
    model_key VARCHAR(200) NOT NULL,
    name VARCHAR(160) NOT NULL,
    capabilities JSONB NOT NULL DEFAULT '[]'::jsonb,
    input_cost_micros_per_million BIGINT NOT NULL DEFAULT 0 CHECK (input_cost_micros_per_million >= 0),
    output_cost_micros_per_million BIGINT NOT NULL DEFAULT 0 CHECK (output_cost_micros_per_million >= 0),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_model_definition_provider_scope
        FOREIGN KEY (provider_id, tenant_id, workspace_id)
        REFERENCES model_provider(id, tenant_id, workspace_id),
    UNIQUE (provider_id, model_key),
    UNIQUE (id, tenant_id, workspace_id)
);

CREATE TABLE model_route (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    workspace_id UUID NOT NULL REFERENCES workspace(id),
    name VARCHAR(160) NOT NULL,
    version BIGINT NOT NULL CHECK (version > 0),
    model_id UUID NOT NULL,
    status VARCHAR(24) NOT NULL,
    timeout_ms INTEGER NOT NULL CHECK (timeout_ms BETWEEN 1000 AND 300000),
    max_output_tokens INTEGER NOT NULL CHECK (max_output_tokens BETWEEN 1 AND 200000),
    temperature NUMERIC(4,3),
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_model_route_model_scope
        FOREIGN KEY (model_id, tenant_id, workspace_id)
        REFERENCES model_definition(id, tenant_id, workspace_id),
    UNIQUE (workspace_id, name, version),
    UNIQUE (id, tenant_id, workspace_id)
);

CREATE TABLE prompt_asset (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    workspace_id UUID NOT NULL REFERENCES workspace(id),
    slug VARCHAR(80) NOT NULL,
    name VARCHAR(160) NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_prompt_asset_workspace_scope
        FOREIGN KEY (workspace_id, tenant_id) REFERENCES workspace(id, tenant_id),
    UNIQUE (workspace_id, slug),
    UNIQUE (id, tenant_id, workspace_id)
);

CREATE TABLE prompt_version (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    workspace_id UUID NOT NULL REFERENCES workspace(id),
    prompt_asset_id UUID NOT NULL,
    version INTEGER NOT NULL CHECK (version > 0),
    system_prompt TEXT NOT NULL,
    variables JSONB NOT NULL DEFAULT '[]'::jsonb,
    status VARCHAR(24) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_prompt_version_asset_scope
        FOREIGN KEY (prompt_asset_id, tenant_id, workspace_id)
        REFERENCES prompt_asset(id, tenant_id, workspace_id),
    UNIQUE (prompt_asset_id, version),
    UNIQUE (id, tenant_id, workspace_id)
);

ALTER TABLE ai_application
    ADD COLUMN draft_model_route_id UUID,
    ADD COLUMN draft_prompt_version_id UUID,
    ADD CONSTRAINT fk_application_model_route_scope
        FOREIGN KEY (draft_model_route_id, tenant_id, workspace_id)
        REFERENCES model_route(id, tenant_id, workspace_id),
    ADD CONSTRAINT fk_application_prompt_version_scope
        FOREIGN KEY (draft_prompt_version_id, tenant_id, workspace_id)
        REFERENCES prompt_version(id, tenant_id, workspace_id);

ALTER TABLE release_bundle
    ADD COLUMN purpose VARCHAR(24) NOT NULL DEFAULT 'PRODUCTION',
    ADD COLUMN expires_at TIMESTAMPTZ;

ALTER TABLE release_bundle
    DROP CONSTRAINT release_bundle_application_id_artifact_digest_key;

CREATE INDEX idx_release_bundle_application_digest
    ON release_bundle(application_id, artifact_digest);

ALTER TABLE ai_run
    ADD COLUMN failure_category VARCHAR(64),
    ADD COLUMN failure_message VARCHAR(500);

CREATE INDEX idx_api_credential_workspace ON api_credential(workspace_id, status);
CREATE INDEX idx_secret_reference_workspace ON secret_reference(workspace_id, updated_at DESC);
CREATE INDEX idx_model_provider_workspace ON model_provider(workspace_id, updated_at DESC);
CREATE INDEX idx_model_definition_workspace ON model_definition(workspace_id, updated_at DESC);
CREATE INDEX idx_model_route_workspace ON model_route(workspace_id, created_at DESC);
CREATE INDEX idx_prompt_asset_workspace ON prompt_asset(workspace_id, updated_at DESC);
CREATE INDEX idx_prompt_version_workspace ON prompt_version(workspace_id, created_at DESC);

INSERT INTO model_provider (
    id, tenant_id, workspace_id, name, provider_type, base_url,
    secret_reference_id, enabled, version, created_at, updated_at
) VALUES (
    '00000000-0000-0000-0000-000000003001',
    '00000000-0000-0000-0000-000000000001',
    '00000000-0000-0000-0000-000000000101',
    'Deterministic Local', 'DETERMINISTIC_LOCAL', 'local://deterministic',
    NULL, TRUE, 1, now(), now()
);

INSERT INTO model_definition (
    id, tenant_id, workspace_id, provider_id, model_key, name, capabilities,
    input_cost_micros_per_million, output_cost_micros_per_million, enabled, created_at, updated_at
) VALUES (
    '00000000-0000-0000-0000-000000003101',
    '00000000-0000-0000-0000-000000000001',
    '00000000-0000-0000-0000-000000000101',
    '00000000-0000-0000-0000-000000003001',
    'local-deterministic', 'Deterministic Local', '["CHAT"]'::jsonb,
    0, 0, TRUE, now(), now()
);

INSERT INTO model_route (
    id, tenant_id, workspace_id, name, version, model_id, status,
    timeout_ms, max_output_tokens, temperature, created_at
) VALUES (
    '00000000-0000-0000-0000-000000003201',
    '00000000-0000-0000-0000-000000000001',
    '00000000-0000-0000-0000-000000000101',
    'local-deterministic', 1,
    '00000000-0000-0000-0000-000000003101',
    'PUBLISHED', 30000, 512, 0, now()
);

INSERT INTO prompt_asset (
    id, tenant_id, workspace_id, slug, name, description, created_at, updated_at
) VALUES (
    '00000000-0000-0000-0000-000000004001',
    '00000000-0000-0000-0000-000000000001',
    '00000000-0000-0000-0000-000000000101',
    'apvero-baseline', 'Apvero Baseline',
    'Default versioned system Prompt for the deterministic local workflow.', now(), now()
);

INSERT INTO prompt_version (
    id, tenant_id, workspace_id, prompt_asset_id, version,
    system_prompt, variables, status, created_at
) VALUES (
    '00000000-0000-0000-0000-000000004101',
    '00000000-0000-0000-0000-000000000001',
    '00000000-0000-0000-0000-000000000101',
    '00000000-0000-0000-0000-000000004001', 1,
    'You are an Apvero application. Answer accurately and keep the response concise.',
    '[]'::jsonb, 'PUBLISHED', now()
);

UPDATE ai_application
SET draft_model_route_id = '00000000-0000-0000-0000-000000003201',
    draft_prompt_version_id = '00000000-0000-0000-0000-000000004101';

COMMENT ON TABLE secret_reference IS 'Metadata-only secret locator. Secret values are never persisted.';
COMMENT ON TABLE prompt_version IS 'Immutable versioned Prompt content; create a new row for every change.';
COMMENT ON TABLE model_route IS 'Immutable published model route version used by release bundles.';
