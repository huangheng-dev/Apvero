ALTER TABLE knowledge_source
    ADD CONSTRAINT uq_knowledge_source_base_scope
        UNIQUE (id, tenant_id, workspace_id, knowledge_base_id);

ALTER TABLE knowledge_chunk
    ADD CONSTRAINT uq_knowledge_chunk_revision_lineage
        UNIQUE (id, tenant_id, workspace_id, document_id, source_revision_id);

ALTER TABLE execution_reservation
    ALTER COLUMN application_id DROP NOT NULL,
    ADD COLUMN subject_type VARCHAR(32) NOT NULL DEFAULT 'APPLICATION_RUN',
    ADD COLUMN subject_id UUID;

UPDATE execution_reservation
SET subject_id = application_id
WHERE subject_id IS NULL;

ALTER TABLE execution_reservation
    ALTER COLUMN subject_id SET NOT NULL,
    ADD CONSTRAINT ck_execution_reservation_subject_type
        CHECK (subject_type IN ('APPLICATION_RUN', 'KNOWLEDGE_INGESTION', 'KNOWLEDGE_QUERY')),
    ADD CONSTRAINT ck_execution_reservation_subject_shape
        CHECK ((subject_type = 'APPLICATION_RUN' AND application_id IS NOT NULL
                AND subject_id = application_id)
            OR (subject_type IN ('KNOWLEDGE_INGESTION', 'KNOWLEDGE_QUERY')
                AND application_id IS NULL)),
    ADD CONSTRAINT fk_execution_reservation_application_scope
        FOREIGN KEY (application_id, tenant_id, workspace_id)
        REFERENCES ai_application(id, tenant_id, workspace_id),
    ADD CONSTRAINT fk_execution_reservation_route_scope
        FOREIGN KEY (model_route_id, tenant_id, workspace_id)
        REFERENCES model_route(id, tenant_id, workspace_id),
    ADD CONSTRAINT uq_execution_reservation_full_scope
        UNIQUE (id, tenant_id, workspace_id);

CREATE TABLE retrieval_policy_version (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    workspace_id UUID NOT NULL,
    slug VARCHAR(80) NOT NULL,
    version VARCHAR(64) NOT NULL,
    retrieval_algorithm_version VARCHAR(120) NOT NULL,
    token_estimator_version VARCHAR(120) NOT NULL,
    retention_policy_version_at_publish BIGINT NOT NULL,
    top_k INTEGER NOT NULL,
    maximum_context_input_units INTEGER NOT NULL,
    minimum_score NUMERIC(7,6) NOT NULL,
    overlap_behavior VARCHAR(32) NOT NULL,
    no_evidence_behavior VARCHAR(32) NOT NULL DEFAULT 'NO_EVIDENCE',
    policy_digest CHAR(71) NOT NULL,
    created_by VARCHAR(160) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_retrieval_policy_workspace_scope
        FOREIGN KEY (workspace_id, tenant_id) REFERENCES workspace(id, tenant_id),
    CONSTRAINT ck_retrieval_policy_slug
        CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    CONSTRAINT ck_retrieval_policy_version
        CHECK (version ~ '^[0-9]+[.][0-9]+[.][0-9]+([+-][0-9A-Za-z.-]+)?$'),
    CONSTRAINT ck_retrieval_policy_algorithm
        CHECK (char_length(retrieval_algorithm_version) BETWEEN 1 AND 120),
    CONSTRAINT ck_retrieval_policy_estimator
        CHECK (char_length(token_estimator_version) BETWEEN 1 AND 120),
    CONSTRAINT ck_retrieval_policy_retention_version
        CHECK (retention_policy_version_at_publish > 0),
    CONSTRAINT ck_retrieval_policy_top_k
        CHECK (top_k BETWEEN 1 AND 100),
    CONSTRAINT ck_retrieval_policy_context_units
        CHECK (maximum_context_input_units BETWEEN 1 AND 2000000),
    CONSTRAINT ck_retrieval_policy_score
        CHECK (minimum_score BETWEEN 0 AND 1),
    CONSTRAINT ck_retrieval_policy_overlap
        CHECK (overlap_behavior IN ('KEEP', 'COLLAPSE_ADJACENT')),
    CONSTRAINT ck_retrieval_policy_no_evidence
        CHECK (no_evidence_behavior = 'NO_EVIDENCE'),
    CONSTRAINT ck_retrieval_policy_digest
        CHECK (policy_digest ~ '^sha256:[a-f0-9]{64}$'),
    CONSTRAINT ck_retrieval_policy_created_by
        CHECK (char_length(created_by) BETWEEN 1 AND 160),
    CONSTRAINT uq_retrieval_policy_workspace_slug_version
        UNIQUE (workspace_id, slug, version),
    CONSTRAINT uq_retrieval_policy_workspace_digest
        UNIQUE (workspace_id, policy_digest),
    CONSTRAINT uq_retrieval_policy_full_scope
        UNIQUE (id, tenant_id, workspace_id)
);

