CREATE TABLE application_draft_knowledge_binding (
    application_id UUID NOT NULL,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    binding_order SMALLINT NOT NULL,
    knowledge_index_version_id UUID NOT NULL,
    retrieval_policy_version_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_application_draft_knowledge_binding
        PRIMARY KEY (application_id, binding_order),
    CONSTRAINT fk_application_draft_knowledge_binding_application_scope
        FOREIGN KEY (application_id, tenant_id, workspace_id)
        REFERENCES ai_application(id, tenant_id, workspace_id)
        ON DELETE CASCADE,
    CONSTRAINT uq_application_draft_knowledge_binding_pair
        UNIQUE (
            application_id,
            knowledge_index_version_id,
            retrieval_policy_version_id),
    CONSTRAINT ck_application_draft_knowledge_binding_order
        CHECK (binding_order BETWEEN 0 AND 15),
    CONSTRAINT ck_application_draft_knowledge_binding_timestamps
        CHECK (updated_at >= created_at)
);

CREATE INDEX idx_application_draft_knowledge_binding_scope
    ON application_draft_knowledge_binding(
        tenant_id, workspace_id, application_id, binding_order);

CREATE OR REPLACE FUNCTION validate_application_draft_knowledge_binding()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    application_mode VARCHAR(32);
BEGIN
    SELECT runtime_mode
      INTO application_mode
      FROM ai_application
     WHERE id = NEW.application_id
       AND tenant_id = NEW.tenant_id
       AND workspace_id = NEW.workspace_id;

    IF application_mode IS DISTINCT FROM 'RAG' THEN
        RAISE EXCEPTION 'application Knowledge bindings require RAG runtime mode'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER application_draft_knowledge_binding_validates_mode
BEFORE INSERT OR UPDATE ON application_draft_knowledge_binding
FOR EACH ROW EXECUTE FUNCTION validate_application_draft_knowledge_binding();

CREATE OR REPLACE FUNCTION reject_application_mode_with_knowledge_bindings()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.runtime_mode IS DISTINCT FROM 'RAG'
       AND EXISTS (
           SELECT 1
             FROM application_draft_knowledge_binding binding
            WHERE binding.application_id = OLD.id
              AND binding.tenant_id = OLD.tenant_id
              AND binding.workspace_id = OLD.workspace_id
       ) THEN
        RAISE EXCEPTION 'application with Knowledge bindings must remain in RAG runtime mode'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER application_runtime_mode_preserves_knowledge_binding
BEFORE UPDATE OF runtime_mode ON ai_application
FOR EACH ROW EXECUTE FUNCTION reject_application_mode_with_knowledge_bindings();

COMMENT ON TABLE application_draft_knowledge_binding IS
    'Mutable ordered opaque Knowledge version IDs on an Application draft. Release performs authoritative Knowledge validation.';
COMMENT ON COLUMN application_draft_knowledge_binding.knowledge_index_version_id IS
    'Opaque Knowledge-owned identifier; intentionally no cross-module foreign key.';
COMMENT ON COLUMN application_draft_knowledge_binding.retrieval_policy_version_id IS
    'Opaque Knowledge-owned identifier; intentionally no cross-module foreign key.';
