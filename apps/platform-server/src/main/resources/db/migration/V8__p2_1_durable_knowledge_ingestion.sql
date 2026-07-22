CREATE TABLE knowledge_base (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    workspace_id UUID NOT NULL,
    slug VARCHAR(80) NOT NULL,
    name VARCHAR(160) NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    status VARCHAR(24) NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_knowledge_base_workspace_scope
        FOREIGN KEY (workspace_id, tenant_id) REFERENCES workspace(id, tenant_id),
    CONSTRAINT ck_knowledge_base_slug
        CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    CONSTRAINT ck_knowledge_base_name
        CHECK (char_length(name) BETWEEN 1 AND 160),
    CONSTRAINT ck_knowledge_base_description
        CHECK (char_length(description) <= 2000),
    CONSTRAINT ck_knowledge_base_status
        CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    CONSTRAINT ck_knowledge_base_version
        CHECK (version > 0),
    CONSTRAINT ck_knowledge_base_timestamps
        CHECK (updated_at >= created_at),
    CONSTRAINT uq_knowledge_base_workspace_slug
        UNIQUE (workspace_id, slug),
    CONSTRAINT uq_knowledge_base_full_scope
        UNIQUE (id, tenant_id, workspace_id)
);

CREATE INDEX idx_knowledge_base_workspace_updated
    ON knowledge_base(workspace_id, updated_at DESC);

CREATE TABLE knowledge_source (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    workspace_id UUID NOT NULL,
    knowledge_base_id UUID NOT NULL,
    name VARCHAR(160) NOT NULL,
    source_type VARCHAR(24) NOT NULL,
    status VARCHAR(24) NOT NULL,
    canonical_web_uri VARCHAR(2048),
    latest_revision_number INTEGER NOT NULL DEFAULT 0,
    latest_revision_id UUID,
    version BIGINT NOT NULL DEFAULT 1,
    tombstoned_at TIMESTAMPTZ,
    tombstoned_by VARCHAR(160),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_knowledge_source_base_scope
        FOREIGN KEY (knowledge_base_id, tenant_id, workspace_id)
        REFERENCES knowledge_base(id, tenant_id, workspace_id),
    CONSTRAINT ck_knowledge_source_name
        CHECK (char_length(name) BETWEEN 1 AND 160),
    CONSTRAINT ck_knowledge_source_type
        CHECK (source_type IN ('TEXT', 'MARKDOWN', 'PDF', 'DOCX', 'WEB')),
    CONSTRAINT ck_knowledge_source_status
        CHECK (status IN ('ACTIVE', 'TOMBSTONED')),
    CONSTRAINT ck_knowledge_source_web_locator
        CHECK ((source_type = 'WEB' AND canonical_web_uri IS NOT NULL)
            OR (source_type <> 'WEB' AND canonical_web_uri IS NULL)),
    CONSTRAINT ck_knowledge_source_latest_revision
        CHECK ((latest_revision_number = 0 AND latest_revision_id IS NULL)
            OR (latest_revision_number > 0 AND latest_revision_id IS NOT NULL)),
    CONSTRAINT ck_knowledge_source_version
        CHECK (version > 0),
    CONSTRAINT ck_knowledge_source_tombstone
        CHECK ((status = 'ACTIVE' AND tombstoned_at IS NULL AND tombstoned_by IS NULL)
            OR (status = 'TOMBSTONED' AND tombstoned_at IS NOT NULL
                AND tombstoned_by IS NOT NULL AND char_length(tombstoned_by) BETWEEN 1 AND 160)),
    CONSTRAINT ck_knowledge_source_timestamps
        CHECK (updated_at >= created_at AND (tombstoned_at IS NULL OR tombstoned_at >= created_at)),
    CONSTRAINT uq_knowledge_source_full_scope
        UNIQUE (id, tenant_id, workspace_id)
);

CREATE INDEX idx_knowledge_source_base_updated
    ON knowledge_source(workspace_id, knowledge_base_id, updated_at DESC);