CREATE INDEX idx_retrieval_policy_workspace_created
    ON retrieval_policy_version(workspace_id, created_at DESC);

CREATE TABLE knowledge_index (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    workspace_id UUID NOT NULL,
    knowledge_base_id UUID NOT NULL,
    slug VARCHAR(80) NOT NULL,
    name VARCHAR(160) NOT NULL,
    status VARCHAR(24) NOT NULL,
    metadata_version BIGINT NOT NULL DEFAULT 1,
    version_count INTEGER NOT NULL DEFAULT 0,
    latest_ready_version_id UUID,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_knowledge_index_base_scope
        FOREIGN KEY (knowledge_base_id, tenant_id, workspace_id)
        REFERENCES knowledge_base(id, tenant_id, workspace_id),
    CONSTRAINT ck_knowledge_index_slug
        CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    CONSTRAINT ck_knowledge_index_name
        CHECK (char_length(name) BETWEEN 1 AND 160),
    CONSTRAINT ck_knowledge_index_status
        CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    CONSTRAINT ck_knowledge_index_metadata_version
        CHECK (metadata_version > 0),
    CONSTRAINT ck_knowledge_index_version_count
        CHECK (version_count >= 0),
    CONSTRAINT ck_knowledge_index_timestamps
        CHECK (updated_at >= created_at),
    CONSTRAINT uq_knowledge_index_workspace_slug
        UNIQUE (workspace_id, slug),
    CONSTRAINT uq_knowledge_index_full_scope
        UNIQUE (id, tenant_id, workspace_id),
    CONSTRAINT uq_knowledge_index_base_scope
        UNIQUE (id, tenant_id, workspace_id, knowledge_base_id)
);

CREATE INDEX idx_knowledge_index_workspace_updated
    ON knowledge_index(workspace_id, updated_at DESC);
CREATE INDEX idx_knowledge_index_base_updated
    ON knowledge_index(workspace_id, knowledge_base_id, updated_at DESC);

