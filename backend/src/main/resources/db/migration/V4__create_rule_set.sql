CREATE TABLE repaircalc.rule_set (
    id uuid PRIMARY KEY,
    version text NOT NULL UNIQUE CHECK (btrim(version) <> ''),
    fingerprint varchar(64) NOT NULL UNIQUE CHECK (fingerprint ~ '^[0-9a-f]{64}$'),
    catalog_release_id uuid NOT NULL REFERENCES repaircalc.catalog_release (id),
    taxonomy_version text NOT NULL CHECK (btrim(taxonomy_version) <> ''),
    bundle jsonb NOT NULL CHECK (jsonb_typeof(bundle) = 'object'),
    manifest jsonb NOT NULL CHECK (jsonb_typeof(manifest) = 'object'),
    status varchar(16) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'PUBLISHED')),
    active boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at timestamptz,
    CONSTRAINT rule_set_publication_state CHECK (
        (status = 'DRAFT' AND published_at IS NULL AND NOT active)
        OR (status = 'PUBLISHED' AND published_at IS NOT NULL)
    )
);

CREATE UNIQUE INDEX rule_set_one_active_per_catalog ON repaircalc.rule_set (catalog_release_id) WHERE active;
CREATE INDEX rule_set_catalog_release ON repaircalc.rule_set (catalog_release_id);

CREATE FUNCTION repaircalc.protect_rule_set() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    catalog_status text;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Rule set versions cannot be deleted' USING ERRCODE = '23514';
    END IF;

    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'DRAFT' THEN
            RAISE EXCEPTION 'A rule set must be created as DRAFT' USING ERRCODE = '23514';
        END IF;
    ELSIF OLD.status = 'PUBLISHED' THEN
        IF ROW(NEW.id, NEW.version, NEW.fingerprint, NEW.catalog_release_id, NEW.taxonomy_version,
                NEW.bundle, NEW.manifest, NEW.status, NEW.created_at, NEW.published_at)
                IS DISTINCT FROM
                ROW(OLD.id, OLD.version, OLD.fingerprint, OLD.catalog_release_id, OLD.taxonomy_version,
                OLD.bundle, OLD.manifest, OLD.status, OLD.created_at, OLD.published_at) THEN
            RAISE EXCEPTION 'Published rule set metadata and content are immutable' USING ERRCODE = '23514';
        END IF;
    END IF;

    -- This has its own lifecycle: rules are added after their catalog is published.
    SELECT status INTO catalog_status FROM repaircalc.catalog_release
        WHERE id = NEW.catalog_release_id FOR SHARE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Rule set catalog release does not exist' USING ERRCODE = '23503';
    END IF;
    IF catalog_status <> 'PUBLISHED' THEN
        RAISE EXCEPTION 'Rule sets require a published catalog release' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER rule_set_publication_guard BEFORE INSERT OR UPDATE OR DELETE ON repaircalc.rule_set
    FOR EACH ROW EXECUTE FUNCTION repaircalc.protect_rule_set();
