CREATE OR REPLACE FUNCTION guard_knowledge_index_entry_insert()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    build_status VARCHAR(24);
    build_step VARCHAR(24);
    published_version UUID;
BEGIN
    SELECT build.status, build.current_step, build.published_version_id
    INTO build_status, build_step, published_version
    FROM knowledge_index_build build
    WHERE build.id = NEW.knowledge_index_build_id
      AND build.tenant_id = NEW.tenant_id
      AND build.workspace_id = NEW.workspace_id
      AND build.knowledge_index_id = NEW.knowledge_index_id
      AND build.knowledge_base_id = NEW.knowledge_base_id
    FOR SHARE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'knowledge index entry build is missing from the active scope'
            USING ERRCODE = '23503';
    END IF;
    IF build_status <> 'EMBEDDING'
        OR build_step <> 'EMBEDDING'
        OR published_version IS NOT NULL THEN
        RAISE EXCEPTION 'knowledge index entries require an unpublished EMBEDDING build'
            USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION guard_knowledge_index_build_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    transition_allowed BOOLEAN := FALSE;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'knowledge_index_build is durable and cannot be deleted'
            USING ERRCODE = '55000';
    END IF;
    IF OLD.status IN ('READY', 'CANCELLED') OR OLD.published_version_id IS NOT NULL THEN
        RAISE EXCEPTION 'terminal knowledge_index_build is immutable'
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
    IF NEW.lock_version <> OLD.lock_version + 1 THEN
        RAISE EXCEPTION 'knowledge_index_build lock_version must increase exactly once'
            USING ERRCODE = '55000';
    END IF;
    IF NEW.embedded_entry_count < OLD.embedded_entry_count
        OR NEW.validated_entry_count < OLD.validated_entry_count
        OR (OLD.last_durable_chunk_ordinal IS NOT NULL
            AND (NEW.last_durable_chunk_ordinal IS NULL
                OR NEW.last_durable_chunk_ordinal < OLD.last_durable_chunk_ordinal)) THEN
        RAISE EXCEPTION 'knowledge_index_build progress is monotonic'
            USING ERRCODE = '55000';
    END IF;
    IF NEW.attempt_count < OLD.attempt_count
        AND NOT (
            OLD.status = 'FAILED'
            AND NEW.status = 'RETRY_WAIT'
            AND OLD.retryable
            AND NEW.attempt_count = 0
        ) THEN
        RAISE EXCEPTION 'knowledge_index_build attempt_count is monotonic outside manual retry'
            USING ERRCODE = '55000';
    END IF;
    IF OLD.validation_digest IS NOT NULL
        AND NEW.validation_digest IS DISTINCT FROM OLD.validation_digest THEN
        RAISE EXCEPTION 'knowledge_index_build validation digest is immutable once recorded'
            USING ERRCODE = '55000';
    END IF;
    IF OLD.artifact_digest IS NOT NULL
        AND NEW.artifact_digest IS DISTINCT FROM OLD.artifact_digest THEN
        RAISE EXCEPTION 'knowledge_index_build artifact digest is immutable once recorded'
            USING ERRCODE = '55000';
    END IF;

    transition_allowed :=
        (NEW.status = OLD.status
            AND NEW.current_step = OLD.current_step
            AND OLD.status IN ('QUEUED', 'EMBEDDING', 'INDEXING', 'VALIDATING', 'RETRY_WAIT'))
        OR (OLD.status = 'QUEUED'
            AND NEW.status = 'EMBEDDING'
            AND NEW.current_step = 'EMBEDDING')
        OR (OLD.status = 'QUEUED'
            AND NEW.status = 'CANCELLED'
            AND NEW.current_step = OLD.current_step)
        OR (OLD.status = 'EMBEDDING'
            AND NEW.status = 'INDEXING'
            AND NEW.current_step = 'INDEXING')
        OR (OLD.status = 'INDEXING'
            AND NEW.status = 'VALIDATING'
            AND NEW.current_step = 'VALIDATING')
        OR (OLD.status = 'VALIDATING'
            AND NEW.status = 'READY'
            AND NEW.current_step = 'COMPLETE')
        OR (OLD.status IN ('EMBEDDING', 'INDEXING', 'VALIDATING')
            AND NEW.status IN ('RETRY_WAIT', 'FAILED')
            AND NEW.current_step = OLD.current_step)
        OR (OLD.status = 'RETRY_WAIT'
            AND NEW.current_step = OLD.current_step
            AND (
                (OLD.current_step = 'EMBEDDING' AND NEW.status = 'EMBEDDING')
                OR (OLD.current_step = 'INDEXING' AND NEW.status = 'INDEXING')
                OR (OLD.current_step = 'VALIDATING' AND NEW.status = 'VALIDATING')
                OR NEW.status = 'CANCELLED'
            ))
        OR (OLD.status = 'FAILED'
            AND NEW.status = 'RETRY_WAIT'
            AND NEW.current_step = OLD.current_step
            AND OLD.retryable);

    IF NOT transition_allowed THEN
        RAISE EXCEPTION 'illegal knowledge_index_build state transition: %/% -> %/%',
            OLD.status, OLD.current_step, NEW.status, NEW.current_step
            USING ERRCODE = '55000';
    END IF;
    IF NEW.status = 'CANCELLED'
        AND (OLD.status NOT IN ('QUEUED', 'RETRY_WAIT')
            OR OLD.lease_owner IS NOT NULL
            OR OLD.lease_until IS NOT NULL) THEN
        RAISE EXCEPTION 'only an unleased waiting knowledge_index_build can be cancelled'
            USING ERRCODE = '55000';
    END IF;
    IF OLD.status = 'FAILED' AND NEW.status = 'RETRY_WAIT'
        AND (NOT OLD.retryable
            OR NEW.attempt_count <> 0
            OR NEW.completed_at IS NOT NULL
            OR NEW.error_code IS NOT NULL
            OR NEW.error_category IS NOT NULL
            OR NEW.reconciliation_required) THEN
        RAISE EXCEPTION 'knowledge_index_build manual retry shape is invalid'
            USING ERRCODE = '55000';
    END IF;
    IF NEW.published_version_id IS DISTINCT FROM OLD.published_version_id
        AND NOT (
            OLD.status = 'VALIDATING'
            AND NEW.status = 'READY'
            AND OLD.published_version_id IS NULL
            AND NEW.published_version_id IS NOT NULL
        ) THEN
        RAISE EXCEPTION 'knowledge_index_build publication identity can only be assigned at READY'
            USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION guard_knowledge_index_version_insert()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    build_status VARCHAR(24);
    build_step VARCHAR(24);
    build_version VARCHAR(64);
    build_route_id UUID;
    build_route_reference VARCHAR(240);
    build_dimension INTEGER;
    build_source_count INTEGER;
    build_chunk_count INTEGER;
    build_embedded_count INTEGER;
    build_validated_count INTEGER;
    build_validation_digest CHAR(71);
    build_artifact_digest CHAR(71);
    build_published_version UUID;
    index_slug VARCHAR(80);
