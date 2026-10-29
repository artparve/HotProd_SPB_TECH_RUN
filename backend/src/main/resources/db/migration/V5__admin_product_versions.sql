-- Administrative drafts are separate from immutable public catalog releases.
CREATE TABLE repaircalc.admin_product (
    id uuid PRIMARY KEY,
    source_namespace text NOT NULL CHECK (btrim(source_namespace) <> ''),
    external_id text NOT NULL CHECK (btrim(external_id) <> ''),
    current_version_id uuid NOT NULL,
    status text NOT NULL DEFAULT 'draft' CHECK (status IN ('draft', 'review', 'published', 'archived')),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by text NOT NULL CHECK (btrim(created_by) <> ''),
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by text NOT NULL CHECK (btrim(updated_by) <> ''),
    UNIQUE (source_namespace, external_id)
);

CREATE TABLE repaircalc.admin_product_version (
    id uuid PRIMARY KEY,
    product_id uuid NOT NULL REFERENCES repaircalc.admin_product (id),
    version integer NOT NULL CHECK (version > 0),
    snapshot jsonb NOT NULL CHECK (jsonb_typeof(snapshot) = 'object'
        AND COALESCE(jsonb_typeof(snapshot->'name') = 'string' AND btrim(snapshot->>'name') <> '', false)),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by text NOT NULL CHECK (btrim(created_by) <> ''),
    UNIQUE (product_id, version),
    UNIQUE (product_id, id)
);

-- The product and its initial version are inserted in the same transaction.
ALTER TABLE repaircalc.admin_product ADD CONSTRAINT admin_product_current_version_fk
    FOREIGN KEY (id, current_version_id) REFERENCES repaircalc.admin_product_version (product_id, id)
    DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE repaircalc.admin_audit_event (
    id uuid PRIMARY KEY,
    request_id text NOT NULL CHECK (btrim(request_id) <> ''),
    actor text NOT NULL CHECK (btrim(actor) <> ''),
    occurred_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    action text NOT NULL CHECK (btrim(action) <> ''),
    product_id uuid NOT NULL REFERENCES repaircalc.admin_product (id),
    before_version_id uuid,
    after_version_id uuid,
    details jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(details) = 'object'),
    FOREIGN KEY (product_id, before_version_id) REFERENCES repaircalc.admin_product_version (product_id, id),
    FOREIGN KEY (product_id, after_version_id) REFERENCES repaircalc.admin_product_version (product_id, id)
);
CREATE INDEX admin_audit_event_product_time ON repaircalc.admin_audit_event (product_id, occurred_at, id);

CREATE FUNCTION repaircalc.protect_admin_identity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Administrative product identities cannot be deleted' USING ERRCODE = '23514';
    END IF;
    IF ROW(NEW.id, NEW.source_namespace, NEW.external_id, NEW.created_at, NEW.created_by)
        IS DISTINCT FROM ROW(OLD.id, OLD.source_namespace, OLD.external_id, OLD.created_at, OLD.created_by) THEN
        RAISE EXCEPTION 'Administrative product identity is immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER admin_product_identity_guard BEFORE UPDATE OR DELETE ON repaircalc.admin_product
    FOR EACH ROW EXECUTE FUNCTION repaircalc.protect_admin_identity();

CREATE FUNCTION repaircalc.protect_admin_history() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Administrative history is immutable' USING ERRCODE = '23514';
END;
$$;
CREATE TRIGGER admin_product_version_immutable BEFORE UPDATE OR DELETE ON repaircalc.admin_product_version
    FOR EACH ROW EXECUTE FUNCTION repaircalc.protect_admin_history();
CREATE TRIGGER admin_audit_event_immutable BEFORE UPDATE OR DELETE ON repaircalc.admin_audit_event
    FOR EACH ROW EXECUTE FUNCTION repaircalc.protect_admin_history();