CREATE INDEX idx_knowledge_source_workspace_status
    ON knowledge_source(workspace_id, status, updated_at DESC);

CREATE TABLE knowledge_source_revision (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    workspace_id UUID NOT NULL,
    source_id UUID NOT NULL,
    revision INTEGER NOT NULL,
    content_digest CHAR(71) NOT NULL,
    media_type VARCHAR(160) NOT NULL,
    byte_size BIGINT NOT NULL,
    original_filename VARCHAR(512),
    capture_metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    snapshot_bytes BYTEA,
    snapshot_status VARCHAR(24) NOT NULL,
    parser_version VARCHAR(120),
    chunker_version VARCHAR(120),
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_knowledge_revision_source_scope
        FOREIGN KEY (source_id, tenant_id, workspace_id)
        REFERENCES knowledge_source(id, tenant_id, workspace_id),
    CONSTRAINT ck_knowledge_revision_number
        CHECK (revision > 0),
    CONSTRAINT ck_knowledge_revision_digest
        CHECK (content_digest ~ '^sha256:[a-f0-9]{64}$'),
    CONSTRAINT ck_knowledge_revision_media_type
        CHECK (char_length(media_type) BETWEEN 1 AND 160),
    CONSTRAINT ck_knowledge_revision_byte_size
        CHECK (byte_size > 0),
    CONSTRAINT ck_knowledge_revision_filename
        CHECK (original_filename IS NULL OR (
            char_length(original_filename) BETWEEN 1 AND 512
            AND original_filename !~ '[[:cntrl:]/\\]'
        )),
    CONSTRAINT ck_knowledge_revision_capture_metadata
        CHECK (jsonb_typeof(capture_metadata) = 'object'),
    CONSTRAINT ck_knowledge_revision_snapshot_status
        CHECK (snapshot_status IN ('SNAPSHOTTED', 'REJECTED')),
    CONSTRAINT ck_knowledge_revision_snapshot_shape
        CHECK ((snapshot_status = 'SNAPSHOTTED' AND snapshot_bytes IS NOT NULL
                AND octet_length(snapshot_bytes) = byte_size)
            OR (snapshot_status = 'REJECTED' AND snapshot_bytes IS NULL)),
    CONSTRAINT ck_knowledge_revision_parser_version
        CHECK (parser_version IS NULL OR char_length(parser_version) BETWEEN 1 AND 120),
    CONSTRAINT ck_knowledge_revision_chunker_version
        CHECK (chunker_version IS NULL OR char_length(chunker_version) BETWEEN 1 AND 120),
    CONSTRAINT uq_knowledge_revision_source_number
        UNIQUE (source_id, revision),
    CONSTRAINT uq_knowledge_revision_source_digest
        UNIQUE (source_id, content_digest),
    CONSTRAINT uq_knowledge_revision_full_scope
        UNIQUE (id, tenant_id, workspace_id),
    CONSTRAINT uq_knowledge_revision_source_scope
        UNIQUE (id, tenant_id, workspace_id, source_id),
    CONSTRAINT uq_knowledge_revision_source_lineage
        UNIQUE (id, tenant_id, workspace_id, source_id, revision)
);

CREATE INDEX idx_knowledge_revision_source_created
    ON knowledge_source_revision(workspace_id, source_id, created_at DESC);

