ALTER TABLE ai_run
    ADD COLUMN failure_code VARCHAR(96)
        CHECK (failure_code IS NULL OR failure_code ~ '^APVERO_[A-Z0-9_]+$'),
    ADD CONSTRAINT ck_ai_run_lifecycle_status
        CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED')),
    ADD CONSTRAINT uq_ai_run_full_scope UNIQUE (id, tenant_id, workspace_id);

ALTER TABLE execution_reservation_component
    DROP CONSTRAINT ck_execution_component_status,
    DROP CONSTRAINT ck_execution_component_dispatch_shape,
    DROP CONSTRAINT ck_execution_component_settlement_shape,
    DROP CONSTRAINT ck_execution_component_failure_shape,
    ADD CONSTRAINT ck_execution_component_status
        CHECK (status IN ('RESERVED', 'DISPATCHED', 'SUCCEEDED', 'FAILED',
            'RELEASED', 'RECONCILIATION_REQUIRED')),
    ADD CONSTRAINT ck_execution_component_dispatch_shape
        CHECK ((status IN ('RESERVED', 'RELEASED') AND dispatched_at IS NULL)
            OR (status NOT IN ('RESERVED', 'RELEASED') AND dispatched_at IS NOT NULL)),
    ADD CONSTRAINT ck_execution_component_settlement_shape
        CHECK ((status IN ('SUCCEEDED', 'FAILED', 'RELEASED')
                AND settled_at IS NOT NULL
                AND actual_cost_micros IS NOT NULL
                AND usage_quality IS NOT NULL)
            OR (status NOT IN ('SUCCEEDED', 'FAILED', 'RELEASED')
                AND settled_at IS NULL)),
    ADD CONSTRAINT ck_execution_component_failure_shape
        CHECK ((status IN ('FAILED', 'RELEASED', 'RECONCILIATION_REQUIRED')
                AND failure_code IS NOT NULL)
            OR (status NOT IN ('FAILED', 'RELEASED', 'RECONCILIATION_REQUIRED')
                AND failure_code IS NULL)),
    ADD CONSTRAINT ck_execution_component_release_shape
        CHECK (status <> 'RELEASED'
            OR (actual_units = 0 AND actual_cost_micros = 0));

CREATE OR REPLACE FUNCTION guard_execution_component_transition()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'execution_reservation_component is durable and cannot be deleted'
            USING ERRCODE = '55000';
    END IF;
    IF (NEW.tenant_id, NEW.workspace_id, NEW.reservation_id, NEW.component_type,
        NEW.model_route_id, NEW.model_route_reference, NEW.idempotency_identity,
        NEW.estimated_units, NEW.estimated_cost_micros, NEW.currency, NEW.created_at)
       IS DISTINCT FROM
       (OLD.tenant_id, OLD.workspace_id, OLD.reservation_id, OLD.component_type,
        OLD.model_route_id, OLD.model_route_reference, OLD.idempotency_identity,
        OLD.estimated_units, OLD.estimated_cost_micros, OLD.currency, OLD.created_at) THEN
        RAISE EXCEPTION 'execution_reservation_component identity is immutable'
            USING ERRCODE = '55000';
    END IF;
    IF OLD.status IN ('SUCCEEDED', 'FAILED', 'RELEASED', 'RECONCILIATION_REQUIRED') THEN
        RAISE EXCEPTION 'settled execution_reservation_component is immutable'
            USING ERRCODE = '55000';
    END IF;
    IF NOT ((OLD.status = 'RESERVED'
                AND NEW.status IN ('RESERVED', 'DISPATCHED', 'RELEASED'))
        OR (OLD.status = 'DISPATCHED'
            AND NEW.status IN ('DISPATCHED', 'SUCCEEDED', 'FAILED',
                'RECONCILIATION_REQUIRED'))) THEN
        RAISE EXCEPTION 'invalid execution_reservation_component transition'
            USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TABLE ai_run_retrieval (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    sequence INTEGER NOT NULL CHECK (sequence BETWEEN 0 AND 15),
    index_version_id UUID NOT NULL,
    index_version_reference VARCHAR(240) NOT NULL
        CHECK (index_version_reference ~ '^[a-z0-9][a-z0-9._:/-]*@[0-9]+\.[0-9]+\.[0-9]+(-[a-z0-9.-]+)?$'),
    retrieval_policy_version_id UUID NOT NULL,
    retrieval_policy_version_reference VARCHAR(240) NOT NULL
        CHECK (retrieval_policy_version_reference ~ '^[a-z0-9][a-z0-9._:/-]*@[0-9]+\.[0-9]+\.[0-9]+(-[a-z0-9.-]+)?$'),
    query_digest CHAR(71) NOT NULL CHECK (query_digest ~ '^sha256:[a-f0-9]{64}$'),
    status VARCHAR(24) NOT NULL CHECK (status IN ('MATCHES', 'NO_EVIDENCE')),
    hit_count INTEGER NOT NULL CHECK (hit_count BETWEEN 0 AND 100),
    latency_ms BIGINT NOT NULL CHECK (latency_ms >= 0),
    retention_decision_version BIGINT NOT NULL CHECK (retention_decision_version >= 1),
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_run_retrieval_run_scope
        FOREIGN KEY (run_id, tenant_id, workspace_id)
        REFERENCES ai_run(id, tenant_id, workspace_id) ON DELETE CASCADE,
    CONSTRAINT ck_run_retrieval_status_count
        CHECK ((status = 'MATCHES' AND hit_count > 0)
            OR (status = 'NO_EVIDENCE' AND hit_count = 0)),
    UNIQUE (run_id, sequence),
    UNIQUE (id, run_id, tenant_id, workspace_id)
);

