CREATE TABLE budget_policy (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    workspace_id UUID NOT NULL REFERENCES workspace(id),
    name VARCHAR(160) NOT NULL,
    scope_type VARCHAR(24) NOT NULL CHECK (scope_type IN ('WORKSPACE', 'APPLICATION', 'MODEL_ROUTE')),
    scope_id UUID,
    monthly_cost_limit_micros BIGINT CHECK (monthly_cost_limit_micros IS NULL OR monthly_cost_limit_micros >= 0),
    requests_per_minute INTEGER CHECK (requests_per_minute IS NULL OR requests_per_minute > 0),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_budget_policy_workspace_scope
        FOREIGN KEY (workspace_id, tenant_id) REFERENCES workspace(id, tenant_id),
    CONSTRAINT budget_policy_scope_shape CHECK (
        (scope_type = 'WORKSPACE' AND scope_id IS NULL)
        OR (scope_type IN ('APPLICATION', 'MODEL_ROUTE') AND scope_id IS NOT NULL)
    ),
    UNIQUE (workspace_id, name)
);

CREATE TABLE rate_limit_counter (
    policy_id UUID NOT NULL REFERENCES budget_policy(id),
    window_started_at TIMESTAMPTZ NOT NULL,
    request_count INTEGER NOT NULL CHECK (request_count > 0),
    PRIMARY KEY (policy_id, window_started_at)
);

CREATE TABLE execution_reservation (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    workspace_id UUID NOT NULL REFERENCES workspace(id),
    application_id UUID NOT NULL REFERENCES ai_application(id),
    model_route_id UUID NOT NULL REFERENCES model_route(id),
    actor_id VARCHAR(160) NOT NULL,
    trace_id VARCHAR(80) NOT NULL UNIQUE,
    estimated_cost_micros BIGINT NOT NULL CHECK (estimated_cost_micros >= 0),
    actual_cost_micros BIGINT CHECK (actual_cost_micros IS NULL OR actual_cost_micros >= 0),
    status VARCHAR(24) NOT NULL CHECK (status IN ('RESERVED', 'SUCCEEDED', 'FAILED')),
    created_at TIMESTAMPTZ NOT NULL,
    settled_at TIMESTAMPTZ,
    CONSTRAINT fk_execution_reservation_workspace_scope
        FOREIGN KEY (workspace_id, tenant_id) REFERENCES workspace(id, tenant_id)
);

CREATE TABLE retention_policy (
    workspace_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    run_retention_days INTEGER NOT NULL CHECK (run_retention_days BETWEEN 1 AND 3650),
    audit_retention_days INTEGER NOT NULL CHECK (audit_retention_days BETWEEN 30 AND 3650),
    retain_payloads BOOLEAN NOT NULL,
    mask_sensitive_fields BOOLEAN NOT NULL,
    version BIGINT NOT NULL CHECK (version > 0),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_retention_policy_workspace_scope
        FOREIGN KEY (workspace_id, tenant_id) REFERENCES workspace(id, tenant_id)
);

CREATE TABLE audit_event (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    workspace_id UUID NOT NULL REFERENCES workspace(id),
    occurred_at TIMESTAMPTZ NOT NULL,
    actor_id VARCHAR(160) NOT NULL,
    action VARCHAR(120) NOT NULL,
    resource_type VARCHAR(80) NOT NULL,
    resource_id VARCHAR(240),
    outcome VARCHAR(24) NOT NULL CHECK (outcome IN ('SUCCEEDED', 'DENIED', 'FAILED')),
    source_ip VARCHAR(64),
    trace_id VARCHAR(80),
    details JSONB NOT NULL DEFAULT '{}'::jsonb,
    CONSTRAINT fk_audit_event_workspace_scope
        FOREIGN KEY (workspace_id, tenant_id) REFERENCES workspace(id, tenant_id)
);

ALTER TABLE ai_run
    ADD COLUMN model_route_id UUID,
    ADD COLUMN actor_id VARCHAR(160) NOT NULL DEFAULT 'system',
    ADD COLUMN governance_reservation_id UUID,
    ADD CONSTRAINT fk_ai_run_model_route_scope
        FOREIGN KEY (model_route_id, tenant_id, workspace_id)
        REFERENCES model_route(id, tenant_id, workspace_id),
    ADD CONSTRAINT fk_ai_run_governance_reservation
        FOREIGN KEY (governance_reservation_id) REFERENCES execution_reservation(id);

CREATE INDEX idx_budget_policy_workspace ON budget_policy(workspace_id, enabled);
CREATE INDEX idx_execution_reservation_budget ON execution_reservation(workspace_id, created_at, status);
CREATE INDEX idx_audit_event_workspace_occurred ON audit_event(workspace_id, occurred_at DESC);
CREATE INDEX idx_ai_run_route_created ON ai_run(model_route_id, created_at DESC);

INSERT INTO budget_policy (
    id, tenant_id, workspace_id, name, scope_type, scope_id,
    monthly_cost_limit_micros, requests_per_minute, enabled, created_at, updated_at
) VALUES (
    '00000000-0000-0000-0000-000000005001',
    '00000000-0000-0000-0000-000000000001',
    '00000000-0000-0000-0000-000000000101',
    'Default workspace guardrail', 'WORKSPACE', NULL,
    100000000, 1000, TRUE, now(), now()
);

INSERT INTO retention_policy (
    workspace_id, tenant_id, run_retention_days, audit_retention_days,
    retain_payloads, mask_sensitive_fields, version, created_at, updated_at
) VALUES (
    '00000000-0000-0000-0000-000000000101',
    '00000000-0000-0000-0000-000000000001',
    90, 365, TRUE, TRUE, 1, now(), now()
);

COMMENT ON TABLE budget_policy IS 'Tenant-scoped pre-execution cost and request-rate guardrails.';
COMMENT ON TABLE execution_reservation IS 'Atomic pre-call budget reservations settled with actual model cost.';
COMMENT ON TABLE audit_event IS 'Append-only administrative and policy decision ledger; request bodies and secrets are excluded.';
COMMENT ON TABLE retention_policy IS 'Versioned workspace policy controlling run payload retention and masking.';
