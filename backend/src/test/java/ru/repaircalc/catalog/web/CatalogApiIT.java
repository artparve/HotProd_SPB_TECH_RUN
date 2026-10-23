package ru.repaircalc.catalog.web;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.framework.Advised;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import ru.repaircalc.catalog.persistence.CatalogReleaseRepository;
import ru.repaircalc.catalog.persistence.ProductRevisionRepository;
import ru.repaircalc.ingestion.CatalogImportService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"test", "postgres-test"})
@Import(CatalogApiIT.PostgresConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CatalogApiIT {
    private static final Path CATALOG = Path.of("data/catalog-v1.json");
    private static final Path MAPPINGS = Path.of("data/work-mappings-v1.json");

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    @Value("${local.server.port}")
    private int port;
    @Autowired
    private CatalogImportService importer;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private JsonMapper json;
    @MockitoSpyBean
    private CatalogReleaseRepository releases;
    @Autowired
    private ProductRevisionRepository revisions;
    @TempDir
    private Path temporary;

    @BeforeAll
    static void removePreviousContractExport() throws IOException {
        Files.deleteIfExists(Path.of("target/contracts/products-response.json"));
    }

    @BeforeEach
    void clearActivePublication() {
        jdbc.update("update repaircalc.catalog_release set active = false where active");
    }

    @Test
    void noPublicationReturnsSafe503() throws Exception {
        var response = requestProducts();
        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.headers().firstValue("X-Request-ID")).isPresent();
        var body = json.readTree(response.body());
        assertThat(body.path("code").asText()).isEqualTo("catalog_unavailable");
        assertThat(body.path("message").asText()).isEqualTo("Catalog is temporarily unavailable.");
        assertThat(body.has("stackTrace")).isFalse();
    }

    @Test
    void publishedFixturesMatchFrontendShapeAndExportActualResponse() throws Exception {
        importer.importFiles(CATALOG, MAPPINGS);

        HttpResponse<String> result = productsResponse();
        JsonNode response = json.readTree(result.body());
        assertThat(response).isEqualTo(expectedFixtureResponse());
        assertThat(response.size()).isEqualTo(12);
        List<String> ids = new ArrayList<>();
        for (JsonNode product : response) {
            ids.add(product.path("id").asText());
            assertThat(product.path("price").asText()).matches("\\d+\\.\\d{2}");
            assertThat(product.path("source").asText()).startsWith("ТЕСТОВЫЕ данные, не актуальные цены/нормы;");
            assertThat(product.has("sources")).isFalse();
            assertThat(product.has("releaseId")).isFalse();
            assertThat(UUID.fromString(product.path("id").asText())).isNotNull();
            if (product.path("kind").asText().equals("wallpaper")) {
                assertThat(product.has("packageSize")).isTrue();
                assertThat(product.path("packageSize").isNull()).isTrue();
            }
            if (product.path("name").asText().equals("Лак универсальный, 0,9 л")) {
                for (String field : List.of("wet", "highLoad", "unheated")) {
                    assertThat(product.has(field)).isTrue();
                    assertThat(product.path(field).isNull()).isTrue();
                }
                assertThat(product.has("consumption")).isFalse();
            }
        }
        assertThat(ids).isSorted();

        // Written only after the real database-backed API response passes all fixture checks.
        Path export = Path.of("target/contracts/products-response.json");
        Files.createDirectories(export.getParent());
        Files.writeString(export, result.body(), StandardCharsets.UTF_8);
    }

    @Test
    void emptyPublishedCatalogReturnsAnEmptyArray() throws Exception {
        var files = versionedFiles("empty", true, false);
        importer.importFiles(files.catalog(), files.mappings());

        var response = productsResponse();
        assertThat(json.readTree(response.body())).isEqualTo(json.createArrayNode());
    }

    @Test
    void assignmentsUseTheTaxonomyRecordedInThePublishedManifest() throws Exception {
        jdbc.update("""
                insert into repaircalc.work_type(taxonomy_version, code, meaning)
                select 'work-taxonomy-v2', code, meaning from repaircalc.work_type where taxonomy_version = 'work-taxonomy-v1'
                """);
        var files = versionedFiles("new-taxonomy", false, false);
        var document = (ObjectNode) json.readTree(Files.readString(files.mappings()));
        document.put("taxonomyVersion", "work-taxonomy-v2");
        Files.writeString(files.mappings(), json.writeValueAsString(document));
        importer.importFiles(files.catalog(), files.mappings());
        var response = json.readTree(productsResponse().body());
        assertThat(response.size()).isEqualTo(12);
        for (var product : response) {
            assertThat(product.path("works").size()).isPositive();
        }
    }

    @Test
    void publicationSwitchBetweenQueriesCannotMixRevisionsAndMappings() throws Exception {
        var firstFiles = versionedFiles("before-switch", false, false);
        var first = importer.importFiles(firstFiles.catalog(), firstFiles.mappings());
        JsonNode original = json.readTree(productsResponse().body());
        var nextFiles = versionedFiles("after-switch", false, true);
        AtomicBoolean switched = new AtomicBoolean();
        clearInvocations(releases);

        try (var publisher = Executors.newSingleThreadExecutor()) {
            var repositoryProxy = (Advised) revisions;
            MethodInterceptor barrier = invocation -> {
                if (invocation.getMethod().getName().equals("findByReleaseIdOrderByProductIdAsc")
                        && first.releaseId().equals(invocation.getArguments()[0]) && switched.compareAndSet(false, true)) {
                    publisher.submit(() -> importer.importFiles(nextFiles.catalog(), nextFiles.mappings()))
                            .get(30, TimeUnit.SECONDS);
                }
                return invocation.proceed();
            };
            // Insert ahead of Spring Data's query interceptor, then invoke the real repository.
            repositoryProxy.addAdvice(0, barrier);
            try {
                JsonNode duringSwitch = json.readTree(productsResponse().body());
                assertThat(switched).isTrue();
                assertThat(duringSwitch).isEqualTo(original);
                verify(releases, times(1)).findByActiveTrue();
            } finally {
                repositoryProxy.removeAdvice(barrier);
            }
        }

        JsonNode afterSwitch = json.readTree(productsResponse().body());
        assertThat(afterSwitch.size()).isEqualTo(12);
        for (JsonNode product : afterSwitch) {
            assertThat(product.path("price").asText()).isEqualTo("1999.99");
            assertThat(product.path("works")).isEqualTo(json.createArrayNode().add("electrical"));
        }
    }

    private HttpResponse<String> productsResponse() throws Exception {
        var response = requestProducts();
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("application/json");
        assertThat(response.headers().firstValue("X-Request-ID")).isPresent();
        return response;
    }

    private HttpResponse<String> requestProducts() throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/products"))
                .timeout(Duration.ofSeconds(35)).GET().build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private ArrayNode expectedFixtureResponse() throws Exception {
        JsonNode catalog = json.readTree(Files.readString(CATALOG, StandardCharsets.UTF_8));
        JsonNode mappings = json.readTree(Files.readString(MAPPINGS, StandardCharsets.UTF_8));
        Map<String, List<String>> worksByProduct = new HashMap<>();
        for (JsonNode mapping : mappings.path("mappings")) {
            List<String> works = new ArrayList<>();
            mapping.path("works").forEach(work -> works.add(work.asText()));
            works.sort(String::compareTo);
            worksByProduct.put(mapping.path("productId").asText(), works);
        }
        List<ObjectNode> products = new ArrayList<>();
        for (JsonNode node : catalog.path("products")) {
            ObjectNode product = ((ObjectNode) node).deepCopy();
            product.remove("sources");
            product.set("works", json.valueToTree(worksByProduct.get(product.path("id").asText())));
            products.add(product);
        }
        products.sort(Comparator.comparing(product -> product.path("id").asText()));
        ArrayNode expected = json.createArrayNode();
        products.forEach(expected::add);
        return expected;
    }

    private ImportFiles versionedFiles(String label, boolean empty, boolean changed) throws Exception {
        ObjectNode catalog = (ObjectNode) json.readTree(Files.readString(CATALOG, StandardCharsets.UTF_8));
        ObjectNode mappings = (ObjectNode) json.readTree(Files.readString(MAPPINGS, StandardCharsets.UTF_8));
        String version = label + "-" + UUID.randomUUID();
        catalog.put("version", version);
        mappings.put("version", "mappings-" + version);
        if (empty) {
            catalog.set("products", json.createArrayNode());
            mappings.set("mappings", json.createArrayNode());
        } else if (changed) {
            for (JsonNode product : catalog.path("products")) {
                ((ObjectNode) product).put("price", "1999.99");
            }
            for (JsonNode mapping : mappings.path("mappings")) {
                ((ObjectNode) mapping).set("works", json.createArrayNode().add("electrical"));
            }
        }
        Path catalogFile = temporary.resolve(version + "-catalog.json");
        Path mappingFile = temporary.resolve(version + "-mappings.json");
        Files.writeString(catalogFile, json.writeValueAsString(catalog), StandardCharsets.UTF_8);
        Files.writeString(mappingFile, json.writeValueAsString(mappings), StandardCharsets.UTF_8);
        return new ImportFiles(catalogFile, mappingFile);
    }

    private record ImportFiles(Path catalog, Path mappings) { }

    @TestConfiguration(proxyBeanMethods = false)
    static class PostgresConfiguration {
        @Bean
        @ServiceConnection
        PostgreSQLContainer postgres() {
            return new PostgreSQLContainer("postgres:17.11")
                    .withDatabaseName("repaircalc_catalog_api")
                    .withUsername("repaircalc_test")
                    .withPassword("isolated-test-password");
        }
    }
}
