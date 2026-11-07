package ru.repaircalc.admin;

import static ru.repaircalc.storage.MongoStore.*;

import java.math.BigDecimal;
import java.util.*;
import org.bson.Document;
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
  private static final String AUTOMATIC_MAPPING = "classifier-java-auto-v1";
  private final MongoStore store;
  private final AdminProductService products;
  private final JsonMapper json;
  private final RuleSetValidator validator;
  private final AdminReviewService mapping;

  public AdminPublicationService(
      MongoStore store, AdminProductService products, JsonMapper json, RuleSetValidator validator,
      AdminReviewService mapping) {
    this.store = store;
    this.products = products;
    this.json = json;
    this.validator = validator;
    this.mapping = mapping;
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
    var base = base();
    if (base == null) blockers.add("Нет активной совместимой пары каталога и правил.");
    JsonNode review = automaticMapping(p, base, blockers);
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
        null,
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
          var mappingBlockers = new ArrayList<String>();
          var review = (ObjectNode) automaticMapping(p, base, mappingBlockers);
          if (review == null || !mappingBlockers.isEmpty())
            throw new AdminException(422, "not_ready", "Классификация изменилась.");
          String reviewId = UUID.randomUUID().toString();
          review.put("id", reviewId).put("product_id", id.toString())
              .put("version_id", p.versionId().toString()).put("mapping_version", AUTOMATIC_MAPPING)
              .put("created_by", actor).put("created_at", java.time.Instant.now().toString())
              .put("mode", "automatic_model_mapping");
          var reviewDocument = store.document(review);
          reviewDocument.put("_id", reviewId);
          store.insert("admin_label_review", reviewDocument);
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
              .put("reviewId", reviewId)
              .put("mappingVersion", AUTOMATIC_MAPPING);
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
                  "ТЕСТОВЫЕ / automatic-classification:" + reviewId,
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
                  .append("review_id", reviewId)
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

  private JsonNode automaticMapping(
      AdminProductService.Product p, JsonNode base, List<String> blockers) {
    var job = store.one("admin_classification_job", query("versionId", p.versionId().toString()));
    if (job == null || !p.id().toString().equals(job.getString("productId"))
        || !"succeeded".equals(job.getString("status")) || job.get("result") == null) {
      blockers.add("Для текущей версии нужна успешная классификация.");
      return null;
    }
    var result = store.tree(job.get("result", Document.class));
    var classification = result.path("classification");
    if (!AdminReviewService.TAXONOMY.equals(result.path("taxonomy_version").asText())
        || !"in_scope".equals(classification.path("scope").asText())) {
      blockers.add("Классификация не определила подходящий товар для расчёта.");
      return null;
    }
    var approved = json.createObjectNode();
    for (var group : List.of("work_types", "surface_locations", "substrates")) {
      var labels = approved.putArray(group);
      for (var label : classification.path(group)) labels.add(label.path("label").asText());
    }
    var properties = approved.putArray("properties");
    for (var key : classification.path("properties").propertyNames())
      properties.addObject().put("label", key)
          .put("state", classification.path("properties").path(key).path("state").asText());
    if (!confirmed(p.snapshot(), "kind") || !confirmed(p.snapshot(), "unit")) return null;
    String productKind = value(p.snapshot(), "kind");
    var works = mapping.mapWorks(approved, productKind);
    if (works.isEmpty()) {
      boolean needsSurface = Set.of("paint", "primer", "wallpaper", "covering", "adhesive")
          .contains(productKind) && approved.path("surface_locations").isEmpty();
      blockers.add(needsSurface
          ? "Не удалось сопоставить товар с расчётом: укажите в описании подходящую поверхность и повторите классификацию."
          : "Вид товара и назначение или поверхность из классификации несовместимы с поддерживаемым расчётом.");
      return null;
    }
    if (base == null) return null;
    var s = p.snapshot();
    String kind = value(s, "kind"), unit = value(s, "unit");
    BigDecimal consumption = decimal(s, "consumption");
    if (Set.of("paint", "primer", "adhesive").contains(kind)
        && (!confirmed(s, "consumption") || !confirmed(s, "consumptionUnit"))) {
      blockers.add("Укажите норму расхода и её единицу с источником.");
      return null;
    }
    var candidates = new ArrayList<RuleSetBundle.Profile>();
    var bundle = json.readValue(base.path("bundle").toString(), RuleSetBundle.class);
    for (var profile : bundle.profiles()) {
      if (!profile.strategy().name().equalsIgnoreCase(kind) || !profile.unit().equals(unit)) continue;
      if (profile.consumption() != null && (consumption == null
          || profile.consumption().compareTo(consumption) != 0
          || !profile.unit().equals(value(s, "consumptionUnit")))) continue;
      candidates.add(profile);
    }
    if (candidates.isEmpty()) {
      blockers.add("Нет расчётного профиля, совместимого с видом товара и нормой расхода.");
      return null;
    }
    candidates.sort(Comparator.comparingInt((RuleSetBundle.Profile x) -> x.id().length())
        .thenComparing(RuleSetBundle.Profile::id));
    var profile = candidates.getFirst();
    if (candidates.stream().anyMatch(x -> x.strategy() != profile.strategy()
        || x.reservePolicy() != profile.reservePolicy() || x.layerPolicy() != profile.layerPolicy()
        || !Objects.equals(x.trimAllowance(), profile.trimAllowance()))) {
      blockers.add("Найдено несколько разных расчётных профилей; автоматический выбор невозможен.");
      return null;
    }
    var review = json.createObjectNode().put("job_id", job.getString("id"))
        .put("profile_id", profile.id());
    review.set("approved", approved);
    var javaWorks = review.putArray("java_works");
    works.forEach(javaWorks::add);
    return review;
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