CREATE TABLE knowledge_index_build (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    workspace_id UUID NOT NULL,
    knowledge_index_id UUID NOT NULL,
    knowledge_base_id UUID NOT NULL,
    requested_version VARCHAR(64) NOT NULL,
    embedding_route_id UUID NOT NULL,
    embedding_route_reference VARCHAR(240) NOT NULL,
    vector_dimension INTEGER NOT NULL,
    maximum_input_tokens INTEGER NOT NULL,
    maximum_batch_size INTEGER NOT NULL,
    normalization VARCHAR(16) NOT NULL,
    request_digest CHAR(71) NOT NULL,
    source_set_digest CHAR(71) NOT NULL,
    requested_source_count INTEGER NOT NULL,
    requested_chunk_count INTEGER NOT NULL,
    status VARCHAR(24) NOT NULL,
    current_step VARCHAR(24) NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    maximum_attempts INTEGER NOT NULL DEFAULT 3,
    retryable BOOLEAN NOT NULL DEFAULT FALSE,
    next_attempt_at TIMESTAMPTZ,
    lease_owner VARCHAR(200),
    lease_until TIMESTAMPTZ,
    lock_version BIGINT NOT NULL DEFAULT 1,
    cancellation_requested BOOLEAN NOT NULL DEFAULT FALSE,
    embedded_entry_count INTEGER NOT NULL DEFAULT 0,
    validated_entry_count INTEGER NOT NULL DEFAULT 0,
    last_durable_chunk_ordinal INTEGER,
    validation_digest CHAR(71),
    artifact_digest CHAR(71),
    published_version_id UUID,
    error_code VARCHAR(120),
    error_category VARCHAR(40),
    reconciliation_required BOOLEAN NOT NULL DEFAULT FALSE,
    failure_metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_knowledge_index_build_index_scope
        FOREIGN KEY (knowledge_index_id, tenant_id, workspace_id, knowledge_base_id)
        REFERENCES knowledge_index(id, tenant_id, workspace_id, knowledge_base_id),
    CONSTRAINT fk_knowledge_index_build_route_scope
        FOREIGN KEY (embedding_route_id, tenant_id, workspace_id)
        REFERENCES model_route(id, tenant_id, workspace_id),
    CONSTRAINT ck_knowledge_index_build_version
        CHECK (requested_version ~ '^[0-9]+[.][0-9]+[.][0-9]+([+-][0-9A-Za-z.-]+)?$'),
    CONSTRAINT ck_knowledge_index_build_route_reference
        CHECK (embedding_route_reference ~ '^.+@[1-9][0-9]*$'),
    CONSTRAINT ck_knowledge_index_build_dimension
        CHECK (vector_dimension BETWEEN 1 AND 16000),
    CONSTRAINT ck_knowledge_index_build_input_limit
        CHECK (maximum_input_tokens BETWEEN 1 AND 2000000),
    CONSTRAINT ck_knowledge_index_build_batch_limit
        CHECK (maximum_batch_size BETWEEN 1 AND 4096),
    CONSTRAINT ck_knowledge_index_build_normalization
        CHECK (normalization IN ('NONE', 'L2')),
    CONSTRAINT ck_knowledge_index_build_request_digest
        CHECK (request_digest ~ '^sha256:[a-f0-9]{64}$'),
    CONSTRAINT ck_knowledge_index_build_source_set_digest
        CHECK (source_set_digest ~ '^sha256:[a-f0-9]{64}$'),
    CONSTRAINT ck_knowledge_index_build_requested_counts
        CHECK (requested_source_count > 0 AND requested_chunk_count > 0),
    CONSTRAINT ck_knowledge_index_build_status
        CHECK (status IN ('QUEUED', 'EMBEDDING', 'INDEXING', 'VALIDATING',
            'READY', 'RETRY_WAIT', 'FAILED', 'CANCELLED')),
    CONSTRAINT ck_knowledge_index_build_step
        CHECK (current_step IN ('EMBEDDING', 'INDEXING', 'VALIDATING', 'COMPLETE')),
    CONSTRAINT ck_knowledge_index_build_status_step
        CHECK ((status = 'QUEUED' AND current_step = 'EMBEDDING')
            OR (status = 'EMBEDDING' AND current_step = 'EMBEDDING')
            OR (status = 'INDEXING' AND current_step = 'INDEXING')
            OR (status = 'VALIDATING' AND current_step = 'VALIDATING')
            OR (status = 'READY' AND current_step = 'COMPLETE')
            OR (status IN ('RETRY_WAIT', 'FAILED', 'CANCELLED')
                AND current_step IN ('EMBEDDING', 'INDEXING', 'VALIDATING'))),
    CONSTRAINT ck_knowledge_index_build_attempts
        CHECK (attempt_count >= 0 AND maximum_attempts > 0
            AND attempt_count <= maximum_attempts),
    CONSTRAINT ck_knowledge_index_build_retry
        CHECK ((status = 'RETRY_WAIT' AND retryable AND next_attempt_at IS NOT NULL)
            OR (status <> 'RETRY_WAIT' AND next_attempt_at IS NULL)),
    CONSTRAINT ck_knowledge_index_build_lease
        CHECK ((lease_owner IS NULL AND lease_until IS NULL)
            OR (lease_owner IS NOT NULL AND char_length(lease_owner) BETWEEN 1 AND 200
                AND lease_until IS NOT NULL)),
    CONSTRAINT ck_knowledge_index_build_lock_version
        CHECK (lock_version > 0),
    CONSTRAINT ck_knowledge_index_build_progress
        CHECK (embedded_entry_count BETWEEN 0 AND requested_chunk_count
            AND validated_entry_count BETWEEN 0 AND embedded_entry_count
            AND (last_durable_chunk_ordinal IS NULL OR last_durable_chunk_ordinal >= 0)),
    CONSTRAINT ck_knowledge_index_build_validation_digest
        CHECK (validation_digest IS NULL OR validation_digest ~ '^sha256:[a-f0-9]{64}$'),
    CONSTRAINT ck_knowledge_index_build_artifact_digest
        CHECK (artifact_digest IS NULL OR artifact_digest ~ '^sha256:[a-f0-9]{64}$'),
    CONSTRAINT ck_knowledge_index_build_error
        CHECK ((error_code IS NULL AND error_category IS NULL)
            OR (error_code IS NOT NULL AND char_length(error_code) BETWEEN 1 AND 120
                AND error_category IN ('VALIDATION', 'SECURITY', 'TRANSIENT',
                    'PERMANENT', 'INTERNAL', 'AMBIGUOUS'))),
    CONSTRAINT ck_knowledge_index_build_reconciliation
        CHECK (NOT reconciliation_required OR error_category = 'AMBIGUOUS'),
    CONSTRAINT ck_knowledge_index_build_failure_metadata
        CHECK (jsonb_typeof(failure_metadata) = 'object'),
    CONSTRAINT ck_knowledge_index_build_publication_shape
        CHECK ((status = 'READY' AND published_version_id IS NOT NULL
                AND artifact_digest IS NOT NULL AND completed_at IS NOT NULL)
            OR (status <> 'READY' AND published_version_id IS NULL)),
    CONSTRAINT ck_knowledge_index_build_terminal_shape
        CHECK ((status IN ('READY', 'FAILED', 'CANCELLED') AND completed_at IS NOT NULL)
            OR (status NOT IN ('READY', 'FAILED', 'CANCELLED') AND completed_at IS NULL)),
    CONSTRAINT ck_knowledge_index_build_timestamps
        CHECK (updated_at >= created_at
            AND (started_at IS NULL OR started_at >= created_at)
            AND (completed_at IS NULL OR completed_at >= created_at)),
    CONSTRAINT uq_knowledge_index_build_version
        UNIQUE (knowledge_index_id, requested_version),
    CONSTRAINT uq_knowledge_index_build_request
        UNIQUE (workspace_id, request_digest),
    CONSTRAINT uq_knowledge_index_build_full_scope
        UNIQUE (id, tenant_id, workspace_id),
    CONSTRAINT uq_knowledge_index_build_index_scope
        UNIQUE (id, tenant_id, workspace_id, knowledge_index_id),
    CONSTRAINT uq_knowledge_index_build_lineage
        UNIQUE (id, tenant_id, workspace_id, knowledge_index_id, knowledge_base_id),
    CONSTRAINT uq_knowledge_index_build_entry_shape
        UNIQUE (id, tenant_id, workspace_id, knowledge_index_id, knowledge_base_id,
            vector_dimension, embedding_route_id, embedding_route_reference),
    CONSTRAINT uq_knowledge_index_build_publication_shape
        UNIQUE (id, tenant_id, workspace_id, knowledge_index_id,
            embedding_route_id, embedding_route_reference, vector_dimension,
            requested_source_count, requested_chunk_count)
);

