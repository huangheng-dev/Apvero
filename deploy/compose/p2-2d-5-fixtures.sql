\set ON_ERROR_STOP on

BEGIN;

INSERT INTO tenant (id, slug, name)
VALUES (
    '00000000-0000-0000-0000-000000000002',
    'compose-verification-tenant',
    'Compose Verification Tenant'
)
ON CONFLICT (id) DO NOTHING;

INSERT INTO workspace (id, tenant_id, slug, name)
VALUES (
    '00000000-0000-0000-0000-000000000102',
    '00000000-0000-0000-0000-000000000002',
    'compose-verification',
    'Compose Verification'
)
ON CONFLICT (id) DO NOTHING;

INSERT INTO knowledge_base (
    id, tenant_id, workspace_id, slug, name, description, status,
    version, created_at, updated_at
) VALUES
(
    '00000000-0000-0000-0000-000000005101',
    '00000000-0000-0000-0000-000000000001',
    '00000000-0000-0000-0000-000000000101',
    'build-verification-primary',
    'Build Verification Primary',
    '',
    'ACTIVE',
    1,
    now(),
    now()
),
(
    '00000000-0000-0000-0000-000000006102',
    '00000000-0000-0000-0000-000000000002',
    '00000000-0000-0000-0000-000000000102',
    'build-verification-secondary',
    'Build Verification Secondary',
    '',
    'ACTIVE',
    1,
    now(),
    now()
)
ON CONFLICT (id) DO NOTHING;

INSERT INTO knowledge_source (
    id, tenant_id, workspace_id, knowledge_base_id, name, source_type,
    status, latest_revision_number, latest_revision_id, version,
    created_at, updated_at
) VALUES
(
    '00000000-0000-0000-0000-000000005201',
    '00000000-0000-0000-0000-000000000001',
    '00000000-0000-0000-0000-000000000101',
    '00000000-0000-0000-0000-000000005101',
    'Primary Evidence',
    'TEXT',
    'ACTIVE',
    0,
    NULL,
    1,
    now(),
    now()
),
(
    '00000000-0000-0000-0000-000000006202',
    '00000000-0000-0000-0000-000000000002',
    '00000000-0000-0000-0000-000000000102',
    '00000000-0000-0000-0000-000000006102',
    'Secondary Evidence',
    'TEXT',
    'ACTIVE',
    0,
    NULL,
    1,
    now(),
    now()
)
ON CONFLICT (id) DO NOTHING;

INSERT INTO knowledge_source_revision (
    id, tenant_id, workspace_id, source_id, revision, content_digest,
    media_type, byte_size, capture_metadata, snapshot_bytes, snapshot_status,
    parser_version, chunker_version, created_at
) VALUES
(
    '00000000-0000-0000-0000-000000005301',
    '00000000-0000-0000-0000-000000000001',
    '00000000-0000-0000-0000-000000000101',
    '00000000-0000-0000-0000-000000005201',
    1,
    'sha256:6d821f2a541fe912714c3285118da83656218f4b12421ed3db79385da8d51696',
    'text/plain',
    14,
    '{}'::jsonb,
    convert_to('alpha evidence', 'UTF8'),
    'SNAPSHOTTED',
    'apvero-text@1.0.0',
    'apvero-boundary@1.0.0',
    now()
),
(
    '00000000-0000-0000-0000-000000006302',
    '00000000-0000-0000-0000-000000000002',
    '00000000-0000-0000-0000-000000000102',
    '00000000-0000-0000-0000-000000006202',
    1,
    'sha256:678dd53361214d12832f1899af14cfcde1bd7f79200047efee48853161b65555',
    'text/plain',
    13,
    '{}'::jsonb,
    convert_to('beta evidence', 'UTF8'),
    'SNAPSHOTTED',
    'apvero-text@1.0.0',
    'apvero-boundary@1.0.0',
    now()
)
ON CONFLICT (id) DO NOTHING;

UPDATE knowledge_source
SET latest_revision_number = 1,
    latest_revision_id = CASE id
        WHEN '00000000-0000-0000-0000-000000005201'
            THEN '00000000-0000-0000-0000-000000005301'::uuid
        WHEN '00000000-0000-0000-0000-000000006202'
            THEN '00000000-0000-0000-0000-000000006302'::uuid
    END,
    updated_at = now()
