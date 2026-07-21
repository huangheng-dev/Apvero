CREATE OR REPLACE FUNCTION protect_audit_event_ledger()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'DELETE' AND current_setting('apvero.retention_purge', true) = 'on' THEN
        RETURN OLD;
    END IF;
    RAISE EXCEPTION 'audit_event is append-only; only the controlled retention purge may delete rows';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_protect_audit_event_ledger
BEFORE UPDATE OR DELETE ON audit_event
FOR EACH ROW EXECUTE FUNCTION protect_audit_event_ledger();

COMMENT ON FUNCTION protect_audit_event_ledger() IS
    'Rejects audit mutation while allowing governance-owned, transaction-scoped retention deletion.';
