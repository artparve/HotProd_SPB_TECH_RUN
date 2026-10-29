package ru.repaircalc.catalog.web;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;

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
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"test", "postgres-test"})
@Import(CatalogDatabaseUnavailableIT.PostgresConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CatalogDatabaseUnavailableIT {
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    @Value("${local.server.port}")
    private int port;
    @Autowired
    private CatalogImportService importer;
    @Autowired
    private PostgreSQLContainer postgres;
    @Autowired
    private JsonMapper json;

    @Test
    void stoppedDatabaseReturnsSafe503FromTheRealTransactionProxy() throws Exception {
        importer.importFiles(Path.of("data/catalog-v1.json"), Path.of("data/work-mappings-v1.json"));
        assertThat(requestProducts().statusCode()).isEqualTo(200);

        postgres.stop();

        var response = requestProducts();
        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.headers().firstValue("X-Request-ID")).isPresent();
        var body = json.readTree(response.body());
        assertThat(body.path("code").asText()).isEqualTo("catalog_unavailable");
        assertThat(body.path("message").asText()).isEqualTo("Catalog is temporarily unavailable.");
        assertThat(body.has("stackTrace")).isFalse();
        assertThat(body.has("exception")).isFalse();
        assertThat(response.body()).doesNotContain("jdbc:", "isolated-test-password");
    }

    private HttpResponse<String> requestProducts() throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/products"))
                .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class PostgresConfiguration {
        @Bean
        @ServiceConnection
        PostgreSQLContainer postgres() {
            return new PostgreSQLContainer("postgres:17.11")
                    .withDatabaseName("repaircalc_catalog_unavailable")
                    .withUsername("repaircalc_test")
                    .withPassword("isolated-test-password");
        }
    }
}
