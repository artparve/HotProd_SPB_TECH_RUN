package ru.repaircalc.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.stream.Collectors;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import ru.repaircalc.calculation.domain.*;
import ru.repaircalc.rules.*;
import ru.repaircalc.storage.MongoStore;
import tools.jackson.databind.json.JsonMapper;

class BundledDataTest {
  private final JsonMapper json = RuleSetImportService.strictMapper(JsonMapper.builder().build());

  @Test
  void expandedCatalogHasValidIdentitiesMappingsAndExactRuleFingerprint() throws Exception {
    byte[] catalogBytes = Files.readAllBytes(Path.of("../data/catalog-expanded.json"));
    byte[] mappingBytes = Files.readAllBytes(Path.of("../data/work-mappings-expanded.json"));
    var catalog = json.readValue(catalogBytes, PreparedCatalog.Catalog.class);
    var mappings = json.readValue(mappingBytes, PreparedCatalog.Mappings.class);
    var rules = json.readValue(Files.readAllBytes(Path.of("../data/rules-expanded.json")), RuleSetBundle.class);
    try (var factory = Validation.buildDefaultValidatorFactory()) {
      var validator = factory.getValidator();
      assertThat(validator.validate(catalog)).isEmpty();
      assertThat(validator.validate(mappings)).isEmpty();
      var ruleValidator = new RuleSetValidator(validator);
      ruleValidator.validateShape(rules);
      ruleValidator.validateProducts(rules, catalog.products().stream().map(p ->
          new RuleSetValidator.ProductFacts(p.id(), p.kind(), p.unit(), p.packageSize(),
              p.consumption(), p.rollWidth(), p.rollLength(), p.rapport())).toList());
    }
    assertThat(catalog.products()).hasSize(112);
    assertThat(catalog.products().stream().map(PreparedCatalog.Product::id)).doesNotHaveDuplicates();
    assertThat(catalog.products().stream().map(PreparedCatalog.Product::name)).doesNotHaveDuplicates();
    assertThat(mappings.mappings().stream().map(PreparedCatalog.Mapping::productId))
        .containsExactlyInAnyOrderElementsOf(catalog.products().stream().map(PreparedCatalog.Product::id).toList());
    assertThat(rules.catalog().version()).isEqualTo(catalog.version());
    assertThat(rules.catalog().fingerprint()).isEqualTo(MongoStore.sha(
        CatalogImportService.IMPORTER_VERSION + "\n" + ImportDigest.text(catalogBytes) + "\n" + ImportDigest.text(mappingBytes)));
  }

  @Test
  void everyNewProductCanBeCalculatedByJava() throws Exception {
    var catalog = json.readValue(Files.readAllBytes(Path.of("../data/catalog-expanded.json")), PreparedCatalog.Catalog.class);
    var rules = json.readValue(Files.readAllBytes(Path.of("../data/rules-expanded.json")), RuleSetBundle.class);
    var assignments = rules.assignments().stream().collect(Collectors.toMap(RuleSetBundle.Assignment::productId, a -> a));
    var profiles = rules.profiles().stream().collect(Collectors.toMap(RuleSetBundle.Profile::id, p -> p));
    var calculator = new MaterialCalculator();
    for (var p : catalog.products().subList(12, 112)) {
      var profile = profiles.get(assignments.get(p.id()).profileId());
      assertThat(profile).isNotNull();
      var result = calculator.calculate(new CalculationProduct(p.id(), p.kind(), p.price(), p.packageSize(),
          p.unit(), p.saleUnit(), p.consumption(), p.rollWidth(), p.rollLength(), p.rapport()),
          new CalculationInput(new BigDecimal("20"), new BigDecimal("18"), new BigDecimal("2.7"),
              new BigDecimal("10"), new BigDecimal("2"), new BigDecimal("6")), profile, "ТЕСТОВЫЕ нормы");
      assertThat(result.packages().total().amount()).isPositive();
    }
  }

  @Test
  void allBundledPhotosAreDistinctDecodableAndHaveCredits() throws Exception {
    Path root = Path.of("../data/inspirations");
    var manifest = json.readTree(Files.readString(root.resolve("manifest.json")));
    var hashes = new HashSet<String>();
    var ids = new HashSet<String>();
    var counts = new java.util.HashMap<String, Integer>();
    String credits = Files.readString(root.resolve("CREDITS.md"));
    assertThat(manifest.size()).isEqualTo(32);
    for (var entry : manifest) {
      Path image = root.resolve(entry.path("file").asText());
      byte[] bytes = Files.readAllBytes(image);
      assertThat(bytes.length).isBetween(1, 8388608);
      assertThat(hashes.add(MongoStore.sha(bytes))).isTrue();
      assertThat(ids.add(entry.path("id").asText())).isTrue();
      var decoded = ImageIO.read(image.toFile());
      assertThat(decoded).isNotNull();
      assertThat((long) decoded.getWidth() * decoded.getHeight()).isLessThanOrEqualTo(12000000);
      assertThat(credits).contains(image.getFileName().toString(), entry.path("sourceUrl").asText(), MongoStore.sha(bytes));
      counts.merge(entry.path("category").asText(), 1, Integer::sum);
    }
    assertThat(counts).containsExactlyInAnyOrderEntriesOf(java.util.Map.of("living", 7, "bedroom", 7, "kitchen", 7, "bathroom", 7, "children", 2, "balcony", 2));
  }
}
