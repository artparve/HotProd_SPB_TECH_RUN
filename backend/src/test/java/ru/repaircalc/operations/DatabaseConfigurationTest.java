package ru.repaircalc.operations;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer());

    @Test
    void unitProfileCannotConnectToAnExternalDatabase() {
        runner.withPropertyValues("spring.profiles.active=test").run(context -> {
            var environment = context.getEnvironment();
            var excluded = Binder.get(environment)
                    .bind("spring.autoconfigure.exclude", Bindable.listOf(String.class)).orElse(List.of());
            assertThat(excluded).contains(
                    "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
                    "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration");
            assertThat(environment.getProperty("management.endpoint.health.group.readiness.include"))
                    .isEqualTo("readinessState,calculationData");
        });
    }

    @Test
    void postgresProfileRestoresRealDatabaseInfrastructure() {
        runner.withPropertyValues("spring.profiles.active=test,postgres-test").run(context -> {
            var environment = context.getEnvironment();
            var excluded = Binder.get(environment)
                    .bind("spring.autoconfigure.exclude", Bindable.listOf(String.class)).orElse(List.of());
            assertThat(excluded).isEmpty();
            assertThat(environment.getProperty("management.endpoint.health.group.readiness.include"))
                    .isEqualTo("readinessState,calculationData,db");
            assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
            assertThat(environment.getProperty("spring.flyway.default-schema")).isEqualTo("public");
            assertThat(environment.getProperty("spring.datasource.password"))
                    .isEqualTo("unused-service-connection-password");
        });
    }
}
