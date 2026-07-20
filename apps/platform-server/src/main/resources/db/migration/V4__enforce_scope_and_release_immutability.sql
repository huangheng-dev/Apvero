ALTER TABLE workspace
    ADD CONSTRAINT uq_workspace_id_tenant UNIQUE (id, tenant_id);

ALTER TABLE ai_application
    ADD CONSTRAINT fk_application_workspace_scope
        FOREIGN KEY (workspace_id, tenant_id) REFERENCES workspace(id, tenant_id),
    ADD CONSTRAINT uq_application_full_scope UNIQUE (id, tenant_id, workspace_id);

ALTER TABLE release_bundle
    ADD CONSTRAINT fk_release_application_scope
        FOREIGN KEY (application_id, tenant_id, workspace_id)
        REFERENCES ai_application(id, tenant_id, workspace_id),
    ADD CONSTRAINT uq_release_full_scope UNIQUE (id, tenant_id, workspace_id, application_id);

ALTER TABLE ai_run
    ADD CONSTRAINT fk_run_application_scope
        FOREIGN KEY (application_id, tenant_id, workspace_id)
        REFERENCES ai_application(id, tenant_id, workspace_id),
    ADD CONSTRAINT fk_run_release_scope
        FOREIGN KEY (release_bundle_id, tenant_id, workspace_id, application_id)
        REFERENCES release_bundle(id, tenant_id, workspace_id, application_id);

CREATE OR REPLACE FUNCTION reject_release_bundle_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'release_bundle is immutable; create a new version instead'
        USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER release_bundle_is_insert_only
BEFORE UPDATE OR DELETE ON release_bundle
FOR EACH ROW EXECUTE FUNCTION reject_release_bundle_mutation();
