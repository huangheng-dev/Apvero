INSERT INTO tenant (id, slug, name)
VALUES ('00000000-0000-0000-0000-000000000001', 'local', 'Local Tenant');

INSERT INTO workspace (id, tenant_id, slug, name)
VALUES (
    '00000000-0000-0000-0000-000000000101',
    '00000000-0000-0000-0000-000000000001',
    'default',
    'Default Workspace'
);

INSERT INTO ai_application (
    id, tenant_id, workspace_id, slug, name, description,
    runtime_mode, status, version, created_at, updated_at
) VALUES
(
    '00000000-0000-0000-0000-000000001001',
    '00000000-0000-0000-0000-000000000001',
    '00000000-0000-0000-0000-000000000101',
    'procurement-copilot', 'Procurement Copilot',
    'Answers procurement questions and invokes approved read-only capabilities.',
    'AGENTIC', 'DRAFT', 1, now(), now()
),
(
    '00000000-0000-0000-0000-000000001002',
    '00000000-0000-0000-0000-000000000001',
    '00000000-0000-0000-0000-000000000101',
    'policy-qa', 'Policy Q&A',
    'Retrieval-augmented answers grounded in approved company policies.',
    'RAG', 'DRAFT', 1, now(), now()
),
(
    '00000000-0000-0000-0000-000000001003',
    '00000000-0000-0000-0000-000000000001',
    '00000000-0000-0000-0000-000000000101',
    'invoice-extractor', 'Invoice Extractor',
    'Produces validated structured invoice output.',
    'STRUCTURED', 'DRAFT', 1, now(), now()
);
