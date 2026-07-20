INSERT INTO release_bundle (
    id, tenant_id, workspace_id, application_id, version,
    artifact_digest, manifest, status, created_at
) VALUES
(
    '00000000-0000-0000-0000-000000002001',
    '00000000-0000-0000-0000-000000000001',
    '00000000-0000-0000-0000-000000000101',
    '00000000-0000-0000-0000-000000001001',
    '1.0.0',
    '942a788a7ddd986211e506fd58b5b1a9e37566af807948cc47300856d4d0f949',
    '{"schemaVersion":"1.0","modelRouteVersion":"local-deterministic@1.0.0","promptVersion":"prompt@1.0.0","outputSchemaVersion":"output@1.0.0","knowledgeIndexVersions":["knowledge@1.0.0"],"capabilityVersions":["core.read@1.0.0"],"policyVersions":["baseline@1.0.0"],"memoryPolicyVersion":"session@1.0.0","evaluationReportVersion":"smoke@1.0.0","runtimeParameters":{"temperature":0,"maxOutputTokens":512}}'::jsonb,
    'RELEASED', now() - interval '3 days'
),
(
    '00000000-0000-0000-0000-000000002002',
    '00000000-0000-0000-0000-000000000001',
    '00000000-0000-0000-0000-000000000101',
    '00000000-0000-0000-0000-000000001002',
    '1.0.0',
    '942a788a7ddd986211e506fd58b5b1a9e37566af807948cc47300856d4d0f949',
    '{"schemaVersion":"1.0","modelRouteVersion":"local-deterministic@1.0.0","promptVersion":"prompt@1.0.0","outputSchemaVersion":"output@1.0.0","knowledgeIndexVersions":["knowledge@1.0.0"],"capabilityVersions":["core.read@1.0.0"],"policyVersions":["baseline@1.0.0"],"memoryPolicyVersion":"session@1.0.0","evaluationReportVersion":"smoke@1.0.0","runtimeParameters":{"temperature":0,"maxOutputTokens":512}}'::jsonb,
    'RELEASED', now() - interval '2 days'
),
(
    '00000000-0000-0000-0000-000000002003',
    '00000000-0000-0000-0000-000000000001',
    '00000000-0000-0000-0000-000000000101',
    '00000000-0000-0000-0000-000000001003',
    '1.0.0',
    '942a788a7ddd986211e506fd58b5b1a9e37566af807948cc47300856d4d0f949',
    '{"schemaVersion":"1.0","modelRouteVersion":"local-deterministic@1.0.0","promptVersion":"prompt@1.0.0","outputSchemaVersion":"output@1.0.0","knowledgeIndexVersions":["knowledge@1.0.0"],"capabilityVersions":["core.read@1.0.0"],"policyVersions":["baseline@1.0.0"],"memoryPolicyVersion":"session@1.0.0","evaluationReportVersion":"smoke@1.0.0","runtimeParameters":{"temperature":0,"maxOutputTokens":512}}'::jsonb,
    'RELEASED', now() - interval '1 day'
);

INSERT INTO ai_run (
    id, tenant_id, workspace_id, application_id, release_bundle_id,
    status, provider_id, input, output, latency_ms, prompt_tokens,
    completion_tokens, cost_micros, trace_id, created_at
)
SELECT
    ('10000000-0000-0000-0000-' || lpad(series::text, 12, '0'))::uuid,
    '00000000-0000-0000-0000-000000000001'::uuid,
    '00000000-0000-0000-0000-000000000101'::uuid,
    CASE series % 3
        WHEN 1 THEN '00000000-0000-0000-0000-000000001001'::uuid
        WHEN 2 THEN '00000000-0000-0000-0000-000000001002'::uuid
        ELSE '00000000-0000-0000-0000-000000001003'::uuid
    END,
    CASE series % 3
        WHEN 1 THEN '00000000-0000-0000-0000-000000002001'::uuid
        WHEN 2 THEN '00000000-0000-0000-0000-000000002002'::uuid
        ELSE '00000000-0000-0000-0000-000000002003'::uuid
    END,
    'SUCCEEDED',
    'local-deterministic',
    jsonb_build_object('message', 'Local demonstration request ' || series),
    jsonb_build_object(
        'message', 'Apvero received: Local demonstration request ' || series,
        'mode', 'deterministic-local',
        'releaseDigest', '942a788a7ddd986211e506fd58b5b1a9e37566af807948cc47300856d4d0f949'
    ),
    4 + (series % 7),
    8 + series,
    20 + series,
    0,
    md5('apvero-local-trace-' || series),
    now() - (series || ' hours')::interval
FROM generate_series(1, 12) AS series;
