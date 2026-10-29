package ru.repaircalc.persistence;

import java.util.UUID;
import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles({"test", "postgres-test"})
@Import(AdminProductPersistenceIT.PostgresConfiguration.class)
class AdminProductPersistenceIT {
    @Autowired DataSource dataSource;
    @Autowired org.springframework.transaction.PlatformTransactionManager manager;

    @Test
    void draftsHaveStableIdentityAndImmutableVersionsAndAudit() {
        var jdbc = new JdbcTemplate(dataSource);
        var transactions = new TransactionTemplate(manager);
        var product = UUID.randomUUID();
        var first = UUID.randomUUID();
        transactions.executeWithoutResult(status -> {
            jdbc.update("insert into repaircalc.admin_product(id, source_namespace, external_id, current_version_id, created_by, updated_by) values (?, 'supplier', ?, ?, 'admin', 'admin')", product, product.toString(), first);
            jdbc.update("insert into repaircalc.admin_product_version(id, product_id, version, snapshot, created_by) values (?, ?, 1, '{\"name\":\"Draft\"}'::jsonb, 'admin')", first, product);
        });
        var second = UUID.randomUUID();
        jdbc.update("insert into repaircalc.admin_product_version(id, product_id, version, snapshot, created_by) values (?, ?, 2, '{\"name\":\"Updated\"}'::jsonb, 'admin')", second, product);
        var audit = UUID.randomUUID();
        transactions.executeWithoutResult(status -> {
            jdbc.update("update repaircalc.admin_product set current_version_id = ? where id = ?", second, product);
            jdbc.update("insert into repaircalc.admin_audit_event(id, request_id, actor, action, product_id, before_version_id, after_version_id) values (?, 'request', 'admin', 'update', ?, ?, ?)", audit, product, first, second);
        });
        assertThat(jdbc.queryForObject("select snapshot->>'name' from repaircalc.admin_product_version where id = ?", String.class, first)).isEqualTo("Draft");
        assertThatThrownBy(() -> jdbc.update("update repaircalc.admin_product set external_id = 'changed' where id = ?", product)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("delete from repaircalc.admin_product_version where id = ?", first)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update repaircalc.admin_audit_event set actor = 'other' where id = ?", audit)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("insert into repaircalc.admin_product_version(id, product_id, version, snapshot, created_by) values (?, ?, 3, '{}'::jsonb, 'admin')", UUID.randomUUID(), product)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("insert into repaircalc.admin_product(id, source_namespace, external_id, current_version_id, created_by, updated_by) values (?, 'supplier', ?, ?, 'admin', 'admin')", UUID.randomUUID(), product.toString(), UUID.randomUUID())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update repaircalc.admin_product set current_version_id = ? where id = ?", UUID.randomUUID(), product)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class PostgresConfiguration {
        @Bean @ServiceConnection PostgreSQLContainer postgres() {
            return new PostgreSQLContainer("postgres:17.11").withDatabaseName("repaircalc_admin_test")
                    .withUsername("repaircalc_test").withPassword("isolated-test-password");
        }
    }
}
