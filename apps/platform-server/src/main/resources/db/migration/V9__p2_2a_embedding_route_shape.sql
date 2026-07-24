ALTER TABLE model_route
    ADD COLUMN route_capability VARCHAR(24) NOT NULL DEFAULT 'CHAT',
    ADD COLUMN embedding_dimension INTEGER,
    ADD COLUMN embedding_maximum_input_tokens INTEGER,
    ADD COLUMN embedding_maximum_batch_size INTEGER,
    ADD COLUMN embedding_normalization VARCHAR(16);

UPDATE model_definition model
SET capabilities = model.capabilities || '["CHAT"]'::jsonb
WHERE NOT model.capabilities ? 'CHAT'
  AND EXISTS (
      SELECT 1
      FROM model_route route
      WHERE route.model_id = model.id
        AND route.tenant_id = model.tenant_id
        AND route.workspace_id = model.workspace_id
  );

ALTER TABLE model_route
    ALTER COLUMN max_output_tokens DROP NOT NULL,
    ADD CONSTRAINT ck_model_route_status
        CHECK (status IN ('PUBLISHED', 'DEPRECATED')),
    ADD CONSTRAINT ck_model_route_capability
        CHECK (route_capability IN ('CHAT', 'EMBEDDING')),
    ADD CONSTRAINT ck_model_route_shape
        CHECK (
            (
                route_capability = 'CHAT'
                AND max_output_tokens BETWEEN 1 AND 200000
                AND (temperature IS NULL OR temperature BETWEEN 0 AND 2)
                AND embedding_dimension IS NULL
                AND embedding_maximum_input_tokens IS NULL
                AND embedding_maximum_batch_size IS NULL
                AND embedding_normalization IS NULL
            )
            OR
            (
                route_capability = 'EMBEDDING'
                AND max_output_tokens IS NULL
                AND temperature IS NULL
                AND embedding_dimension BETWEEN 1 AND 16000
                AND embedding_maximum_input_tokens BETWEEN 1 AND 2000000
                AND embedding_maximum_batch_size BETWEEN 1 AND 4096
                AND embedding_normalization IN ('NONE', 'L2')
            )
        );

CREATE OR REPLACE FUNCTION enforce_model_route_capability()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM model_definition model
        WHERE model.id = NEW.model_id
          AND model.tenant_id = NEW.tenant_id
          AND model.workspace_id = NEW.workspace_id
          AND model.capabilities ? NEW.route_capability
    ) THEN
        RAISE EXCEPTION 'model_route capability must be declared by model_definition'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_model_route_capability
BEFORE INSERT ON model_route
FOR EACH ROW EXECUTE FUNCTION enforce_model_route_capability();

CREATE OR REPLACE FUNCTION reject_model_route_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'model_route is immutable; publish a new route version instead'
        USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER trg_model_route_immutable
BEFORE UPDATE OR DELETE ON model_route
FOR EACH ROW EXECUTE FUNCTION reject_model_route_mutation();

COMMENT ON COLUMN model_route.route_capability IS
    'Discriminates the immutable CHAT or EMBEDDING route profile.';
COMMENT ON COLUMN model_route.embedding_dimension IS
    'Pinned pgvector-compatible Embedding dimension in the range 1..16000.';
COMMENT ON COLUMN model_route.embedding_maximum_input_tokens IS
    'Pinned maximum estimated aggregate input units per Embedding request.';
COMMENT ON COLUMN model_route.embedding_maximum_batch_size IS
    'Pinned maximum item count per Embedding request.';
COMMENT ON COLUMN model_route.embedding_normalization IS
    'Pinned Embedding normalization contract: NONE or L2.';
