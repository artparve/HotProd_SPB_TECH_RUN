package ru.repaircalc.storage;

import org.springframework.data.domain.Sort.Direction;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.stereotype.Component;

@Component
public class MongoSchema {
  private final MongoStore store;

  public MongoSchema(MongoStore store) {
    this.store = store;
  }

  public void initialize() {
    unique("catalog_release", "version");
    unique("rule_set", "version");
    unique("product_revision", "releaseId", "id");
    unique("source_identity", "namespace", "externalId");
    unique("source_record", "releaseId", "namespace", "externalId");
    unique("admin_product", "sourceNamespace", "externalId");
    unique("admin_product_version", "productId", "version");
    unique("admin_publication", "version_id");
    // A version has one durable job. Retry is a state transition, not a duplicate job.
    unique("admin_classification_job", "versionId");
    index("admin_classification_job", "status", "availableAt");
    index("admin_classification_job", "status", "leaseUntil");
    index("admin_label_review", "version_id", "created_at");
    index("admin_product", "updatedAt", "id");
    index("admin_audit_event", "productId", "createdAt");
    index("inspiration", "id");
  }

  private void unique(String collection, String... fields) {
    create(collection, true, fields);
  }

  private void index(String collection, String... fields) {
    create(collection, false, fields);
  }

  private void create(String collection, boolean unique, String... fields) {
    var index = new Index();
    for (var field : fields) index.on(field, Direction.ASC);
    if (unique) index.unique();
    store.mongo.indexOps(collection).ensureIndex(index);
  }
}