ALTER TABLE knowledge_source
    ADD CONSTRAINT fk_knowledge_source_latest_revision_scope
        FOREIGN KEY (latest_revision_id, tenant_id, workspace_id, id, latest_revision_number)
        REFERENCES knowledge_source_revision(id, tenant_id, workspace_id, source_id, revision)
        DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE knowledge_document (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    workspace_id UUID NOT NULL,
    source_revision_id UUID NOT NULL,
    ordinal INTEGER NOT NULL,
    title VARCHAR(1000),
    normalized_text_digest CHAR(71) NOT NULL,
    parser_version VARCHAR(120) NOT NULL,
    processing_profile VARCHAR(120) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_knowledge_document_revision_scope
        FOREIGN KEY (source_revision_id, tenant_id, workspace_id)
        REFERENCES knowledge_source_revision(id, tenant_id, workspace_id),
    CONSTRAINT ck_knowledge_document_ordinal
        CHECK (ordinal >= 0),
    CONSTRAINT ck_knowledge_document_title
        CHECK (title IS NULL OR char_length(title) BETWEEN 1 AND 1000),
    CONSTRAINT ck_knowledge_document_digest
        CHECK (normalized_text_digest ~ '^sha256:[a-f0-9]{64}$'),
    CONSTRAINT ck_knowledge_document_parser_version
        CHECK (char_length(parser_version) BETWEEN 1 AND 120),
    CONSTRAINT ck_knowledge_document_processing_profile
        CHECK (char_length(processing_profile) BETWEEN 1 AND 120),
    CONSTRAINT uq_knowledge_document_revision_ordinal
        UNIQUE (source_revision_id, ordinal),
    CONSTRAINT uq_knowledge_document_full_scope
        UNIQUE (id, tenant_id, workspace_id, source_revision_id)
);

CREATE INDEX idx_knowledge_document_revision_ordinal
    ON knowledge_document(workspace_id, source_revision_id, ordinal);

CREATE TABLE knowledge_chunk (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    workspace_id UUID NOT NULL,
    source_revision_id UUID NOT NULL,
    document_id UUID NOT NULL,
    ordinal INTEGER NOT NULL,
    text TEXT NOT NULL,
    content_digest CHAR(71) NOT NULL,
    start_offset INTEGER NOT NULL,
    end_offset INTEGER NOT NULL,
    page_number INTEGER,
    heading VARCHAR(1000),
    paragraph_number INTEGER,
    line_start INTEGER,
    line_end INTEGER,
    chunker_version VARCHAR(120) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_knowledge_chunk_revision_scope
        FOREIGN KEY (source_revision_id, tenant_id, workspace_id)
        REFERENCES knowledge_source_revision(id, tenant_id, workspace_id),
    CONSTRAINT fk_knowledge_chunk_document_scope
        FOREIGN KEY (document_id, tenant_id, workspace_id, source_revision_id)
        REFERENCES knowledge_document(id, tenant_id, workspace_id, source_revision_id),
    CONSTRAINT ck_knowledge_chunk_ordinal
        CHECK (ordinal >= 0),
    CONSTRAINT ck_knowledge_chunk_text
        CHECK (char_length(text) BETWEEN 1 AND 20000),
    CONSTRAINT ck_knowledge_chunk_digest
        CHECK (content_digest ~ '^sha256:[a-f0-9]{64}$'),
    CONSTRAINT ck_knowledge_chunk_offsets
        CHECK (start_offset >= 0 AND end_offset > start_offset),
    CONSTRAINT ck_knowledge_chunk_page
        CHECK (page_number IS NULL OR page_number > 0),
    CONSTRAINT ck_knowledge_chunk_heading
        CHECK (heading IS NULL OR char_length(heading) BETWEEN 1 AND 1000),
    CONSTRAINT ck_knowledge_chunk_paragraph
        CHECK (paragraph_number IS NULL OR paragraph_number > 0),
    CONSTRAINT ck_knowledge_chunk_lines
        CHECK ((line_start IS NULL AND line_end IS NULL)
            OR (line_start > 0 AND line_end >= line_start)),
    CONSTRAINT ck_knowledge_chunk_chunker_version
        CHECK (char_length(chunker_version) BETWEEN 1 AND 120),
    CONSTRAINT uq_knowledge_chunk_document_ordinal
        UNIQUE (document_id, ordinal),
    CONSTRAINT uq_knowledge_chunk_full_scope
        UNIQUE (id, tenant_id, workspace_id, document_id)
);

CREATE INDEX idx_knowledge_chunk_document_ordinal
    ON knowledge_chunk(workspace_id, document_id, ordinal);
CREATE INDEX idx_knowledge_chunk_revision
    ON knowledge_chunk(workspace_id, source_revision_id);

