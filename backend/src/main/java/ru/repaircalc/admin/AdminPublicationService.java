package ru.repaircalc.admin;

import static ru.repaircalc.storage.MongoStore.*;

import java.math.BigDecimal;
import java.util.*;
import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.stereotype.Service;
import ru.repaircalc.catalog.*;
import ru.repaircalc.rules.*;
import ru.repaircalc.storage.MongoStore;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
public class AdminPublicationService {
  private final MongoStore store;
  private final AdminProductService products;
  private final JsonMapper json;
  private final RuleSetValidator validator;

  public AdminPublicationService(
      MongoStore store, AdminProductService products, JsonMapper json, RuleSetValidator validator) {
    this.store = store;
    this.products = products;
    this.json = json;
    this.validator = validator;
  }

  public Readiness readiness(UUID id) {
    return inspect(products.get(id));
  }

  private Readiness inspect(AdminProductService.Product p) {
    var blockers = new ArrayList<String>();
    var s = p.snapshot();
    for (var key :
        List.of("category", "price", "currency", "priceDate", "kind", "unit", "saleUnit"))
      if (!confirmed(s, key)) blockers.add("Требуется подтверждение: " + key);
    if ("covering".equals(value(s, "kind")) && !confirmed(s, "materialFamily"))
      blockers.add("Подтвердите семейство напольного покрытия.");
    if (s.path("sources").isEmpty()) blockers.add("Добавьте источник карточки.");
    var reviews =
        store
            .find(
                "admin_label_review",
                query("version_id", p.versionId().toString())
                    .with(Sort.by(Sort.Direction.DESC, "created_at", "id"))
                    .limit(1))
            .stream()
            .map(store::tree)
            .toList();
    JsonNode review = reviews.isEmpty() ? null : reviews.getFirst();
    if (review == null) blockers.add("Нет проверки классификации для текущей версии.");
    else if (review.path("java_works").isEmpty())
      blockers.add("Нет проверенного сопоставления с Java-таксономией.");
    var base = base();
    if (base == null) blockers.add("Нет активной совместимой пары каталога и правил.");
    if (base != null && review != null && blockers.isEmpty()) {
      try {
        var old = json.readValue(base.path("bundle").toString(), RuleSetBundle.class);
        var profile =
            old.profiles().stream()
                .filter(x -> x.id().equals(review.path("profile_id").asText()))
                .findFirst()
                .orElse(null);
        if (profile == null) blockers.add("Выбранный профиль отсутствует в действующих правилах.");
        else {
          if (profile.consumption() != null
              && (!confirmed(s, "consumption")
                  || !confirmed(s, "consumptionUnit")
                  || !s.path("consumptionUnit").path("value").asText().equals(profile.unit())))
            blockers.add("Подтвердите норму расхода и её единицу.");
          if (profile.strategy() == RuleSetBundle.Strategy.WALLPAPER) {
            for (var field : List.of("rollWidth", "rollLength", "rapport"))
              if (!confirmed(s, field)) blockers.add("Требуется подтверждение: " + field);
          } else if (!confirmed(s, "packageSize")) blockers.add("Подтвердите фасовку.");
          var test =
              new RuleSetBundle(
                  old.schemaVersion(),
                  old.version(),
                  old.dataClass(),
                  old.catalog(),
                  old.sources(),
                  old.profiles(),
                  List.of(new RuleSetBundle.Assignment(p.id(), profile.id(), null)),
                  old.laborTariffs(),
                  old.kits());
          validator.validateShape(test);
          validator.validateProducts(test, List.of(facts(p)));
        }
      } catch (RuleSetImportException | IllegalArgumentException e) {
        blockers.add("Профиль несовместим с данными товара: " + e.getMessage());
      }
    }
    return new Readiness(
        blockers.isEmpty(),
        blockers,
        review == null ? null : UUID.fromString(review.path("id").asText()),
        base == null ? null : UUID.fromString(base.path("id").asText()));
  }

