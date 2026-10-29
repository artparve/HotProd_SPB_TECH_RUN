-- Queue tests originally used minimal version snapshots. Append valid versions for UI listing.
BEGIN;
WITH fixed AS (
  INSERT INTO repaircalc.admin_product_version(id,product_id,version,snapshot,created_by)
  SELECT gen_random_uuid(),p.id,2,v.snapshot || jsonb_build_object('sourceNamespace',p.source_namespace,'externalId',p.external_id),'test'
  FROM repaircalc.admin_product p JOIN repaircalc.admin_product_version v ON v.id=p.current_version_id
  WHERE p.source_namespace='worker-test' AND NOT (v.snapshot ? 'sourceNamespace')
  RETURNING product_id,id
) UPDATE repaircalc.admin_product p SET current_version_id=f.id FROM fixed f WHERE p.id=f.product_id;
COMMIT;