CREATE INDEX idx_knowledge_index_build_workspace_created
    ON knowledge_index_build(workspace_id, created_at DESC);
CREATE INDEX idx_knowledge_index_build_claim
    ON knowledge_index_build(status, next_attempt_at, lease_until, created_at)
    WHERE status IN ('QUEUED', 'EMBEDDING', 'INDEXING', 'VALIDATING', 'RETRY_WAIT');

CREATE TABLE knowledge_index_build_revision (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    workspace_id UUID NOT NULL,
    knowledge_index_build_id UUID NOT NULL,
    knowledge_index_id UUID NOT NULL,
    knowledge_base_id UUID NOT NULL,
    source_id UUID NOT NULL,
    source_revision_id UUID NOT NULL,
    source_content_digest CHAR(71) NOT NULL,
    parser_version VARCHAR(120) NOT NULL,
    chunker_version VARCHAR(120) NOT NULL,
    source_set_ordinal INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_knowledge_build_revision_build_scope
        FOREIGN KEY (knowledge_index_build_id, tenant_id, workspace_id,
            knowledge_index_id, knowledge_base_id)
        REFERENCES knowledge_index_build(id, tenant_id, workspace_id,
            knowledge_index_id, knowledge_base_id),
    CONSTRAINT fk_knowledge_build_revision_source_scope
        FOREIGN KEY (source_id, tenant_id, workspace_id, knowledge_base_id)
        REFERENCES knowledge_source(id, tenant_id, workspace_id, knowledge_base_id),
    CONSTRAINT fk_knowledge_build_revision_revision_scope
        FOREIGN KEY (source_revision_id, tenant_id, workspace_id, source_id)
        REFERENCES knowledge_source_revision(id, tenant_id, workspace_id, source_id),
    CONSTRAINT ck_knowledge_build_revision_digest
        CHECK (source_content_digest ~ '^sha256:[a-f0-9]{64}$'),
    CONSTRAINT ck_knowledge_build_revision_parser
        CHECK (char_length(parser_version) BETWEEN 1 AND 120),
    CONSTRAINT ck_knowledge_build_revision_chunker
        CHECK (char_length(chunker_version) BETWEEN 1 AND 120),
    CONSTRAINT ck_knowledge_build_revision_ordinal
        CHECK (source_set_ordinal >= 0),
    CONSTRAINT uq_knowledge_build_revision_source
        UNIQUE (knowledge_index_build_id, source_id),
    CONSTRAINT uq_knowledge_build_revision_revision
        UNIQUE (knowledge_index_build_id, source_revision_id),
    CONSTRAINT uq_knowledge_build_revision_ordinal
        UNIQUE (knowledge_index_build_id, source_set_ordinal),
    CONSTRAINT uq_knowledge_build_revision_full_scope
        UNIQUE (id, tenant_id, workspace_id),
    CONSTRAINT uq_knowledge_build_revision_entry_scope
        UNIQUE (knowledge_index_build_id, tenant_id, workspace_id,
            source_id, source_revision_id)
);

CREATE INDEX idx_knowledge_build_revision_order
    ON knowledge_index_build_revision(
        workspace_id, knowledge_index_build_id, source_set_ordinal);