  public JsonNode publish(UUID id, String match, String actor, String requestId) {
    return store.transaction(
        () -> {
          var p = products.matched(id, match);
          var prior = store.one("admin_publication", query("version_id", p.versionId().toString()));
          if (prior != null) return store.tree(prior);
          var ready = inspect(p);
          if (!ready.ready())
            throw new AdminException(
                422,
                "not_ready",
                "Карточка не готова.",
                ready.blockers().stream()
                    .map(x -> new AdminException.Detail("readiness", "blocked", x))
                    .toList());
          var base = base();
          var review = store.tree(store.id("admin_label_review", ready.reviewId()));
          var s = p.snapshot();
          String release = UUID.randomUUID().toString(),
              rules = UUID.randomUUID().toString(),
              version = "admin-catalog-" + release,
              ruleVersion = "admin-rules-" + rules;
          String fingerprint = sha(base.toString() + p.versionId() + review.toString());
          var manifest = (ObjectNode) base.path("manifest").deepCopy();
          manifest
              .put("baseReleaseId", ready.baseReleaseId().toString())
              .put("adminVersionId", p.versionId().toString())
              .put("reviewId", ready.reviewId().toString())
              .put("mappingVersion", AdminReviewService.MAPPING);
          store.insert(
              "catalog_release",
              new Document("_id", release)
                  .append("version", version)
                  .append("fingerprint", fingerprint)
                  .append("manifest", store.document(manifest)));
          for (var d :
              store.find(
                  "product_revision", query("releaseId", ready.baseReleaseId().toString()))) {
            if (d.getString("id").equals(id.toString())) continue;
            d.put("_id", UUID.randomUUID().toString());
            d.put("releaseId", release);
            store.insert("product_revision", d);
          }
          List<String> works = new ArrayList<>();
          for (var work : review.path("java_works")) works.add(work.asText());
          var product =
              new ProductView(
                  id,
                  s.path("name").asText(),
                  value(s, "category"),
                  value(s, "kind"),
                  decimal(s, "price").toPlainString(),
                  decimal(s, "packageSize"),
                  value(s, "unit"),
                  value(s, "saleUnit"),
                  works,
                  bool(s, "wet"),
                  bool(s, "highLoad"),
                  bool(s, "unheated"),
                  s.path("description").asText("")
                      + "\n"
                      + s.path("application").asText("")
                      + "\n"
                      + s.path("limitations").asText(""),
                  "ТЕСТОВЫЕ / admin-reviewed:" + ready.reviewId(),
                  decimal(s, "consumption"),
                  decimal(s, "rollWidth"),
                  decimal(s, "rollLength"),
                  decimal(s, "rapport"),
                  value(s, "materialFamily"));
          var doc =
              store
                  .document(product)
                  .append("_id", UUID.randomUUID().toString())
                  .append("releaseId", release);
          doc.put("price", MongoStore.decimal(new BigDecimal(product.price())));
          store.insert("product_revision", doc);
          for (var d :
              store.find("source_record", query("releaseId", ready.baseReleaseId().toString()))) {
            if (p.sourceNamespace().equals(d.getString("namespace"))
                && p.externalId().equals(d.getString("externalId"))) continue;
            d.put("_id", UUID.randomUUID().toString());
            d.put("releaseId", release);
            store.insert("source_record", d);
          }
          store.insert(
              "source_record",
              new Document("_id", UUID.randomUUID().toString())
                  .append("releaseId", release)
                  .append("productId", id.toString())
                  .append("namespace", p.sourceNamespace())
                  .append("externalId", p.externalId())
                  .append("raw", store.document(s)));
          var bundle = (ObjectNode) base.path("bundle").deepCopy();
          bundle.put("version", ruleVersion);
          ((ObjectNode) bundle.path("catalog"))
              .put("version", version)
              .put("fingerprint", fingerprint);
          var assignments = bundle.putArray("assignments");
          for (var a : base.path("bundle").path("assignments"))
            if (!a.path("productId").asText().equals(id.toString())) assignments.add(a.deepCopy());
          assignments
              .addObject()
              .put("productId", id.toString())
              .put("profileId", review.path("profile_id").asText())
              .putNull("blockedReason")
              .put("priority", 100);
          var typed = json.treeToValue(bundle, RuleSetBundle.class);
          validator.validateShape(typed);
          validator.validateProducts(
              typed,
              store.find("product_revision", query("releaseId", release)).stream()
                  .map(d -> RuleSetImportService.facts(CatalogReadService.view(store, d)))
                  .toList());
          store.insert(
              "rule_set",
              new Document("_id", rules)
                  .append("catalogReleaseId", release)
                  .append("version", ruleVersion)
                  .append("fingerprint", sha(bundle.toString()))
                  .append("bundle", store.document(bundle)));
          // One CAS switches the pair. Concurrent publications cause a transaction retry.
          if (store.update(
                  "publication_pointer",
                  query("_id", "active")
                      .addCriteria(
                          Criteria.where("catalogReleaseId").is(ready.baseReleaseId().toString()))
                      .addCriteria(Criteria.where("ruleSetId").is(base.path("rule_id").asText())),
                  new Update()
                      .set("catalogReleaseId", release)
                      .set("ruleSetId", rules)
                      .inc("generation", 1))
              != 1)
            throw new AdminException(
                409, "publication_conflict", "Активная публикация изменилась.");
          String pubId = UUID.randomUUID().toString();
          var publication =
              new Document("_id", pubId)
                  .append("id", pubId)
                  .append("product_id", id.toString())
                  .append("version_id", p.versionId().toString())
                  .append("review_id", ready.reviewId().toString())
                  .append("release_id", release)
                  .append("rule_set_id", rules)
                  .append("created_by", actor)
                  .append("created_at", java.time.Instant.now().toString());
          store.insert("admin_publication", publication);
          products.touch(p, "published", actor);
          products.audit(id, "publish", actor, requestId, new Document("publicationId", pubId));
          return store.tree(publication);
        });
  }

