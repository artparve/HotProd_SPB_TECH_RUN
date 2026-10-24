package ru.repaircalc.calculation.web;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.postgresql.PostgreSQLContainer;
import ru.repaircalc.ingestion.CatalogImportService;
import ru.repaircalc.rules.RuleSetImportService;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"test", "postgres-test"})
@Import(ProductCalculationUnavailableIT.PostgresConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ProductCalculationUnavailableIT {
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    @Value("${local.server.port}")
    private int port;
    @Autowired
    private PostgreSQLContainer postgres;
    @Autowired
    private CatalogImportService catalogs;
    @Autowired
    private RuleSetImportService rules;
    @Autowired
    private JsonMapper json;

    @Test
    void losingTheOwnedDatabaseFailsCalculationAndReadinessSafelyWhileProcessRemainsLive() throws Exception {
        catalogs.importFiles(Path.of("data/catalog-v1.json"), Path.of("data/work-mappings-v1.json"));
        rules.importFile(Path.of("data/rules-v1.json"));
        assertThat(calculate().statusCode()).isEqualTo(200);
        assertThat(get("/actuator/health/readiness").statusCode()).isEqualTo(200);

        postgres.stop();

        var response = calculate();
        assertThat(response.statusCode()).as(response.body()).isEqualTo(503);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("application/json");
        assertThat(UUID.fromString(response.headers().firstValue("X-Request-ID").orElseThrow())).isNotNull();
        var body = json.readTree(response.body());
        assertThat(body.path("code").asText()).isNotBlank();
        assertThat(body.path("message").asText()).isNotBlank();
        assertThat(response.body()).doesNotContain("jdbc:", "password", "stackTrace", "org.springframework", "repaircalc_test");
        assertThat(get("/actuator/health/readiness").statusCode()).isEqualTo(503);
        var liveness = get("/actuator/health/liveness");
        assertThat(liveness.statusCode()).isEqualTo(200);
        assertThat(json.readTree(liveness.body()).path("status").asText()).isEqualTo("UP");
    }

    private HttpResponse<String> calculate() throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                        + "/api/v1/product/47f9b29f-8972-3c84-9103-6b3281bd9de7/calc"))
                .timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"area\":20,\"perimeter\":18,\"height\":2.7,\"reserve\":10,\"layers\":2,\"quantity\":6}"))
                .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(15)).header("Accept", "application/json").GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class PostgresConfiguration {
        @Bean
        @ServiceConnection
        PostgreSQLContainer postgres() {
            return new PostgreSQLContainer("postgres:17.11")
                    .withDatabaseName("repaircalc_product_calculation_unavailable")
                    .withUsername("repaircalc_test")
                    .withPassword("isolated-test-password")
                    .withUrlParam("connectTimeout", "2")
                    .withUrlParam("socketTimeout", "2");
        }
    }
}
