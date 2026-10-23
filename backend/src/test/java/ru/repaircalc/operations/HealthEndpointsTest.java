package ru.repaircalc.operations;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import ru.repaircalc.ingestion.CatalogImportService;
import ru.repaircalc.catalog.CatalogReadService;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class HealthEndpointsTest {
    @MockitoBean
    private CatalogImportService importer;
    @MockitoBean
    private CatalogReadService catalog;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    @Value("${local.server.port}")
    private int port;

    @Test
    void liveProcessIsHealthyWithoutPublishedBusinessData() throws Exception {
        var response = get("/actuator/health/liveness");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("{\"status\":\"UP\"}");
    }

    @Test
    void readinessWaitsForPublishedCatalogAndRules() throws Exception {
        var response = get("/actuator/health/readiness");
        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.body()).isEqualTo("{\"status\":\"OUT_OF_SERVICE\"}");
    }

    @Test
    void onlyHealthIsExposedAndErrorResponsesHideInternalDetails() throws Exception {
        for (var path : new String[]{"/actuator/env", "/actuator/configprops", "/actuator/beans"}) {
            var response = get(path);
            assertThat(response.statusCode()).as(path).isEqualTo(404);
            assertThat(response.body()).doesNotContain("password", "exception", "trace", "org.springframework");
        }
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
}
