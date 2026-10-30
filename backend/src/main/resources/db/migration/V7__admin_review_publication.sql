CREATE TABLE repaircalc.admin_label_review (
    id uuid PRIMARY KEY,
    product_id uuid NOT NULL,
    version_id uuid NOT NULL,
    job_id uuid NOT NULL REFERENCES repaircalc.admin_classification_job(id),
    decisions jsonb NOT NULL,
    approved jsonb NOT NULL,
    java_works jsonb NOT NULL,
    mapping_version text NOT NULL,
    profile_id text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by text NOT NULL,
    FOREIGN KEY (product_id,version_id) REFERENCES repaircalc.admin_product_version(product_id,id)
);
CREATE INDEX admin_review_version ON repaircalc.admin_label_review(version_id,created_at DESC);
CREATE TRIGGER admin_label_review_immutable BEFORE UPDATE OR DELETE ON repaircalc.admin_label_review
    FOR EACH ROW EXECUTE FUNCTION repaircalc.protect_admin_history();
CREATE TABLE repaircalc.admin_publication (
    id uuid PRIMARY KEY,
    product_id uuid NOT NULL,
    version_id uuid NOT NULL UNIQUE,
    review_id uuid NOT NULL REFERENCES repaircalc.admin_label_review(id),
    release_id uuid NOT NULL REFERENCES repaircalc.catalog_release(id),
    rule_set_id uuid NOT NULL REFERENCES repaircalc.rule_set(id),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by text NOT NULL,
    FOREIGN KEY (product_id,version_id) REFERENCES repaircalc.admin_product_version(product_id,id)
);
CREATE TRIGGER admin_publication_immutable BEFORE UPDATE OR DELETE ON repaircalc.admin_publication
    FOR EACH ROW EXECUTE FUNCTION repaircalc.protect_admin_history();