CREATE TABLE knowledge_index_entry (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    workspace_id UUID NOT NULL,
    knowledge_index_build_id UUID NOT NULL,
    knowledge_index_id UUID NOT NULL,
    knowledge_base_id UUID NOT NULL,
    source_id UUID NOT NULL,
    source_revision_id UUID NOT NULL,
    document_id UUID NOT NULL,
    chunk_id UUID NOT NULL,
    entry_ordinal INTEGER NOT NULL,
    embedding vector NOT NULL,
    vector_dimension INTEGER NOT NULL,
    vector_digest CHAR(71) NOT NULL,
    normalized_input_digest CHAR(71) NOT NULL,
    batch_ordinal INTEGER NOT NULL,
    embedding_route_id UUID NOT NULL,
    embedding_route_reference VARCHAR(240) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_knowledge_index_entry_build_scope
        FOREIGN KEY (knowledge_index_build_id, tenant_id, workspace_id,
            knowledge_index_id, knowledge_base_id, vector_dimension,
            embedding_route_id, embedding_route_reference)
        REFERENCES knowledge_index_build(id, tenant_id, workspace_id,
            knowledge_index_id, knowledge_base_id, vector_dimension,
            embedding_route_id, embedding_route_reference),
    CONSTRAINT fk_knowledge_index_entry_build_revision_scope
        FOREIGN KEY (knowledge_index_build_id, tenant_id, workspace_id,
            source_id, source_revision_id)
        REFERENCES knowledge_index_build_revision(knowledge_index_build_id,
            tenant_id, workspace_id, source_id, source_revision_id),
    CONSTRAINT fk_knowledge_index_entry_document_scope
        FOREIGN KEY (document_id, tenant_id, workspace_id, source_revision_id)
        REFERENCES knowledge_document(id, tenant_id, workspace_id, source_revision_id),
    CONSTRAINT fk_knowledge_index_entry_chunk_scope
        FOREIGN KEY (chunk_id, tenant_id, workspace_id, document_id, source_revision_id)
        REFERENCES knowledge_chunk(id, tenant_id, workspace_id, document_id, source_revision_id),
    CONSTRAINT ck_knowledge_index_entry_ordinal
        CHECK (entry_ordinal >= 0),
    CONSTRAINT ck_knowledge_index_entry_dimension
        CHECK (vector_dimension BETWEEN 1 AND 16000
            AND vector_dims(embedding) = vector_dimension),
    CONSTRAINT ck_knowledge_index_entry_vector_norm
        CHECK (vector_norm(embedding) > 0),
    CONSTRAINT ck_knowledge_index_entry_vector_digest
        CHECK (vector_digest ~ '^sha256:[a-f0-9]{64}$'),
    CONSTRAINT ck_knowledge_index_entry_input_digest
        CHECK (normalized_input_digest ~ '^sha256:[a-f0-9]{64}$'),
    CONSTRAINT ck_knowledge_index_entry_batch_ordinal
        CHECK (batch_ordinal >= 0),
    CONSTRAINT ck_knowledge_index_entry_route_reference
        CHECK (embedding_route_reference ~ '^.+@[1-9][0-9]*$'),
    CONSTRAINT uq_knowledge_index_entry_chunk
        UNIQUE (knowledge_index_build_id, chunk_id),
    CONSTRAINT uq_knowledge_index_entry_ordinal
        UNIQUE (knowledge_index_build_id, entry_ordinal),
    CONSTRAINT uq_knowledge_index_entry_full_scope
        UNIQUE (id, tenant_id, workspace_id)
);

CREATE INDEX idx_knowledge_index_entry_order
    ON knowledge_index_entry(workspace_id, knowledge_index_build_id, entry_ordinal);
CREATE INDEX idx_knowledge_index_entry_lineage
    ON knowledge_index_entry(
        workspace_id, source_revision_id, document_id, chunk_id);

CREATE TABLE knowledge_index_version (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    workspace_id UUID NOT NULL,
    knowledge_index_id UUID NOT NULL,
    knowledge_index_build_id UUID NOT NULL,
    version VARCHAR(64) NOT NULL,
    reference VARCHAR(240) NOT NULL,
    embedding_route_id UUID NOT NULL,
    embedding_route_reference VARCHAR(240) NOT NULL,
    vector_dimension INTEGER NOT NULL,
    source_count INTEGER NOT NULL,
    chunk_count INTEGER NOT NULL,
    artifact_digest CHAR(71) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'READY',
    published_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_knowledge_index_version_index_scope
        FOREIGN KEY (knowledge_index_id, tenant_id, workspace_id)
        REFERENCES knowledge_index(id, tenant_id, workspace_id),
    CONSTRAINT fk_knowledge_index_version_build_scope
        FOREIGN KEY (knowledge_index_build_id, tenant_id, workspace_id,
            knowledge_index_id, embedding_route_id, embedding_route_reference,
            vector_dimension, source_count, chunk_count)
        REFERENCES knowledge_index_build(id, tenant_id, workspace_id,
            knowledge_index_id, embedding_route_id, embedding_route_reference,
            vector_dimension, requested_source_count, requested_chunk_count),
    CONSTRAINT fk_knowledge_index_version_route_scope
        FOREIGN KEY (embedding_route_id, tenant_id, workspace_id)
        REFERENCES model_route(id, tenant_id, workspace_id),
    CONSTRAINT ck_knowledge_index_version_semver
        CHECK (version ~ '^[0-9]+[.][0-9]+[.][0-9]+([+-][0-9A-Za-z.-]+)?$'),
    CONSTRAINT ck_knowledge_index_version_reference
        CHECK (reference ~ '^[a-z0-9]+(-[a-z0-9]+)*@[0-9]+[.][0-9]+[.][0-9]+([+-][0-9A-Za-z.-]+)?$'),
    CONSTRAINT ck_knowledge_index_version_route_reference
        CHECK (embedding_route_reference ~ '^.+@[1-9][0-9]*$'),
    CONSTRAINT ck_knowledge_index_version_dimension
        CHECK (vector_dimension BETWEEN 1 AND 16000),
    CONSTRAINT ck_knowledge_index_version_counts
        CHECK (source_count > 0 AND chunk_count > 0),
    CONSTRAINT ck_knowledge_index_version_digest
        CHECK (artifact_digest ~ '^sha256:[a-f0-9]{64}$'),
    CONSTRAINT ck_knowledge_index_version_status
        CHECK (status = 'READY'),
    CONSTRAINT uq_knowledge_index_version_semver
        UNIQUE (knowledge_index_id, version),
    CONSTRAINT uq_knowledge_index_version_reference
        UNIQUE (workspace_id, reference),
    CONSTRAINT uq_knowledge_index_version_build
        UNIQUE (knowledge_index_build_id),
    CONSTRAINT uq_knowledge_index_version_full_scope
        UNIQUE (id, tenant_id, workspace_id),
    CONSTRAINT uq_knowledge_index_version_index_scope
        UNIQUE (id, tenant_id, workspace_id, knowledge_index_id)
);