CREATE TABLE knowledge_ingestion_job (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    workspace_id UUID NOT NULL,
    knowledge_base_id UUID NOT NULL,
    source_id UUID NOT NULL,
    source_revision_id UUID,
    job_kind VARCHAR(32) NOT NULL,
    status VARCHAR(24) NOT NULL,
    current_step VARCHAR(24) NOT NULL,
    sync_outcome VARCHAR(24),
    attempt_count INTEGER NOT NULL DEFAULT 0,
    maximum_attempts INTEGER NOT NULL DEFAULT 3,
    next_attempt_at TIMESTAMPTZ,
    lease_owner VARCHAR(200),
    lease_until TIMESTAMPTZ,
    lock_version BIGINT NOT NULL DEFAULT 1,
    idempotency_key VARCHAR(200) NOT NULL,
    retryable BOOLEAN NOT NULL DEFAULT FALSE,
    error_code VARCHAR(120),
    error_category VARCHAR(40),
    failure_metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    cancellation_requested BOOLEAN NOT NULL DEFAULT FALSE,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_knowledge_job_base_scope
        FOREIGN KEY (knowledge_base_id, tenant_id, workspace_id)
        REFERENCES knowledge_base(id, tenant_id, workspace_id),
    CONSTRAINT fk_knowledge_job_source_scope
        FOREIGN KEY (source_id, tenant_id, workspace_id)
        REFERENCES knowledge_source(id, tenant_id, workspace_id),
    CONSTRAINT fk_knowledge_job_revision_scope
        FOREIGN KEY (source_revision_id, tenant_id, workspace_id, source_id)
        REFERENCES knowledge_source_revision(id, tenant_id, workspace_id, source_id),
    CONSTRAINT ck_knowledge_job_kind
        CHECK (job_kind IN ('CREATE_SOURCE', 'ADD_REVISION', 'SYNCHRONIZE_SOURCE')),
    CONSTRAINT ck_knowledge_job_status
        CHECK (status IN ('QUEUED', 'SNAPSHOTTING', 'PARSING', 'CHUNKING',
            'READY', 'RETRY_WAIT', 'FAILED', 'CANCELLED')),
    CONSTRAINT ck_knowledge_job_step
        CHECK (current_step IN ('SNAPSHOTTING', 'PARSING', 'CHUNKING', 'COMPLETE')),
    CONSTRAINT ck_knowledge_job_status_step
        CHECK ((status = 'QUEUED' AND current_step IN ('SNAPSHOTTING', 'PARSING'))
            OR (status = 'SNAPSHOTTING' AND current_step = 'SNAPSHOTTING')
            OR (status = 'PARSING' AND current_step = 'PARSING')
            OR (status = 'CHUNKING' AND current_step = 'CHUNKING')
            OR (status = 'READY' AND current_step = 'COMPLETE')
            OR (status IN ('RETRY_WAIT', 'FAILED', 'CANCELLED')
                AND current_step IN ('SNAPSHOTTING', 'PARSING', 'CHUNKING'))),
    CONSTRAINT ck_knowledge_job_sync_outcome
        CHECK (sync_outcome IS NULL OR sync_outcome IN ('CHANGED', 'UNCHANGED')),
    CONSTRAINT ck_knowledge_job_attempts
        CHECK (attempt_count >= 0 AND maximum_attempts > 0 AND attempt_count <= maximum_attempts),
    CONSTRAINT ck_knowledge_job_next_attempt
        CHECK ((status = 'RETRY_WAIT' AND next_attempt_at IS NOT NULL)
            OR (status <> 'RETRY_WAIT' AND next_attempt_at IS NULL)),
    CONSTRAINT ck_knowledge_job_lease
        CHECK ((lease_owner IS NULL AND lease_until IS NULL)
            OR (lease_owner IS NOT NULL AND char_length(lease_owner) BETWEEN 1 AND 200
                AND lease_until IS NOT NULL)),
    CONSTRAINT ck_knowledge_job_lock_version
        CHECK (lock_version > 0),
    CONSTRAINT ck_knowledge_job_idempotency_key
        CHECK (char_length(idempotency_key) BETWEEN 1 AND 200),
    CONSTRAINT ck_knowledge_job_error
        CHECK ((error_code IS NULL AND error_category IS NULL)
            OR (error_code IS NOT NULL AND char_length(error_code) BETWEEN 1 AND 120
                AND error_category IN ('VALIDATION', 'SECURITY', 'TRANSIENT', 'PERMANENT', 'INTERNAL'))),
    CONSTRAINT ck_knowledge_job_failure_metadata
        CHECK (jsonb_typeof(failure_metadata) = 'object'),
    CONSTRAINT ck_knowledge_job_revision_shape
        CHECK (source_revision_id IS NOT NULL
            OR (job_kind IN ('CREATE_SOURCE', 'SYNCHRONIZE_SOURCE')
                AND current_step = 'SNAPSHOTTING')),
    CONSTRAINT ck_knowledge_job_completion
        CHECK ((status IN ('READY', 'FAILED', 'CANCELLED') AND completed_at IS NOT NULL)
            OR (status NOT IN ('READY', 'FAILED', 'CANCELLED') AND completed_at IS NULL)),
    CONSTRAINT ck_knowledge_job_timestamps
        CHECK (updated_at >= created_at
            AND (started_at IS NULL OR started_at >= created_at)
            AND (completed_at IS NULL OR completed_at >= created_at)),
    CONSTRAINT uq_knowledge_job_idempotency
        UNIQUE (workspace_id, job_kind, idempotency_key),
    CONSTRAINT uq_knowledge_job_full_scope
        UNIQUE (id, tenant_id, workspace_id)
);