  private JsonNode base() {
    var p = store.id("publication_pointer", "active");
    if (p == null) return null;
    var c = store.id("catalog_release", p.getString("catalogReleaseId"));
    var r = store.id("rule_set", p.getString("ruleSetId"));
    if (c == null || r == null) return null;
    var bundle = store.tree(r.get("bundle", Document.class));
    if (!c.getString("_id").equals(r.getString("catalogReleaseId"))
        || !c.getString("fingerprint").equals(bundle.path("catalog").path("fingerprint").asText()))
      return null;
    var n =
        json.createObjectNode()
            .put("id", c.getString("_id"))
            .put("fingerprint", c.getString("fingerprint"))
            .put("rule_id", r.getString("_id"))
            .put("rule_fingerprint", r.getString("fingerprint"));
    n.set("manifest", store.tree(c.get("manifest", Document.class)));
    n.set("bundle", bundle);
    return n;
  }

  private RuleSetValidator.ProductFacts facts(AdminProductService.Product p) {
    var s = p.snapshot();
    return new RuleSetValidator.ProductFacts(
        p.id(),
        value(s, "kind"),
        value(s, "unit"),
        decimal(s, "packageSize"),
        decimal(s, "consumption"),
        decimal(s, "rollWidth"),
        decimal(s, "rollLength"),
        decimal(s, "rapport"));
  }

  private static boolean confirmed(JsonNode s, String key) {
    return s.path(key).path("state").asText().equals("confirmed");
  }

  private static String value(JsonNode s, String key) {
    return confirmed(s, key) ? s.path(key).path("value").asText() : null;
  }

  private static BigDecimal decimal(JsonNode s, String key) {
    return confirmed(s, key) ? new BigDecimal(s.path(key).path("value").asText()) : null;
  }

  private static Boolean bool(JsonNode s, String key) {
    return confirmed(s, key) ? s.path(key).path("value").asBoolean() : null;
  }

  public record Readiness(
      boolean ready, List<String> blockers, UUID reviewId, UUID baseReleaseId) {}
}