CREATE TABLE ai_run_retrieval_hit (
    id UUID PRIMARY KEY,
    retrieval_id UUID NOT NULL,
    run_id UUID NOT NULL,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    marker VARCHAR(16) NOT NULL CHECK (marker ~ '^\[K[1-9][0-9]*\]$'),
    rank INTEGER NOT NULL CHECK (rank BETWEEN 1 AND 100),
    score NUMERIC(12, 11) NOT NULL CHECK (score BETWEEN 0 AND 1),
    source_id UUID NOT NULL,
    source_revision_id UUID NOT NULL,
    document_id UUID NOT NULL,
    chunk_id UUID NOT NULL,
    content_digest CHAR(71) NOT NULL CHECK (content_digest ~ '^sha256:[a-f0-9]{64}$'),
    retained_content VARCHAR(20000),
    source_title VARCHAR(500),
    source_type VARCHAR(24) NOT NULL CHECK (source_type IN ('TEXT', 'MARKDOWN', 'PDF', 'DOCX', 'WEB')),
    page INTEGER CHECK (page >= 1),
    heading VARCHAR(1000),
    paragraph INTEGER CHECK (paragraph >= 1),
    line_start INTEGER CHECK (line_start >= 1),
    line_end INTEGER CHECK (line_end >= line_start),
    citation_validated BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_run_retrieval_hit_execution_scope
        FOREIGN KEY (retrieval_id, run_id, tenant_id, workspace_id)
        REFERENCES ai_run_retrieval(id, run_id, tenant_id, workspace_id) ON DELETE CASCADE,
    CONSTRAINT ck_run_retrieval_hit_line_anchor
        CHECK ((line_start IS NULL AND line_end IS NULL)
            OR (line_start IS NOT NULL AND line_end IS NOT NULL)),
    UNIQUE (run_id, marker),
    UNIQUE (retrieval_id, rank)
);

CREATE INDEX idx_run_retrieval_workspace_run
    ON ai_run_retrieval(workspace_id, run_id, sequence);
CREATE INDEX idx_run_retrieval_hit_workspace_run
    ON ai_run_retrieval_hit(workspace_id, run_id, rank);

CREATE OR REPLACE FUNCTION protect_ai_run_lifecycle()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'DELETE' AND current_setting('apvero.retention_purge', true) = 'on' THEN
        RETURN OLD;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'ai_run may be deleted only by the controlled retention purge';
    END IF;
    IF OLD.status <> 'RUNNING' THEN
        RAISE EXCEPTION 'terminal ai_run is immutable';
    END IF;
    IF NEW.id <> OLD.id
        OR NEW.tenant_id <> OLD.tenant_id
        OR NEW.workspace_id <> OLD.workspace_id
        OR NEW.application_id <> OLD.application_id
        OR NEW.release_bundle_id <> OLD.release_bundle_id
        OR NEW.actor_id <> OLD.actor_id
        OR NEW.input <> OLD.input
        OR NEW.trace_id <> OLD.trace_id
        OR NEW.created_at <> OLD.created_at THEN
        RAISE EXCEPTION 'ai_run identity and retained input are immutable';
    END IF;
    IF NEW.status NOT IN ('RUNNING', 'SUCCEEDED', 'FAILED') THEN
        RAISE EXCEPTION 'ai_run lifecycle transition is invalid';
    END IF;
    IF NEW.status = 'RUNNING'
        AND (NEW.output <> OLD.output
            OR NEW.latency_ms <> OLD.latency_ms
            OR NEW.prompt_tokens <> OLD.prompt_tokens
            OR NEW.completion_tokens <> OLD.completion_tokens
            OR NEW.cost_micros <> OLD.cost_micros
            OR NEW.failure_code IS DISTINCT FROM OLD.failure_code
            OR NEW.failure_category IS DISTINCT FROM OLD.failure_category
            OR NEW.failure_message IS DISTINCT FROM OLD.failure_message) THEN
        RAISE EXCEPTION 'running ai_run may only attach execution identity';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_protect_ai_run_lifecycle