ALTER TABLE knowledge_index_build
    ADD CONSTRAINT fk_knowledge_index_build_published_version_scope
        FOREIGN KEY (published_version_id, tenant_id, workspace_id, knowledge_index_id)
        REFERENCES knowledge_index_version(id, tenant_id, workspace_id, knowledge_index_id)
        DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE knowledge_index
    ADD CONSTRAINT fk_knowledge_index_latest_version_scope
        FOREIGN KEY (latest_ready_version_id, tenant_id, workspace_id, id)
        REFERENCES knowledge_index_version(id, tenant_id, workspace_id, knowledge_index_id)
        DEFERRABLE INITIALLY DEFERRED;

CREATE INDEX idx_knowledge_index_version_workspace_published
    ON knowledge_index_version(workspace_id, published_at DESC);

CREATE TABLE execution_reservation_component (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    workspace_id UUID NOT NULL,
    reservation_id UUID NOT NULL,
    component_type VARCHAR(32) NOT NULL,
    model_route_id UUID NOT NULL,
    model_route_reference VARCHAR(240) NOT NULL,
    idempotency_identity VARCHAR(200) NOT NULL,
    estimated_units BIGINT NOT NULL,
    actual_units BIGINT,
    usage_quality VARCHAR(24),
    estimated_cost_micros BIGINT NOT NULL,
    actual_cost_micros BIGINT,
    currency CHAR(3) NOT NULL,
    status VARCHAR(32) NOT NULL,
    provider_request_identity VARCHAR(240),
    failure_code VARCHAR(120),
    dispatched_at TIMESTAMPTZ,
    settled_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_execution_component_reservation_scope
        FOREIGN KEY (reservation_id, tenant_id, workspace_id)
        REFERENCES execution_reservation(id, tenant_id, workspace_id),
    CONSTRAINT fk_execution_component_route_scope
        FOREIGN KEY (model_route_id, tenant_id, workspace_id)
        REFERENCES model_route(id, tenant_id, workspace_id),
    CONSTRAINT ck_execution_component_type
        CHECK (component_type IN ('CHAT_GENERATION', 'EMBEDDING_INDEX', 'EMBEDDING_QUERY')),
    CONSTRAINT ck_execution_component_route_reference
        CHECK (model_route_reference ~ '^.+@[1-9][0-9]*$'),
    CONSTRAINT ck_execution_component_idempotency
        CHECK (char_length(idempotency_identity) BETWEEN 1 AND 200),
    CONSTRAINT ck_execution_component_estimated_units
        CHECK (estimated_units >= 0),
    CONSTRAINT ck_execution_component_actual_units
        CHECK (actual_units IS NULL OR actual_units >= 0),
    CONSTRAINT ck_execution_component_usage_quality
        CHECK (usage_quality IS NULL OR usage_quality IN ('ACTUAL', 'ESTIMATED')),
    CONSTRAINT ck_execution_component_cost
        CHECK (estimated_cost_micros >= 0
            AND (actual_cost_micros IS NULL OR actual_cost_micros >= 0)),
    CONSTRAINT ck_execution_component_currency
        CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_execution_component_status
        CHECK (status IN ('RESERVED', 'DISPATCHED', 'SUCCEEDED', 'FAILED',
            'RECONCILIATION_REQUIRED')),
    CONSTRAINT ck_execution_component_provider_identity
        CHECK (provider_request_identity IS NULL
            OR char_length(provider_request_identity) BETWEEN 1 AND 240),
    CONSTRAINT ck_execution_component_failure
        CHECK (failure_code IS NULL OR char_length(failure_code) BETWEEN 1 AND 120),
    CONSTRAINT ck_execution_component_dispatch_shape
        CHECK ((status = 'RESERVED' AND dispatched_at IS NULL)
            OR (status <> 'RESERVED' AND dispatched_at IS NOT NULL)),
    CONSTRAINT ck_execution_component_settlement_shape
        CHECK ((status IN ('SUCCEEDED', 'FAILED') AND settled_at IS NOT NULL
                AND actual_cost_micros IS NOT NULL AND usage_quality IS NOT NULL)
            OR (status NOT IN ('SUCCEEDED', 'FAILED') AND settled_at IS NULL)),
    CONSTRAINT ck_execution_component_failure_shape
        CHECK ((status IN ('FAILED', 'RECONCILIATION_REQUIRED') AND failure_code IS NOT NULL)
            OR (status NOT IN ('FAILED', 'RECONCILIATION_REQUIRED') AND failure_code IS NULL)),
    CONSTRAINT ck_execution_component_timestamps
        CHECK (updated_at >= created_at
            AND (dispatched_at IS NULL OR dispatched_at >= created_at)
            AND (settled_at IS NULL OR settled_at >= created_at)),
    CONSTRAINT uq_execution_component_idempotency
        UNIQUE (reservation_id, idempotency_identity),
    CONSTRAINT uq_execution_component_full_scope
        UNIQUE (id, tenant_id, workspace_id)
);

