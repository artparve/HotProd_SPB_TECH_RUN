package ru.repaircalc.persistence;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import javax.sql.DataSource;

import jakarta.persistence.EntityManagerFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import ru.repaircalc.operations.CalculationDataHealthIndicator;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"test", "postgres-test"})
@Import(PostgreSQLFoundationIT.PostgresConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PostgreSQLFoundationIT {
    @Autowired
    private DataSource dataSource;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private Flyway flyway;

    @Value("${local.server.port}")
    private int port;

    @TestBean(methodName = "publishedCalculationData")
    private CalculationDataHealthIndicator calculationData;

    @Test
    void migratesFreshPostgresAndKeepsHistoryInPublic() throws Exception {
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
            assertThat(connection.getMetaData().getDatabaseMajorVersion()).isEqualTo(17);
            assertThat(connection.getMetaData().getDatabaseMinorVersion()).isEqualTo(11);
        }

        var jdbc = new JdbcTemplate(dataSource);
        assertThat(jdbc.queryForObject(
                "select count(*) from pg_namespace where nspname = 'repaircalc'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select count(*) from public.flyway_schema_history where version = '1' and success", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select to_regclass('repaircalc.flyway_schema_history') is null", Boolean.class))
                .isTrue();
        assertThat(flyway.getConfiguration().getDefaultSchema()).isEqualTo("public");
        assertThat(flyway.info().pending()).isEmpty();
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
    }

    @Test
    void repeatingMigrateLeavesSchemaAndHistoryUnchanged() {
        var jdbc = new JdbcTemplate(dataSource);
        var historyBefore = jdbc.queryForList(
                "select installed_rank, version, script, checksum, success from public.flyway_schema_history order by installed_rank");
        var schemaOidBefore = jdbc.queryForObject(
                "select oid::text from pg_namespace where nspname = 'repaircalc'", String.class);

        assertThat(flyway.migrate().migrationsExecuted).isZero();

        assertThat(jdbc.queryForList(
                "select installed_rank, version, script, checksum, success from public.flyway_schema_history order by installed_rank"))
                .isEqualTo(historyBefore);
        assertThat(jdbc.queryForObject(
                "select oid::text from pg_namespace where nspname = 'repaircalc'", String.class))
                .isEqualTo(schemaOidBefore);
    }

    @Test
    void jpaUsesPostgresWithSchemaValidationConfigured() {
        assertThat(entityManagerFactory.getProperties())
                .containsEntry("hibernate.hbm2ddl.auto", "validate")
                .containsEntry("hibernate.default_schema", "repaircalc");

        try (var entityManager = entityManagerFactory.createEntityManager()) {
            assertThat((String) entityManager.createNativeQuery("select version()").getSingleResult())
                    .startsWith("PostgreSQL 17.11");
            assertThat((String) entityManager.createNativeQuery(
                    "select nspname from pg_namespace where nspname = 'repaircalc'").getSingleResult())
                    .isEqualTo("repaircalc");
        }
    }

    @Test
    void liveDatabaseMakesReadinessHealthyWhenBusinessDataIsPublished() throws Exception {
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            var response = client.send(HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + port + "/actuator/health/readiness"))
                    .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).isEqualTo("{\"status\":\"UP\"}");
        }
    }

    private static CalculationDataHealthIndicator publishedCalculationData() {
        return new CalculationDataHealthIndicator() {
            @Override
            public Health health() {
                return Health.up().build();
            }
        };
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class PostgresConfiguration {
        @Bean
        @ServiceConnection
        PostgreSQLContainer postgres() {
            return new PostgreSQLContainer("postgres:17.11")
                    .withDatabaseName("repaircalc_integration")
                    .withUsername("repaircalc_test")
                    .withPassword("isolated-test-password");
        }
    }
}