WHERE id IN (
    '00000000-0000-0000-0000-000000005201',
    '00000000-0000-0000-0000-000000006202'
)
  AND latest_revision_id IS NULL;

INSERT INTO knowledge_document (
    id, tenant_id, workspace_id, source_revision_id, ordinal, title,
    normalized_text_digest, parser_version, processing_profile, created_at
) VALUES
(
    '00000000-0000-0000-0000-000000005401',
    '00000000-0000-0000-0000-000000000001',
    '00000000-0000-0000-0000-000000000101',
    '00000000-0000-0000-0000-000000005301',
    0,
    'Primary Evidence',
    'sha256:6d821f2a541fe912714c3285118da83656218f4b12421ed3db79385da8d51696',
    'apvero-text@1.0.0',
    'apvero-default@1.0.0',
    now()
),
(
    '00000000-0000-0000-0000-000000006402',
    '00000000-0000-0000-0000-000000000002',
    '00000000-0000-0000-0000-000000000102',
    '00000000-0000-0000-0000-000000006302',
    0,
    'Secondary Evidence',
    'sha256:678dd53361214d12832f1899af14cfcde1bd7f79200047efee48853161b65555',
    'apvero-text@1.0.0',
    'apvero-default@1.0.0',
    now()
)
ON CONFLICT (id) DO NOTHING;

INSERT INTO knowledge_chunk (
    id, tenant_id, workspace_id, source_revision_id, document_id, ordinal,
    text, content_digest, start_offset, end_offset, paragraph_number,
    line_start, line_end, chunker_version, created_at
) VALUES
(
    '00000000-0000-0000-0000-000000005501',
    '00000000-0000-0000-0000-000000000001',
    '00000000-0000-0000-0000-000000000101',
    '00000000-0000-0000-0000-000000005301',
    '00000000-0000-0000-0000-000000005401',
    0,
    'alpha evidence',
    'sha256:6d821f2a541fe912714c3285118da83656218f4b12421ed3db79385da8d51696',
    0,
    14,
    1,
    1,
    1,
    'apvero-boundary@1.0.0',
    now()
),
(
    '00000000-0000-0000-0000-000000006502',
    '00000000-0000-0000-0000-000000000002',
    '00000000-0000-0000-0000-000000000102',
    '00000000-0000-0000-0000-000000006302',
    '00000000-0000-0000-0000-000000006402',
    0,
    'beta evidence',
    'sha256:678dd53361214d12832f1899af14cfcde1bd7f79200047efee48853161b65555',
    0,
    13,
    1,
    1,
    1,
    'apvero-boundary@1.0.0',
    now()
)
ON CONFLICT (id) DO NOTHING;

INSERT INTO knowledge_ingestion_job (
    id, tenant_id, workspace_id, knowledge_base_id, source_id,
    source_revision_id, job_kind, status, current_step, sync_outcome,
    attempt_count, maximum_attempts, lock_version, idempotency_key,
    retryable, failure_metadata, cancellation_requested,
    started_at, completed_at, created_at, updated_at
) VALUES
(
    '00000000-0000-0000-0000-000000005601',
    '00000000-0000-0000-0000-000000000001',
    '00000000-0000-0000-0000-000000000101',
    '00000000-0000-0000-0000-000000005101',
    '00000000-0000-0000-0000-000000005201',
    '00000000-0000-0000-0000-000000005301',
    'CREATE_SOURCE',
    'READY',
    'COMPLETE',
    'CHANGED',
    1,
    3,
    1,
    'p2-2d-5-primary-ready',
    false,
    '{}'::jsonb,
    false,
    now(),
    now(),
    now(),
    now()
),
(
    '00000000-0000-0000-0000-000000006602',
    '00000000-0000-0000-0000-000000000002',
    '00000000-0000-0000-0000-000000000102',
    '00000000-0000-0000-0000-000000006102',
    '00000000-0000-0000-0000-000000006202',
    '00000000-0000-0000-0000-000000006302',
    'CREATE_SOURCE',
    'READY',
    'COMPLETE',
    'CHANGED',
    1,
    3,
    1,
    'p2-2d-5-secondary-ready',
    false,
    '{}'::jsonb,
    false,
    now(),
    now(),
    now(),
    now()
)
ON CONFLICT (id) DO NOTHING;