BEFORE UPDATE OR DELETE ON ai_run
FOR EACH ROW EXECUTE FUNCTION protect_ai_run_lifecycle();

CREATE OR REPLACE FUNCTION protect_run_retrieval_evidence()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'DELETE' AND current_setting('apvero.retention_purge', true) = 'on' THEN
        RETURN OLD;
    END IF;
    RAISE EXCEPTION '% is append-only; only the controlled retention purge may delete rows', TG_TABLE_NAME;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_protect_run_retrieval
BEFORE UPDATE OR DELETE ON ai_run_retrieval
FOR EACH ROW EXECUTE FUNCTION protect_run_retrieval_evidence();

CREATE OR REPLACE FUNCTION protect_run_retrieval_hit()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'DELETE' AND current_setting('apvero.retention_purge', true) = 'on' THEN
        RETURN OLD;
    END IF;
    IF TG_OP = 'UPDATE'
        AND OLD.citation_validated = FALSE
        AND NEW.citation_validated = TRUE
        AND (to_jsonb(NEW) - 'citation_validated') = (to_jsonb(OLD) - 'citation_validated') THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'ai_run_retrieval_hit identity and retained evidence are immutable';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_protect_run_retrieval_hit
BEFORE UPDATE OR DELETE ON ai_run_retrieval_hit
FOR EACH ROW EXECUTE FUNCTION protect_run_retrieval_hit();

CREATE OR REPLACE FUNCTION validate_run_retrieval_evidence()
RETURNS TRIGGER AS $$
DECLARE
    affected_run_id UUID;
    retrieval_count INTEGER;
    marker_count INTEGER;
BEGIN
    affected_run_id := COALESCE(NEW.run_id, OLD.run_id);
    IF NOT EXISTS (SELECT 1 FROM ai_run WHERE id = affected_run_id) THEN
        RETURN NULL;
    END IF;

    SELECT count(*) INTO retrieval_count
    FROM ai_run_retrieval
    WHERE run_id = affected_run_id;

    IF retrieval_count > 0 AND EXISTS (
        SELECT 1
        FROM ai_run_retrieval
        WHERE run_id = affected_run_id
        HAVING min(sequence) <> 0 OR max(sequence) <> retrieval_count - 1
    ) THEN
        RAISE EXCEPTION 'run retrieval sequence must be contiguous from zero';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM ai_run_retrieval retrieval
        LEFT JOIN ai_run_retrieval_hit hit ON hit.retrieval_id = retrieval.id
        WHERE retrieval.run_id = affected_run_id
        GROUP BY retrieval.id, retrieval.status, retrieval.hit_count
        HAVING count(hit.id) <> retrieval.hit_count
            OR (retrieval.status = 'MATCHES' AND count(hit.id) = 0)
            OR (retrieval.status = 'NO_EVIDENCE' AND count(hit.id) <> 0)
            OR (count(hit.id) > 0 AND (min(hit.rank) <> 1 OR max(hit.rank) <> count(hit.id)))
    ) THEN
        RAISE EXCEPTION 'run retrieval hit count, status, or rank order is inconsistent';
    END IF;

    SELECT count(*) INTO marker_count
    FROM ai_run_retrieval_hit
    WHERE run_id = affected_run_id;

    IF marker_count > 0 AND EXISTS (
        SELECT 1
        FROM ai_run_retrieval_hit
        WHERE run_id = affected_run_id
        HAVING min(substring(marker from '[0-9]+')::integer) <> 1
            OR max(substring(marker from '[0-9]+')::integer) <> marker_count
    ) THEN
        RAISE EXCEPTION 'run retrieval markers must be globally contiguous from K1';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER trg_validate_run_retrieval
AFTER INSERT OR UPDATE OR DELETE ON ai_run_retrieval
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION validate_run_retrieval_evidence();

CREATE CONSTRAINT TRIGGER trg_validate_run_retrieval_hit
AFTER INSERT OR UPDATE OR DELETE ON ai_run_retrieval_hit
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION validate_run_retrieval_evidence();

COMMENT ON TABLE ai_run_retrieval IS
    'Immutable, workspace-scoped retrieval execution evidence owned by Runtime.';
COMMENT ON TABLE ai_run_retrieval_hit IS
    'Immutable ordered hit identity and retention-filtered content used by one Run.';
COMMENT ON COLUMN ai_run_retrieval_hit.retained_content IS
    'Nullable content retained only after the active execution retention decision.';
COMMENT ON COLUMN ai_run.failure_code IS
    'Stable machine-readable Runtime failure code; nullable for successful and migrated Runs.';
