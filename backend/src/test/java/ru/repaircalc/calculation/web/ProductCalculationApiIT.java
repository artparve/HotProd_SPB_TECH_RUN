package ru.repaircalc.calculation.web;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.aop.framework.Advised;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.postgresql.PostgreSQLContainer;
import ru.repaircalc.ingestion.CatalogImportService;
import ru.repaircalc.rules.RuleSetImportService;
import ru.repaircalc.rules.RuleSetReadService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"test", "postgres-test"})
@Import(ProductCalculationApiIT.PostgresConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ProductCalculationApiIT {
    private static final Path CATALOG = Path.of("data/catalog-v1.json");
    private static final Path MAPPINGS = Path.of("data/work-mappings-v1.json");
    private static final Path RULES = Path.of("data/rules-v1.json");
    private static final Path CONTRACT = Path.of("target/contracts/product-estimates-response.json");
    private static final String INPUT = "{\"area\":20,\"perimeter\":18,\"height\":2.7,\"reserve\":10,\"layers\":2,\"quantity\":6}";
    // Independently worked fixture examples; they intentionally do not call the calculation implementation.
    private static final Map<String, Expected> EXAMPLES = Map.ofEntries(
            Map.entry("demo-laminate", new Expected(10, "22", "22.2", "0.2", "18900.00")),
            Map.entry("demo-tile", new Expected(16, "22", "23.04", "1.04", "34560.00")),
            Map.entry("demo-vinyl", new Expected(10, "22", "22.4", "0.4", "36900.00")),
            Map.entry("demo-paint", new Expected(2, "5.28", "10", "4.72", "4980.00")),
            Map.entry("demo-wet-paint", new Expected(2, "6.16", "10", "3.84", "6580.00")),
            Map.entry("demo-primer", new Expected(1, "2.2", "5", "2.8", "690.00")),
            Map.entry("demo-wallpaper", new Expected(7, "19", "21", "2", "13230.00")),
            Map.entry("demo-glue", new Expected(4, "88", "100", "12", "3160.00")),
            Map.entry("demo-socket", new Expected(6, "6", "6", "0", "2094.00")),
            Map.entry("demo-wet-socket", new Expected(6, "6", "6", "0", "3540.00")),
            Map.entry("demo-cable", new Expected(1, "6.6", "50", "43.4", "5490.00")));

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    @Value("${local.server.port}")
    private int port;
    @Autowired
    private CatalogImportService catalogs;
    @Autowired
    private RuleSetImportService rules;
    @Autowired
    private RuleSetReadService rulesRead;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private JsonMapper json;
    @TempDir
    private Path temporary;

    @BeforeAll
    static void clearPreviousContractExport() throws IOException {
        Files.deleteIfExists(CONTRACT);
    }

    @BeforeEach
    void startWithoutAnActiveCatalog() {
        // Publication is append-only; inactive snapshots remain available for race/preservation assertions.
        jdbc.update("update repaircalc.catalog_release set active = false where active");
    }

    @Test
    void readinessRequiresCatalogAndMatchingRulesWhileLivenessAndCatalogHaveTheirOwnReadiness() throws Exception {
        assertStatus(get("/actuator/health/readiness"), 503);
        assertStatus(get("/actuator/health/liveness"), 200);
        assertStatus(get("/api/v1/products"), 503);

        var publication = publishCatalog("readiness", false);
        assertStatus(get("/api/v1/products"), 200);
        assertStatus(get("/actuator/health/readiness"), 503);
        assertStatus(post(productId("demo-laminate"), INPUT), 503);

        publishRules(publication);
        assertStatus(get("/actuator/health/readiness"), 200);
        assertStatus(get("/actuator/health/liveness"), 200);
        assertStatus(get("/api/v1/products"), 200);
        assertStatus(post(productId("demo-laminate"), INPUT), 200);
    }

    @Test
    void allElevenSupportedFixtureCardsReturnVerifiedSingleProductEstimatesAndExportActualResponses() throws Exception {
        var publication = catalogs.importFiles(CATALOG, MAPPINGS);
        var publishedRules = rules.importFile(RULES);
        assertThat(publishedRules.catalogReleaseId()).isEqualTo(publication.releaseId());
        var actualResponses = json.createArrayNode();
        int supported = 0;
        for (var product : fixture().path("products")) {
            String externalId = product.path("sources").get(0).path("externalId").asText();
            var example = EXAMPLES.get(externalId);
            if (example == null) {
                assertThat(externalId).isEqualTo("demo-unknown");
                continue;
            }
            var response = post(product.path("id").asText(), INPUT);
            assertStatus(response, 200);
            var estimate = json.readTree(response.body());
            assertEstimate(estimate, product.path("id").asText(), publication.version(), publishedRules.version());
            var line = estimate.path("lines").get(0);
            assertThat(line.path("quantity").isIntegralNumber()).isTrue();
            assertThat(line.path("quantity").asInt()).as(externalId).isEqualTo(example.quantity());
            assertNumber(line.path("need"), example.need());
            assertNumber(line.path("purchased"), example.purchased());
            assertNumber(line.path("remainder"), example.remainder());
            assertThat(line.path("total").asText()).isEqualTo(example.total());
            assertThat(estimate.path("materials").asText()).isEqualTo(example.total());
            assertThat(estimate.path("total").asText()).isEqualTo(example.total());
            assertThat(line.path("product").path("price").asText()).isEqualTo(product.path("price").asText());
            assertThat(line.path("product").path("source").asText()).contains("ТЕСТОВЫЕ");
            assertThat(line.path("formula").asText()).isNotBlank();
            assertThat(line.path("reason").asText()).isNotBlank();
            actualResponses.add(estimate);
            supported++;
        }
        assertThat(supported).isEqualTo(11);
        assertThat(actualResponses.size()).isEqualTo(11);

        // Export only the genuine HTTP responses after every independent numerical/contract assertion succeeds.
        Files.createDirectories(CONTRACT.getParent());
        Files.writeString(CONTRACT, json.writeValueAsString(actualResponses), StandardCharsets.UTF_8);
    }

    @Test
    void blockedCardUnknownUuidAndBrokenJsonReturnSafe422404And400() throws Exception {
        publishSnapshot("errors", false);
        assertSafeError(post(productId("demo-unknown"), INPUT), 422);
        assertSafeError(post(UUID.randomUUID().toString(), INPUT), 404);
        assertSafeError(post(productId("demo-laminate"), "{\"area\":"), 400);
    }

    @Test
    void decimalRequestAndResponsePreserveTheUnroundedPhysicalNeed() throws Exception {
        publishSnapshot("decimal-http", false);
        var response = post(productId("demo-paint"),
                "{\"area\":0.1234567891,\"perimeter\":18,\"height\":2.7,\"reserve\":7.5,\"layers\":3,\"quantity\":6}");
        assertStatus(response, 200);
        var line = json.readTree(response.body()).path("lines").get(0);
        assertNumber(line.path("need"), "0.0477777773817");
        assertNumber(line.path("remainder"), "4.9522222226183");
        assertThat(line.path("total").asText()).isEqualTo("2490.00");
    }

    @Test
    void invalidBoundsFractionalLayersAndFractionalSocketCountReturn422() throws Exception {
        publishSnapshot("validation", false);
        String covering = productId("demo-laminate");
        for (String invalid : new String[]{
                INPUT.replace("\"area\":20", "\"area\":0"),
                INPUT.replace("\"reserve\":10", "\"reserve\":31"),
                INPUT.replace("\"layers\":2", "\"layers\":0"),
                INPUT.replace("\"layers\":2", "\"layers\":1.5"),
                INPUT.replace("\"quantity\":6", "\"quantity\":5001"),
                "{}"}) {
            assertSafeError(post(covering, invalid), 422);
        }
        assertSafeError(post(productId("demo-socket"), INPUT.replace("\"quantity\":6", "\"quantity\":6.5")), 422);
    }

    @Test
    void aNewCatalogWithoutMatchingRulesFailsClosedAndPreservesTheOldPublishedSnapshot() throws Exception {
        var original = publishSnapshot("old-complete", false);
        var oldResponse = post(productId("demo-laminate"), INPUT);
        assertStatus(oldResponse, 200);

        var next = publishCatalog("new-without-rules", true);
        assertThat(next.releaseId()).isNotEqualTo(original.catalog().releaseId());
        assertStatus(get("/api/v1/products"), 200);
        assertSafeError(post(productId("demo-laminate"), INPUT), 503);
        assertStatus(get("/actuator/health/readiness"), 503);
        assertStatus(get("/actuator/health/liveness"), 200);
        assertThat(jdbc.queryForObject("select status from repaircalc.catalog_release where id = ?",
                String.class, original.catalog().releaseId())).isEqualTo("PUBLISHED");
        assertThat(jdbc.queryForObject("select price::text from repaircalc.product_revision where release_id = ? and product_id = ?",
                String.class, original.catalog().releaseId(), UUID.fromString(productId("demo-laminate")))).isEqualTo("1890.00");
        assertThat(jdbc.queryForObject("select status from repaircalc.rule_set where id = ?",
                String.class, original.rules().ruleSetId())).isEqualTo("PUBLISHED");

        var nextRules = publishRules(next);
        var after = post(productId("demo-laminate"), INPUT);
        assertStatus(after, 200);
        var estimate = json.readTree(after.body());
        assertEstimate(estimate, productId("demo-laminate"), next.version(), nextRules.version());
        assertThat(estimate.path("lines").get(0).path("product").path("price").asText()).isEqualTo("1999.99");
        assertStatus(get("/actuator/health/readiness"), 200);
    }

    @Test
    void publicationBetweenSnapshotQueriesCannotMixOldProductWithNewCatalogOrRules() throws Exception {
        var old = publishSnapshot("race-old", false);
        var productId = productId("demo-laminate");
        AtomicBoolean switched = new AtomicBoolean();
        var proxy = (Advised) rulesRead;
        try (var publisher = Executors.newSingleThreadExecutor()) {
            MethodInterceptor barrier = invocation -> {
                if (invocation.getMethod().getName().equals("activeForCatalog")
                        && old.catalog().releaseId().equals(invocation.getArguments()[0])
                        && switched.compareAndSet(false, true)) {
                    publisher.submit(() -> publishSnapshot("race-new", true)).get(30, TimeUnit.SECONDS);
                }
                return invocation.proceed();
            };
            proxy.addAdvice(0, barrier);
            try {
                var response = post(productId, INPUT);
                assertStatus(response, 200);
                assertThat(switched).isTrue();
                var estimate = json.readTree(response.body());
                assertEstimate(estimate, productId, old.catalog().version(), old.rules().version());
                assertThat(estimate.path("lines").get(0).path("product").path("price").asText()).isEqualTo("1890.00");
                assertThat(estimate.path("total").asText()).isEqualTo("18900.00");
            } finally {
                proxy.removeAdvice(barrier);
            }
        }
        var after = post(productId, INPUT);
        assertStatus(after, 200);
        var current = json.readTree(after.body());
        assertThat(current.path("versions").path("catalog").asText()).isNotEqualTo(old.catalog().version());
        assertThat(current.path("versions").path("rules").asText()).isNotEqualTo(old.rules().version());
        assertThat(current.path("lines").get(0).path("product").path("price").asText()).isEqualTo("1999.99");
        assertThat(current.path("total").asText()).isEqualTo("19999.90");
    }

    private void assertEstimate(JsonNode estimate, String productId, String catalogVersion, String rulesVersion) {
        assertThat(UUID.fromString(estimate.path("id").asText())).isNotNull();
        assertThat(estimate.path("createdAt").asText()).endsWith("Z");
        assertThat(Instant.parse(estimate.path("createdAt").asText())).isNotNull();
        assertThat(estimate.path("mode").asText()).isEqualTo("api");
        assertThat(estimate.path("lines").isArray()).isTrue();
        assertThat(estimate.path("lines").size()).isEqualTo(1);
        assertThat(estimate.path("lines").get(0).path("product").path("id").asText()).isEqualTo(productId);
        assertThat(estimate.path("labor").asText()).isEqualTo("0.00");
        assertThat(estimate.path("versions").path("catalog").asText()).isEqualTo(catalogVersion);
        assertThat(estimate.path("versions").path("rules").asText()).isEqualTo(rulesVersion);
        assertThat(estimate.path("versions").path("model").asText()).isEqualTo("not-used");
        assertThat(estimate.path("warnings").isArray()).isTrue();
        assertThat(estimate.path("warnings").isEmpty()).isFalse();
    }

    private void assertSafeError(HttpResponse<String> response, int status) throws Exception {
        assertStatus(response, status);
        var error = json.readTree(response.body());
        assertThat(error.path("code").asText()).isNotBlank();
        assertThat(error.path("message").asText()).isNotBlank();
        assertThat(response.body()).doesNotContain("jdbc:", "password", "stackTrace", "org.springframework", "repaircalc_test");
    }

    private void assertStatus(HttpResponse<String> response, int status) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("application/json");
        assertThat(response.headers().firstValue("X-Request-ID")).isPresent();
        assertThat(UUID.fromString(response.headers().firstValue("X-Request-ID").orElseThrow())).isNotNull();
    }

    private static void assertNumber(JsonNode value, String expected) {
        assertThat(value.isNumber()).isTrue();
        assertThat(new BigDecimal(value.asText())).isEqualByComparingTo(expected);
    }

    private HttpResponse<String> post(String id, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/product/" + id + "/calc"))
                .timeout(Duration.ofSeconds(40)).header("Accept", "application/json")
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10)).header("Accept", "application/json").GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private JsonNode fixture() throws IOException {
        return json.readTree(Files.readString(CATALOG, StandardCharsets.UTF_8));
    }

    private String productId(String externalId) throws IOException {
        for (var product : fixture().path("products")) {
            if (product.path("sources").get(0).path("externalId").asText().equals(externalId)) {
                return product.path("id").asText();
            }
        }
        throw new AssertionError("Missing fixture product " + externalId);
    }

    private Snapshot publishSnapshot(String label, boolean changedPrices) throws Exception {
        var publication = publishCatalog(label, changedPrices);
        return new Snapshot(publication, publishRules(publication));
    }

    private CatalogImportService.ImportResult publishCatalog(String label, boolean changedPrices) throws Exception {
        var catalog = (ObjectNode) fixture();
        var mappings = (ObjectNode) json.readTree(Files.readString(MAPPINGS, StandardCharsets.UTF_8));
        String version = label + "-" + UUID.randomUUID();
        catalog.put("version", version);
        mappings.put("version", "mappings-" + version);
        if (changedPrices) {
            for (var product : catalog.path("products")) {
                ((ObjectNode) product).put("price", "1999.99");
            }
        }
        Path catalogFile = temporary.resolve(version + "-catalog.json");
        Path mappingsFile = temporary.resolve(version + "-mappings.json");
        Files.writeString(catalogFile, json.writeValueAsString(catalog), StandardCharsets.UTF_8);
        Files.writeString(mappingsFile, json.writeValueAsString(mappings), StandardCharsets.UTF_8);
        return catalogs.importFiles(catalogFile, mappingsFile);
    }

    private RuleSetImportService.ImportResult publishRules(CatalogImportService.ImportResult catalog) throws IOException {
        var bundle = (ObjectNode) json.readTree(Files.readString(RULES, StandardCharsets.UTF_8));
        bundle.put("version", "rules-" + catalog.version());
        ((ObjectNode) bundle.path("catalog")).put("version", catalog.version()).put("fingerprint", catalog.fingerprint());
        Path file = temporary.resolve("rules-" + catalog.releaseId() + ".json");
        Files.writeString(file, json.writeValueAsString(bundle), StandardCharsets.UTF_8);
        return rules.importFile(file);
    }

    private record Expected(int quantity, String need, String purchased, String remainder, String total) { }
    private record Snapshot(CatalogImportService.ImportResult catalog, RuleSetImportService.ImportResult rules) { }

    @TestConfiguration(proxyBeanMethods = false)
    static class PostgresConfiguration {
        @Bean
        @ServiceConnection
        PostgreSQLContainer postgres() {
            return new PostgreSQLContainer("postgres:17.11")
                    .withDatabaseName("repaircalc_product_calculation_api")
                    .withUsername("repaircalc_test")
                    .withPassword("isolated-test-password");
        }
    }
}
