package ru.repaircalc.ingestion;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import jakarta.validation.Validation;
import java.nio.file.*;
import java.util.*;
import java.util.function.Supplier;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.core.query.*;
import ru.repaircalc.rules.*;
import ru.repaircalc.storage.MongoStore;
import tools.jackson.databind.json.JsonMapper;

class CatalogExpansionServiceTest {
  private final JsonMapper json = JsonMapper.builder().build();
  private final Map<String, List<Document>> database = new HashMap<>();
  private final MongoStore store = spy(new MongoStore(null, mock(MongoDatabaseFactory.class), json, 3));
  private final CatalogImportService importer = mock(CatalogImportService.class);
  private final Path data = Path.of("../data");

  private List<Document> collection(String name) { return database.computeIfAbsent(name, k -> new ArrayList<>()); }
  private boolean matches(Document doc, Query query) {
    return query.getQueryObject().entrySet().stream().allMatch(e -> Objects.equals(doc.get(e.getKey()), e.getValue()));
  }

  @SuppressWarnings("unchecked")
  private CatalogExpansionService fixture() throws Exception {
    doAnswer(call -> ((Supplier<?>) call.getArgument(0)).get()).when(store).transaction(any());
    doAnswer(call -> collection(call.getArgument(0)).stream().filter(d -> matches(d, call.getArgument(1)))
        .map(Document::new).toList()).when(store).find(anyString(), any(Query.class));
    doAnswer(call -> collection(call.getArgument(0)).stream().filter(d -> matches(d, call.getArgument(1)))
        .findFirst().map(Document::new).orElse(null)).when(store).one(anyString(), any(Query.class));
    doAnswer(call -> { Document doc = call.getArgument(1); collection(call.getArgument(0)).add(new Document(doc)); return doc; })
        .when(store).insert(anyString(), any(Document.class));
    doAnswer(call -> {
      var doc = collection(call.getArgument(0)).stream().filter(d -> matches(d, call.getArgument(1))).findFirst().orElseThrow();
      var update = ((Update) call.getArgument(2)).getUpdateObject();
      doc.putAll(update.get("$set", Document.class));
      doc.put("generation", ((Number) doc.get("generation")).intValue() + 1);
      return 1L;
    }).when(store).update(anyString(), any(Query.class), any(Update.class));
    var rules = json.readValue(Files.readAllBytes(data.resolve("rules-expanded.json")), RuleSetBundle.class);
    var stagedId = UUID.randomUUID();
    when(importer.importFiles(any(), any())).thenReturn(new CatalogImportService.ImportResult(
        stagedId, rules.catalog().version(), rules.catalog().fingerprint(), 112, false));
    var catalog = json.readValue(Files.readAllBytes(data.resolve("catalog-expanded.json")), PreparedCatalog.Catalog.class);
    var mappings = json.readValue(Files.readAllBytes(data.resolve("work-mappings-expanded.json")), PreparedCatalog.Mappings.class);
    for (var product : catalog.products()) {
      var doc = store.document(product);
      doc.remove("sources");
      doc.put("works", mappings.mappings().stream().filter(m -> m.productId().equals(product.id())).findFirst().orElseThrow().works());
      doc.put("releaseId", stagedId.toString());
      doc.put("_id", UUID.randomUUID().toString());
      collection("product_revision").add(doc);
      for (var source : product.sources()) collection("source_record").add(new Document("releaseId", stagedId.toString())
          .append("productId", product.id().toString()).append("namespace", source.namespace())
          .append("externalId", source.externalId()).append("raw", store.value(source.raw())));
    }
    var originalRules = json.readValue(Files.readAllBytes(data.resolve("rules-v1.json")), RuleSetBundle.class);
    // Existing operator modifications must win over the seed, including same-ID products.
    var original = new ArrayList<>(collection("product_revision").subList(0, 12));
    for (var d : original) { var copy = new Document(d); copy.put("releaseId", "active-before"); collection("product_revision").add(copy); }
    var modified = collection("product_revision").get(112);
    modified.put("name", "Operator-owned name");
    modified.put("price", MongoStore.decimal(new java.math.BigDecimal("9876.54")));
    collection("catalog_release").add(new Document("_id", "active-before").append("fingerprint", originalRules.catalog().fingerprint())
        .append("manifest", new Document("taxonomyVersion", "work-taxonomy-v1")));
    collection("rule_set").add(new Document("_id", "rules-before").append("catalogReleaseId", "active-before")
        .append("fingerprint", "operator-rule-fingerprint").append("bundle", store.document(originalRules)));
    collection("publication_pointer").add(new Document("_id", "active").append("catalogReleaseId", "active-before")
        .append("ruleSetId", "rules-before").append("generation", 1));
    try (var factory = Validation.buildDefaultValidatorFactory()) {
      return new CatalogExpansionService(store, importer, new RuleSetValidator(factory.getValidator()), json);
    }
  }

  @Test
  void addsExactlyOneHundredAndPreservesOperatorProductsRulesAndOldSnapshots() throws Exception {
    var service = fixture();
    var old = store.find("product_revision", MongoStore.query("releaseId", "active-before"));
    var oldRules = store.tree(store.id("rule_set", "rules-before").get("bundle", Document.class));
    assertThat(service.expand(data.resolve("catalog-expanded.json"), data.resolve("work-mappings-expanded.json"), data.resolve("rules-expanded.json"))).isEqualTo(100);
    var pointer = store.id("publication_pointer", "active");
    var current = store.find("product_revision", MongoStore.query("releaseId", pointer.getString("catalogReleaseId")));
    assertThat(current).hasSize(112);
    for (var before : old) {
      var after = current.stream().filter(d -> d.getString("id").equals(before.getString("id"))).findFirst().orElseThrow();
      var expected = new Document(before); expected.remove("_id"); expected.remove("releaseId");
      var actual = new Document(after); actual.remove("_id"); actual.remove("releaseId");
      assertThat(actual).isEqualTo(expected);
    }
    var mergedRules = store.tree(store.id("rule_set", pointer.getString("ruleSetId")).get("bundle", Document.class));
    assertThat(mergedRules.path("laborTariffs")).isEqualTo(oldRules.path("laborTariffs"));
    assertThat(mergedRules.path("kits")).isEqualTo(oldRules.path("kits"));
    assertThat(mergedRules.path("assignments").valueStream().limit(12).toList()).isEqualTo(oldRules.path("assignments").valueStream().toList());
    assertThat(store.find("product_revision", MongoStore.query("releaseId", "active-before"))).isEqualTo(old);
    assertThat(service.expand(data.resolve("catalog-expanded.json"), data.resolve("work-mappings-expanded.json"), data.resolve("rules-expanded.json"))).isZero();
    assertThat(store.id("publication_pointer", "active")).isEqualTo(pointer);
  }

  @Test
  void noActivePublicationNeverFallsBackToReplacingTheCatalog() throws Exception {
    var service = fixture(); collection("publication_pointer").clear();
    assertThatThrownBy(() -> service.expand(data.resolve("catalog-expanded.json"), data.resolve("work-mappings-expanded.json"), data.resolve("rules-expanded.json")))
        .isInstanceOf(CatalogImportException.class).hasMessageContaining("Initialize");
  }
}
