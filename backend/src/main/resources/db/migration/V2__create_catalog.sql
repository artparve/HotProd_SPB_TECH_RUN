CREATE TABLE repaircalc.catalog_release (
    id uuid PRIMARY KEY,
    version text NOT NULL UNIQUE CHECK (btrim(version) <> ''),
    fingerprint varchar(64) NOT NULL UNIQUE CHECK (fingerprint ~ '^[0-9a-f]{64}$'),
    manifest jsonb NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'PUBLISHED')),
    active boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at timestamptz,
    CONSTRAINT catalog_release_publication_state CHECK (
        (status = 'DRAFT' AND published_at IS NULL AND NOT active)
        OR (status = 'PUBLISHED' AND published_at IS NOT NULL)
    )
);

CREATE UNIQUE INDEX catalog_release_one_active ON repaircalc.catalog_release (active) WHERE active;

CREATE TABLE repaircalc.product (
    id uuid PRIMARY KEY,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE repaircalc.product_revision (
    id uuid PRIMARY KEY,
    release_id uuid NOT NULL REFERENCES repaircalc.catalog_release (id),
    product_id uuid NOT NULL REFERENCES repaircalc.product (id),
    cod_good text,
    name text NOT NULL CHECK (btrim(name) <> ''),
    category text NOT NULL CHECK (btrim(category) <> ''),
    kind varchar(32) NOT NULL CHECK (kind IN ('covering', 'paint', 'primer', 'wallpaper', 'adhesive', 'socket', 'cable')),
    price numeric(19, 2) NOT NULL CHECK (price > 0 AND price < 'Infinity'::numeric),
    package_size numeric CHECK (package_size > 0 AND package_size < 'Infinity'::numeric),
    unit text NOT NULL CHECK (btrim(unit) <> ''),
    sale_unit text NOT NULL CHECK (btrim(sale_unit) <> ''),
    wet boolean,
    high_load boolean,
    unheated boolean,
    description text NOT NULL,
    source text NOT NULL CHECK (btrim(source) <> ''),
    consumption numeric CHECK (consumption > 0 AND consumption < 'Infinity'::numeric),
    roll_width numeric CHECK (roll_width > 0 AND roll_width < 'Infinity'::numeric),
    roll_length numeric CHECK (roll_length > 0 AND roll_length < 'Infinity'::numeric),
    rapport numeric CHECK (rapport >= 0 AND rapport < 'Infinity'::numeric),
    CONSTRAINT product_revision_release_product UNIQUE (release_id, product_id)
);

CREATE INDEX product_revision_product ON repaircalc.product_revision (product_id);

CREATE TABLE repaircalc.source_record (
    id uuid PRIMARY KEY,
    release_id uuid NOT NULL REFERENCES repaircalc.catalog_release (id),
    product_id uuid NOT NULL REFERENCES repaircalc.product (id),
    source_namespace text NOT NULL CHECK (btrim(source_namespace) <> ''),
    external_id text NOT NULL CHECK (btrim(external_id) <> ''),
    raw jsonb NOT NULL,
    CONSTRAINT source_record_release_identity UNIQUE (release_id, source_namespace, external_id)
);

CREATE INDEX source_record_product ON repaircalc.source_record (product_id);

CREATE FUNCTION repaircalc.protect_catalog_release() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'DRAFT' THEN
            RAISE EXCEPTION 'A catalog release must be created as DRAFT' USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;

    IF OLD.status = 'PUBLISHED' THEN
        IF TG_OP = 'DELETE' THEN
            RAISE EXCEPTION 'Published catalog releases cannot be deleted' USING ERRCODE = '23514';
        END IF;
        IF ROW(NEW.id, NEW.version, NEW.fingerprint, NEW.manifest, NEW.status, NEW.created_at, NEW.published_at)
                IS DISTINCT FROM
                ROW(OLD.id, OLD.version, OLD.fingerprint, OLD.manifest, OLD.status, OLD.created_at, OLD.published_at) THEN
            RAISE EXCEPTION 'Published catalog release metadata is immutable' USING ERRCODE = '23514';
        END IF;
    END IF;

    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER catalog_release_guard BEFORE INSERT OR UPDATE OR DELETE ON repaircalc.catalog_release
    FOR EACH ROW EXECUTE FUNCTION repaircalc.protect_catalog_release();

-- Shared parent locks prevent publication racing a content insert/update/delete.
-- Mapping tables added later can reuse this function if they carry release_id.
CREATE FUNCTION repaircalc.protect_release_content() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    old_release_id uuid;
    new_release_id uuid;
    parent record;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        old_release_id := OLD.release_id;
    END IF;
    IF TG_OP <> 'DELETE' THEN
        new_release_id := NEW.release_id;
    END IF;

    FOR parent IN
        SELECT id, status FROM repaircalc.catalog_release
        WHERE id IN (old_release_id, new_release_id)
        ORDER BY id FOR SHARE
    LOOP
        IF parent.status <> 'DRAFT' THEN
            RAISE EXCEPTION 'Published catalog content is immutable' USING ERRCODE = '23514';
        END IF;
    END LOOP;

    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER product_revision_publication_guard BEFORE INSERT OR UPDATE OR DELETE ON repaircalc.product_revision
    FOR EACH ROW EXECUTE FUNCTION repaircalc.protect_release_content();

CREATE TRIGGER source_record_publication_guard BEFORE INSERT OR UPDATE OR DELETE ON repaircalc.source_record
    FOR EACH ROW EXECUTE FUNCTION repaircalc.protect_release_content();