CREATE INDEX idx_knowledge_job_workspace_created
    ON knowledge_ingestion_job(workspace_id, created_at DESC);
CREATE INDEX idx_knowledge_job_base_created
    ON knowledge_ingestion_job(workspace_id, knowledge_base_id, created_at DESC);
CREATE INDEX idx_knowledge_job_claim
    ON knowledge_ingestion_job(status, next_attempt_at, lease_until, created_at)
    WHERE status IN ('QUEUED', 'RETRY_WAIT', 'SNAPSHOTTING', 'PARSING', 'CHUNKING');
CREATE INDEX idx_knowledge_job_source_created
    ON knowledge_ingestion_job(workspace_id, source_id, created_at DESC);

CREATE OR REPLACE FUNCTION reject_knowledge_immutable_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION '% is immutable; create a new lineage row instead', TG_TABLE_NAME
        USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER knowledge_source_revision_is_insert_only
BEFORE UPDATE OR DELETE ON knowledge_source_revision
FOR EACH ROW EXECUTE FUNCTION reject_knowledge_immutable_mutation();

CREATE TRIGGER knowledge_document_is_insert_only
BEFORE UPDATE OR DELETE ON knowledge_document
FOR EACH ROW EXECUTE FUNCTION reject_knowledge_immutable_mutation();

CREATE TRIGGER knowledge_chunk_is_insert_only
BEFORE UPDATE OR DELETE ON knowledge_chunk
FOR EACH ROW EXECUTE FUNCTION reject_knowledge_immutable_mutation();

COMMENT ON TABLE knowledge_base IS
    'Workspace-scoped Knowledge aggregate root metadata; product exposure remains disabled until P2 acceptance.';
COMMENT ON TABLE knowledge_source_revision IS
    'Immutable bounded source snapshot and digest lineage; rows are insert-only.';
COMMENT ON TABLE knowledge_document IS
    'Immutable parser output unit tied to one source revision; rows are insert-only.';
COMMENT ON TABLE knowledge_chunk IS
    'Immutable deterministic chunk content with source offsets and anchors; rows are insert-only.';
COMMENT ON TABLE knowledge_ingestion_job IS
    'Durable workspace-scoped ingestion state; READY means documents and chunks exist, not an index.';