CREATE INDEX idx_execution_component_reservation
    ON execution_reservation_component(workspace_id, reservation_id, created_at);
CREATE INDEX idx_execution_component_reconciliation
    ON execution_reservation_component(workspace_id, status, updated_at)
    WHERE status IN ('DISPATCHED', 'RECONCILIATION_REQUIRED');

CREATE OR REPLACE FUNCTION reject_p2_2_immutable_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION '% is immutable; create a new version instead', TG_TABLE_NAME
        USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER retrieval_policy_version_is_insert_only
BEFORE UPDATE OR DELETE ON retrieval_policy_version
FOR EACH ROW EXECUTE FUNCTION reject_p2_2_immutable_mutation();

CREATE TRIGGER knowledge_index_build_revision_is_insert_only
BEFORE UPDATE OR DELETE ON knowledge_index_build_revision
FOR EACH ROW EXECUTE FUNCTION reject_p2_2_immutable_mutation();

CREATE TRIGGER knowledge_index_entry_is_insert_only
BEFORE UPDATE OR DELETE ON knowledge_index_entry
FOR EACH ROW EXECUTE FUNCTION reject_p2_2_immutable_mutation();

CREATE TRIGGER knowledge_index_version_is_insert_only
BEFORE UPDATE OR DELETE ON knowledge_index_version
FOR EACH ROW EXECUTE FUNCTION reject_p2_2_immutable_mutation();

CREATE OR REPLACE FUNCTION guard_knowledge_index_entry_insert()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM knowledge_index_build build
        WHERE build.id = NEW.knowledge_index_build_id
          AND build.tenant_id = NEW.tenant_id
          AND build.workspace_id = NEW.workspace_id
          AND (build.status = 'READY' OR build.published_version_id IS NOT NULL)
    ) THEN
        RAISE EXCEPTION 'published knowledge index build cannot accept new entries'
            USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER knowledge_index_entry_rejects_published_build
BEFORE INSERT ON knowledge_index_entry
FOR EACH ROW EXECUTE FUNCTION guard_knowledge_index_entry_insert();

CREATE OR REPLACE FUNCTION guard_knowledge_index_build_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'knowledge_index_build is durable and cannot be deleted'
            USING ERRCODE = '55000';
    END IF;
    IF OLD.status = 'READY' OR OLD.published_version_id IS NOT NULL THEN
        RAISE EXCEPTION 'published knowledge_index_build is immutable'
            USING ERRCODE = '55000';
    END IF;
    IF (NEW.tenant_id, NEW.workspace_id, NEW.knowledge_index_id,
        NEW.knowledge_base_id, NEW.requested_version, NEW.embedding_route_id,
        NEW.embedding_route_reference, NEW.vector_dimension,
        NEW.maximum_input_tokens, NEW.maximum_batch_size, NEW.normalization,
        NEW.request_digest, NEW.source_set_digest, NEW.requested_source_count,
        NEW.requested_chunk_count, NEW.created_at)
       IS DISTINCT FROM
       (OLD.tenant_id, OLD.workspace_id, OLD.knowledge_index_id,
        OLD.knowledge_base_id, OLD.requested_version, OLD.embedding_route_id,
        OLD.embedding_route_reference, OLD.vector_dimension,
        OLD.maximum_input_tokens, OLD.maximum_batch_size, OLD.normalization,
        OLD.request_digest, OLD.source_set_digest, OLD.requested_source_count,
        OLD.requested_chunk_count, OLD.created_at) THEN
        RAISE EXCEPTION 'knowledge_index_build request identity is immutable'
            USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER knowledge_index_build_preserves_durable_state
BEFORE UPDATE OR DELETE ON knowledge_index_build
FOR EACH ROW EXECUTE FUNCTION guard_knowledge_index_build_mutation();