BEGIN
    SELECT build.status,
        build.current_step,
        build.requested_version,
        build.embedding_route_id,
        build.embedding_route_reference,
        build.vector_dimension,
        build.requested_source_count,
        build.requested_chunk_count,
        build.embedded_entry_count,
        build.validated_entry_count,
        build.validation_digest,
        build.artifact_digest,
        build.published_version_id,
        knowledge_index.slug
    INTO build_status,
        build_step,
        build_version,
        build_route_id,
        build_route_reference,
        build_dimension,
        build_source_count,
        build_chunk_count,
        build_embedded_count,
        build_validated_count,
        build_validation_digest,
        build_artifact_digest,
        build_published_version,
        index_slug
    FROM knowledge_index_build build
    JOIN knowledge_index
      ON knowledge_index.id = build.knowledge_index_id
     AND knowledge_index.tenant_id = build.tenant_id
     AND knowledge_index.workspace_id = build.workspace_id
    WHERE build.id = NEW.knowledge_index_build_id
      AND build.tenant_id = NEW.tenant_id
      AND build.workspace_id = NEW.workspace_id
      AND build.knowledge_index_id = NEW.knowledge_index_id
    FOR UPDATE OF build;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'knowledge index version build is missing from the active scope'
            USING ERRCODE = '23503';
    END IF;
    IF build_status <> 'VALIDATING'
        OR build_step <> 'VALIDATING'
        OR build_published_version IS NOT NULL
        OR build_validation_digest IS NULL
        OR build_artifact_digest IS NULL
        OR build_embedded_count <> build_chunk_count
        OR build_validated_count <> build_chunk_count
        OR NEW.version IS DISTINCT FROM build_version
        OR NEW.reference IS DISTINCT FROM index_slug || '@' || build_version
        OR NEW.embedding_route_id IS DISTINCT FROM build_route_id
        OR NEW.embedding_route_reference IS DISTINCT FROM build_route_reference
        OR NEW.vector_dimension IS DISTINCT FROM build_dimension
        OR NEW.source_count IS DISTINCT FROM build_source_count
        OR NEW.chunk_count IS DISTINCT FROM build_chunk_count
        OR NEW.artifact_digest IS DISTINCT FROM build_artifact_digest
        OR NEW.status <> 'READY' THEN
        RAISE EXCEPTION 'knowledge index version requires a complete VALIDATING build'
            USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER knowledge_index_version_validates_publication
BEFORE INSERT ON knowledge_index_version
FOR EACH ROW EXECUTE FUNCTION guard_knowledge_index_version_insert();

COMMENT ON FUNCTION guard_knowledge_index_entry_insert() IS
    'Serializes Entry insertion against Build transitions and permits only unpublished EMBEDDING builds.';
COMMENT ON FUNCTION guard_knowledge_index_build_mutation() IS
    'Enforces the P2.2d durable Build state machine, optimistic version, monotonic progress, and terminal immutability.';
COMMENT ON FUNCTION guard_knowledge_index_version_insert() IS
    'Rejects partial, mismatched, repeated, or non-VALIDATING Knowledge Index publication.';
