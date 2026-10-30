CREATE TABLE repaircalc.admin_classification_job (
    id uuid PRIMARY KEY,
    product_id uuid NOT NULL,
    version_id uuid NOT NULL,
    input jsonb NOT NULL CHECK (jsonb_typeof(input) = 'object'),
    status text NOT NULL DEFAULT 'queued' CHECK (status IN ('queued','running','succeeded','failed')),
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 3),
    available_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    lease_token uuid,
    lease_until timestamptz,
    result jsonb,
    error_code text,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by text NOT NULL,
    finished_at timestamptz,
    FOREIGN KEY (product_id, version_id) REFERENCES repaircalc.admin_product_version(product_id, id),
    CHECK ((status = 'running') = (lease_token IS NOT NULL AND lease_until IS NOT NULL)),
    CHECK ((status = 'succeeded') = (result IS NOT NULL)),
    CHECK ((status IN ('succeeded','failed')) = (finished_at IS NOT NULL))
);
CREATE UNIQUE INDEX admin_classification_one_pending ON repaircalc.admin_classification_job(version_id)
    WHERE status IN ('queued','running');
CREATE INDEX admin_classification_claim ON repaircalc.admin_classification_job(status, available_at, lease_until);
CREATE INDEX admin_classification_product ON repaircalc.admin_classification_job(product_id, created_at DESC);

CREATE FUNCTION repaircalc.protect_classification_job() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' OR OLD.status IN ('succeeded','failed') THEN
        RAISE EXCEPTION 'Completed classification and its input are immutable' USING ERRCODE = '23514';
    END IF;
    IF ROW(NEW.id,NEW.product_id,NEW.version_id,NEW.input,NEW.created_at,NEW.created_by)
        IS DISTINCT FROM ROW(OLD.id,OLD.product_id,OLD.version_id,OLD.input,OLD.created_at,OLD.created_by) THEN
        RAISE EXCEPTION 'Classification identity and input are immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER admin_classification_guard BEFORE UPDATE OR DELETE ON repaircalc.admin_classification_job
    FOR EACH ROW EXECUTE FUNCTION repaircalc.protect_classification_job();
