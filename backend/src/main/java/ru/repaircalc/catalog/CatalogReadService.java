package ru.repaircalc.catalog;

import static ru.repaircalc.storage.MongoStore.query;

import java.util.*;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.stereotype.Service;
import ru.repaircalc.common.web.ServiceUnavailableException;
import ru.repaircalc.storage.MongoStore;

@Service
public class CatalogReadService {
  public static final String UNAVAILABLE_MESSAGE = "Catalog is temporarily unavailable.";
  private final MongoStore store;

  public CatalogReadService(MongoStore store) {
    this.store = store;
  }

  public List<ProductView> listProducts() {
    return activeSnapshot().products();
  }

  public Document pointer() {
    var p = store.id("publication_pointer", "active");
    if (p == null)
      throw new ServiceUnavailableException("catalog_unavailable", UNAVAILABLE_MESSAGE);
    return p;
  }

  public CatalogSnapshot activeSnapshot() {
    return snapshot(pointer().getString("catalogReleaseId"), null);
  }

  public CatalogSnapshot snapshot(String id, UUID onlyProduct) {
    var release = store.id("catalog_release", id);
    if (release == null)
      throw new ServiceUnavailableException("catalog_unavailable", UNAVAILABLE_MESSAGE);
    var q = query("releaseId", id).with(Sort.by("id"));
    if (onlyProduct != null) q.addCriteria(Criteria.where("id").is(onlyProduct.toString()));
    var products = store.find("product_revision", q).stream().map(d -> view(store, d)).toList();
    return new CatalogSnapshot(
        UUID.fromString(id),
        release.getString("version"),
        release.getString("fingerprint"),
        release.get("manifest", Document.class).getString("taxonomyVersion"),
        products);
  }

  public static ProductView view(MongoStore store, Document document) {
    var copy = new Document(document);
    copy.remove("releaseId");
    copy.put(
        "price", ((Decimal128) copy.get("price")).bigDecimalValue().setScale(2).toPlainString());
    return store.read(copy, ProductView.class);
  }
}