INSERT INTO model_provider (
    id, tenant_id, workspace_id, name, provider_type, base_url,
    enabled, version, created_at, updated_at
) VALUES
(
    '00000000-0000-0000-0000-000000005701',
    '00000000-0000-0000-0000-000000000001',
    '00000000-0000-0000-0000-000000000101',
    'Build Verification Deterministic',
    'DETERMINISTIC_LOCAL',
    'local://deterministic',
    true,
    1,
    now(),
    now()
),
(
    '00000000-0000-0000-0000-000000006702',
    '00000000-0000-0000-0000-000000000002',
    '00000000-0000-0000-0000-000000000102',
    'Build Verification Deterministic',
    'DETERMINISTIC_LOCAL',
    'local://deterministic',
    true,
    1,
    now(),
    now()
)
ON CONFLICT (id) DO NOTHING;

INSERT INTO model_definition (
    id, tenant_id, workspace_id, provider_id, model_key, name,
    capabilities, input_cost_micros_per_million,
    output_cost_micros_per_million, enabled, created_at, updated_at
) VALUES
(
    '00000000-0000-0000-0000-000000005801',
    '00000000-0000-0000-0000-000000000001',
    '00000000-0000-0000-0000-000000000101',
    '00000000-0000-0000-0000-000000005701',
    'apvero-deterministic-embedding@1.0.0',
    'Build Verification Embedding',
    '["EMBEDDING"]'::jsonb,
    0,
    0,
    true,
    now(),
    now()
),
(
    '00000000-0000-0000-0000-000000006802',
    '00000000-0000-0000-0000-000000000002',
    '00000000-0000-0000-0000-000000000102',
    '00000000-0000-0000-0000-000000006702',
    'apvero-deterministic-embedding@1.0.0',
    'Build Verification Embedding',
    '["EMBEDDING"]'::jsonb,
    0,
    0,
    true,
    now(),
    now()
)
ON CONFLICT (id) DO NOTHING;

INSERT INTO model_route (
    id, tenant_id, workspace_id, name, version, model_id, status,
    timeout_ms, route_capability, embedding_dimension,
    embedding_maximum_input_tokens, embedding_maximum_batch_size,
    embedding_normalization, created_at
) VALUES
(
    '00000000-0000-0000-0000-000000005901',
    '00000000-0000-0000-0000-000000000001',
    '00000000-0000-0000-0000-000000000101',
    'build-verification-embedding',
    1,
    '00000000-0000-0000-0000-000000005801',
    'PUBLISHED',
    2000,
    'EMBEDDING',
    256,
    8192,
    64,
    'L2',
    now()
),
(
    '00000000-0000-0000-0000-000000006902',
    '00000000-0000-0000-0000-000000000002',
    '00000000-0000-0000-0000-000000000102',
    'build-verification-embedding',
    1,
    '00000000-0000-0000-0000-000000006802',
    'PUBLISHED',
    2000,
    'EMBEDDING',
    256,
    8192,
    64,
    'L2',
    now()
)
ON CONFLICT (id) DO NOTHING;

INSERT INTO knowledge_index (
    id, tenant_id, workspace_id, knowledge_base_id, slug, name, status,
    metadata_version, version_count, created_at, updated_at
) VALUES
(
    '00000000-0000-0000-0000-000000006001',
    '00000000-0000-0000-0000-000000000001',
    '00000000-0000-0000-0000-000000000101',
    '00000000-0000-0000-0000-000000005101',
    'build-verification-primary',
    'Build Verification Primary',
    'ACTIVE',
    1,
    0,
    now(),
    now()
),
(
    '00000000-0000-0000-0000-000000007002',
    '00000000-0000-0000-0000-000000000002',
    '00000000-0000-0000-0000-000000000102',
    '00000000-0000-0000-0000-000000006102',
    'build-verification-secondary',
    'Build Verification Secondary',
    'ACTIVE',
    1,
    0,
    now(),
    now()
)
ON CONFLICT (id) DO NOTHING;

COMMIT;