CREATE OR REPLACE FUNCTION validate_knowledge_index_build_route()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM model_route route
        WHERE route.id = NEW.embedding_route_id
          AND route.tenant_id = NEW.tenant_id
          AND route.workspace_id = NEW.workspace_id
          AND route.route_capability = 'EMBEDDING'
          AND route.name || '@' || route.version = NEW.embedding_route_reference
          AND route.embedding_dimension = NEW.vector_dimension
          AND route.embedding_maximum_input_tokens = NEW.maximum_input_tokens
          AND route.embedding_maximum_batch_size = NEW.maximum_batch_size
          AND route.embedding_normalization = NEW.normalization
    ) THEN
        RAISE EXCEPTION 'knowledge_index_build route profile does not match the pinned embedding route'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER knowledge_index_build_validates_route_profile
BEFORE INSERT ON knowledge_index_build
FOR EACH ROW EXECUTE FUNCTION validate_knowledge_index_build_route();

CREATE OR REPLACE FUNCTION validate_knowledge_build_revision_snapshot()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM knowledge_source source
        JOIN knowledge_source_revision revision
          ON revision.source_id = source.id
         AND revision.tenant_id = source.tenant_id
         AND revision.workspace_id = source.workspace_id
        WHERE source.id = NEW.source_id
          AND source.tenant_id = NEW.tenant_id
          AND source.workspace_id = NEW.workspace_id
          AND source.knowledge_base_id = NEW.knowledge_base_id
          AND source.status = 'ACTIVE'
          AND revision.id = NEW.source_revision_id
          AND revision.content_digest = NEW.source_content_digest
          AND EXISTS (
              SELECT 1
              FROM knowledge_ingestion_job job
              WHERE job.tenant_id = NEW.tenant_id
                AND job.workspace_id = NEW.workspace_id
                AND job.source_id = NEW.source_id
                AND job.source_revision_id = NEW.source_revision_id
                AND job.status = 'READY'
                AND job.current_step = 'COMPLETE'
          )
          AND EXISTS (
              SELECT 1
              FROM knowledge_document document
              WHERE document.tenant_id = NEW.tenant_id
                AND document.workspace_id = NEW.workspace_id
                AND document.source_revision_id = NEW.source_revision_id
          )
          AND NOT EXISTS (
              SELECT 1
              FROM knowledge_document document
              WHERE document.tenant_id = NEW.tenant_id
                AND document.workspace_id = NEW.workspace_id
                AND document.source_revision_id = NEW.source_revision_id
                AND document.parser_version <> NEW.parser_version
          )
          AND EXISTS (
              SELECT 1
              FROM knowledge_chunk chunk
              WHERE chunk.tenant_id = NEW.tenant_id
                AND chunk.workspace_id = NEW.workspace_id
                AND chunk.source_revision_id = NEW.source_revision_id
          )
          AND NOT EXISTS (
              SELECT 1
              FROM knowledge_chunk chunk
              WHERE chunk.tenant_id = NEW.tenant_id
                AND chunk.workspace_id = NEW.workspace_id
                AND chunk.source_revision_id = NEW.source_revision_id
                AND chunk.chunker_version <> NEW.chunker_version
          )
    ) THEN
        RAISE EXCEPTION 'knowledge_index_build_revision is not a READY active and version-consistent source snapshot'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER knowledge_index_build_revision_validates_snapshot
BEFORE INSERT ON knowledge_index_build_revision
FOR EACH ROW EXECUTE FUNCTION validate_knowledge_build_revision_snapshot();

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
    IF OLD.status IN ('SUCCEEDED', 'FAILED', 'RECONCILIATION_REQUIRED') THEN
        RAISE EXCEPTION 'settled execution_reservation_component is immutable'
            USING ERRCODE = '55000';
    END IF;
    IF NOT ((OLD.status = 'RESERVED' AND NEW.status IN ('RESERVED', 'DISPATCHED'))
        OR (OLD.status = 'DISPATCHED'
            AND NEW.status IN ('DISPATCHED', 'SUCCEEDED', 'FAILED',
                'RECONCILIATION_REQUIRED'))) THEN
        RAISE EXCEPTION 'invalid execution_reservation_component transition'
            USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER execution_reservation_component_transition_guard
BEFORE UPDATE OR DELETE ON execution_reservation_component
FOR EACH ROW EXECUTE FUNCTION guard_execution_component_transition();

COMMENT ON TABLE retrieval_policy_version IS
    'Immutable workspace-scoped Retrieval Lab policy; product exposure remains disabled.';
COMMENT ON TABLE knowledge_index IS
    'Stable workspace-scoped knowledge index identity; latest version is display metadata only.';
COMMENT ON TABLE knowledge_index_build IS
    'Durable scoped index build state; failed unpublished builds remain inspectable.';
COMMENT ON TABLE knowledge_index_build_revision IS
    'Immutable exact source revision set pinned by one index build.';
COMMENT ON TABLE knowledge_index_entry IS
    'Insert-only pgvector entry with complete scoped source lineage and route identity.';
COMMENT ON TABLE knowledge_index_version IS
    'Immutable READY knowledge index artifact version; retrieval must use an exact version.';
COMMENT ON TABLE execution_reservation_component IS
    'Governance-owned idempotent component transition ledger for chat and embedding execution.';
