package ru.repaircalc.persistence;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"test", "postgres-test"})
@Import(PostgreSQLAvailabilityIT.PostgresConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PostgreSQLAvailabilityIT {
    @Autowired
    private PostgreSQLContainer postgres;

    @Value("${local.server.port}")
    private int port;

    @TestBean(name = "calculationData", methodName = "publishedCalculationData")
    private HealthIndicator calculationData;

    @Test
    void databaseLossFailsReadinessButKeepsProcessLive() throws Exception {
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            assertProbe(client, "/actuator/health/readiness", 200, "UP");
            assertProbe(client, "/actuator/health/liveness", 200, "UP");

            // This class owns a distinct container and context. No other test loses its database.
            postgres.stop();

            assertProbe(client, "/actuator/health/readiness", 503, "DOWN");
            assertProbe(client, "/actuator/health/liveness", 200, "UP");
        }
    }

    private void assertProbe(HttpClient client, String path, int status, String health) throws Exception {
        var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(path).isEqualTo(status);
        assertThat(response.body()).isEqualTo("{\"status\":\"" + health + "\"}");
    }

    private static HealthIndicator publishedCalculationData() {
        // This foundation test isolates database health; product API IT exercises the real data indicator.
        return () -> Health.up().build();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class PostgresConfiguration {
        @Bean
        @ServiceConnection
        PostgreSQLContainer postgres() {
            return new PostgreSQLContainer("postgres:17.11")
                    .withDatabaseName("repaircalc_availability")
                    .withUsername("repaircalc_test")
                    .withPassword("isolated-test-password")
                    .withUrlParam("connectTimeout", "2")
                    .withUrlParam("socketTimeout", "2");
        }
    }
}
