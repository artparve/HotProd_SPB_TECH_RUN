CREATE TABLE repaircalc.work_type (
    taxonomy_version text NOT NULL CHECK (btrim(taxonomy_version) <> ''),
    code text NOT NULL CHECK (code IN ('floor', 'paint', 'wallpaper', 'electrical')),
    meaning text NOT NULL CHECK (btrim(meaning) <> ''),
    PRIMARY KEY (taxonomy_version, code)
);

INSERT INTO repaircalc.work_type (taxonomy_version, code, meaning) VALUES
    ('work-taxonomy-v1', 'floor', 'Материалы для устройства напольного покрытия.'),
    ('work-taxonomy-v1', 'paint', 'Материалы для окраски стен.'),
    ('work-taxonomy-v1', 'wallpaper', 'Материалы для оклейки стен обоями.'),
    ('work-taxonomy-v1', 'electrical', 'Материалы для заданных электроточек и кабельных трасс.');

CREATE FUNCTION repaircalc.protect_work_type_meaning()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Work taxonomy entries are immutable; insert a new taxonomy version instead.'
        USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER work_type_immutable
    BEFORE UPDATE OR DELETE ON repaircalc.work_type
    FOR EACH ROW EXECUTE FUNCTION repaircalc.protect_work_type_meaning();

CREATE TABLE repaircalc.product_work (
    release_id uuid NOT NULL,
    product_id uuid NOT NULL,
    taxonomy_version text NOT NULL,
    work_code text NOT NULL,
    mapping_version text NOT NULL CHECK (btrim(mapping_version) <> ''),
    evidence text NOT NULL CHECK (btrim(evidence) <> ''),
    source text NOT NULL DEFAULT 'manual_test_fixture' CHECK (btrim(source) <> ''),
    PRIMARY KEY (release_id, product_id, taxonomy_version, work_code),
    CONSTRAINT product_work_revision_fk FOREIGN KEY (release_id, product_id)
        REFERENCES repaircalc.product_revision (release_id, product_id),
    CONSTRAINT product_work_work_type_fk FOREIGN KEY (taxonomy_version, work_code)
        REFERENCES repaircalc.work_type (taxonomy_version, code)
);

CREATE TRIGGER product_work_release_guard
    BEFORE INSERT OR UPDATE OR DELETE ON repaircalc.product_work
    FOR EACH ROW EXECUTE FUNCTION repaircalc.protect_release_content();
