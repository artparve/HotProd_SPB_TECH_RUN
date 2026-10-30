SELECT jsonb_build_object(
  'product',(SELECT to_jsonb(p) FROM repaircalc.admin_product p WHERE id='d423fa38-ae71-41d5-b895-fc937bab1761'),
  'version',(SELECT to_jsonb(v) FROM repaircalc.admin_product_version v WHERE product_id='d423fa38-ae71-41d5-b895-fc937bab1761'),
  'job',(SELECT to_jsonb(j) FROM repaircalc.admin_classification_job j WHERE product_id='d423fa38-ae71-41d5-b895-fc937bab1761'),
  'review',(SELECT to_jsonb(r) FROM repaircalc.admin_label_review r WHERE product_id='d423fa38-ae71-41d5-b895-fc937bab1761'),
  'publication',(SELECT to_jsonb(x) FROM repaircalc.admin_publication x WHERE product_id='d423fa38-ae71-41d5-b895-fc937bab1761'),
  'activeCatalogCount',(SELECT count(*) FROM repaircalc.product_revision WHERE release_id=(SELECT id FROM repaircalc.catalog_release WHERE active)),
  'pendingJobs',(SELECT count(*) FROM repaircalc.admin_classification_job WHERE status IN ('queued','running'))
);
