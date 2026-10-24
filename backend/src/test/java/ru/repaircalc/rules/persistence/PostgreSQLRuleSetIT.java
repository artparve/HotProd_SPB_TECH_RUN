package ru.repaircalc.rules.persistence;

import java.util.UUID;
import javax.sql.DataSource;

import jakarta.persistence.EntityManagerFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles({"test", "postgres-test"})
@Import(PostgreSQLRuleSetIT.PostgresConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PostgreSQLRuleSetIT {
    @Autowired
    private DataSource dataSource;
    @Autowired
    private RuleSetRepository rules;
    @Autowired
    private EntityManagerFactory entityManagerFactory;
    @Autowired
    private Flyway flyway;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private JdbcTemplate jdbc;

    @BeforeEach
    void prepareJdbc() {
        jdbc = new JdbcTemplate(dataSource);
    }

    @Test
    void migratesFromScratchAndJpaReadsPublishedBundleAndExactCatalogBinding() {
        assertThat(jdbc.queryForObject(
                "select count(*) from public.flyway_schema_history where version = '4' and success", Integer.class))
                .isEqualTo(1);
        assertThat(flyway.getConfiguration().getDefaultSchema()).isEqualTo("public");
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(entityManagerFactory.getProperties())
                .containsEntry("hibernate.hbm2ddl.auto", "validate")
                .containsEntry("hibernate.default_schema", "repaircalc");

        var catalog = catalog(true);
        var id = ruleSet(catalog);
        publish(id, true);
        var ruleSet = rules.findByCatalogReleaseIdAndActiveTrue(catalog).orElseThrow();
        assertThat(ruleSet.getId()).isEqualTo(id);
        assertThat(ruleSet.getCatalogReleaseId()).isEqualTo(catalog);
        assertThat(ruleSet.getTaxonomyVersion()).isEqualTo("work-taxonomy-v1");
        assertThat(ruleSet.getStatus()).isEqualTo(RuleSetStatus.PUBLISHED);
        assertThat(ruleSet.getBundle()).contains("covering-area");
        assertThat(ruleSet.getManifest()).contains("test");
        assertThat(ruleSet.getCreatedAt()).isNotNull();
        assertThat(ruleSet.getPublishedAt()).isNotNull();
        assertThat(rules.findByVersion(ruleSet.getVersion())).isPresent();
        assertThat(rules.findByFingerprint(ruleSet.getFingerprint())).isPresent();
    }

    @Test
    void requiresExistingPublishedCatalogOnInsertAndDraftRebinding() {
        var draftCatalog = catalog(false);
        var publishedCatalog = catalog(true);
        assertThatThrownBy(() -> ruleSet(UUID.randomUUID())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> ruleSet(draftCatalog)).isInstanceOf(DataIntegrityViolationException.class);
        var id = ruleSet(publishedCatalog);
        assertThatThrownBy(() -> jdbc.update("update repaircalc.rule_set set catalog_release_id = ? where id = ?", draftCatalog, id))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update repaircalc.rule_set set catalog_release_id = ? where id = ?", UUID.randomUUID(), id))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(rules.findById(id).orElseThrow().getCatalogReleaseId()).isEqualTo(publishedCatalog);
    }

    @Test
    void versionsFingerprintsAndPublicationStateHaveDatabaseConstraints() {
        var catalog = catalog(true);
        var id = ruleSet(catalog);
        var existing = rules.findById(id).orElseThrow();
        assertThatThrownBy(() -> ruleSet(catalog, existing.getVersion(), fingerprint()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> ruleSet(catalog, "other-" + UUID.randomUUID(), existing.getFingerprint()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> ruleSet(catalog, " ", fingerprint()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> ruleSet(catalog, "invalid-" + UUID.randomUUID(), "A".repeat(64)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> ruleSet(catalog, "invalid-" + UUID.randomUUID(), "too-short"))
                .isInstanceOf(DataIntegrityViolationException.class);

        for (var assignment : new String[]{"active = true", "published_at = current_timestamp",
                "status = 'PUBLISHED'", "status = 'UNKNOWN'", "taxonomy_version = ' '",
                "bundle = '[]'::jsonb", "manifest = 'null'::jsonb"}) {
            assertThatThrownBy(() -> jdbc.update("update repaircalc.rule_set set " + assignment + " where id = ?", id))
                    .as(assignment).isInstanceOf(DataIntegrityViolationException.class);
        }
        assertThatThrownBy(() -> jdbc.update("""
                insert into repaircalc.rule_set
                    (id, version, fingerprint, catalog_release_id, taxonomy_version, bundle, manifest, status, published_at)
                values (?, ?, ?, ?, 'work-taxonomy-v1', '{}'::jsonb, '{}'::jsonb, 'PUBLISHED', current_timestamp)
                """, UUID.randomUUID(), "published-insert-" + UUID.randomUUID(), fingerprint(), catalog))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void allowsOneActiveRuleSetForEachCatalogAndCanReplaceItAtomically() {
        var firstCatalog = catalog(true);
        var secondCatalog = catalog(true);
        var oldRules = ruleSet(firstCatalog);
        var newRules = ruleSet(firstCatalog);
        var otherCatalogRules = ruleSet(secondCatalog);
        publish(oldRules, true);
        publish(otherCatalogRules, true);
        assertThatThrownBy(() -> publish(newRules, true)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(rules.findById(newRules).orElseThrow().getStatus()).isEqualTo(RuleSetStatus.DRAFT);

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    jdbc.update("update repaircalc.rule_set set active = false where id = ?", oldRules);
                    publish(newRules, true);
                });

        assertThat(rules.findByCatalogReleaseIdAndActiveTrue(firstCatalog)).get()
                .extracting(RuleSetEntity::getId).isEqualTo(newRules);
        assertThat(rules.findByCatalogReleaseIdAndActiveTrue(secondCatalog)).get()
                .extracting(RuleSetEntity::getId).isEqualTo(otherCatalogRules);
        assertThat(rules.findById(oldRules).orElseThrow().getStatus()).isEqualTo(RuleSetStatus.PUBLISHED);
    }

    @Test
    void failedReplacementRollsBackPreviousActiveRuleSet() {
        var catalog = catalog(true);
        var original = ruleSet(catalog);
        var replacement = ruleSet(catalog);
        publish(original, true);
        var transactions = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            jdbc.update("update repaircalc.rule_set set active = false where id = ?", original);
            jdbc.update("update repaircalc.rule_set set status = 'PUBLISHED', active = true where id = ?", replacement);
        })).isInstanceOf(DataIntegrityViolationException.class);

        assertThat(rules.findByCatalogReleaseIdAndActiveTrue(catalog)).get()
                .extracting(RuleSetEntity::getId).isEqualTo(original);
        assertThat(rules.findById(replacement).orElseThrow().getStatus()).isEqualTo(RuleSetStatus.DRAFT);
    }

    @Test
    void publishedContentIsImmutableAndRuleSetVersionsCannotBeDeleted() {
        var catalog = catalog(true);
        var otherCatalog = catalog(true);
        var draft = ruleSet(catalog);
        var id = ruleSet(catalog);
        publish(id, true);
        for (var assignment : new String[]{"id = '" + UUID.randomUUID() + "'::uuid", "version = 'changed'",
                "fingerprint = '" + fingerprint() + "'", "catalog_release_id = '" + otherCatalog + "'::uuid",
                "taxonomy_version = 'changed'", "bundle = '{\"changed\":true}'::jsonb",
                "manifest = '{\"changed\":true}'::jsonb", "status = 'DRAFT'",
                "created_at = created_at + interval '1 second'", "published_at = published_at + interval '1 second'"}) {
            assertThatThrownBy(() -> jdbc.update("update repaircalc.rule_set set " + assignment + " where id = ?", id))
                    .as(assignment).isInstanceOf(DataIntegrityViolationException.class);
        }
        assertThatThrownBy(() -> jdbc.update("delete from repaircalc.rule_set where id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("delete from repaircalc.rule_set where id = ?", draft))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("update repaircalc.rule_set set active = false where id = ?", id);
        assertThat(rules.findById(id).orElseThrow().isActive()).isFalse();
        assertThat(rules.findById(id).orElseThrow().getBundle()).contains("covering-area");
    }

    private UUID catalog(boolean published) {
        var id = UUID.randomUUID();
        jdbc.update("insert into repaircalc.catalog_release(id, version, fingerprint, manifest) values (?, ?, ?, '{}'::jsonb)",
                id, "rules-catalog-" + id, fingerprint());
        if (published) {
            jdbc.update("update repaircalc.catalog_release set status = 'PUBLISHED', published_at = current_timestamp where id = ?", id);
        }
        return id;
    }

    private UUID ruleSet(UUID catalog) {
        return ruleSet(catalog, "rules-" + UUID.randomUUID(), fingerprint());
    }

    private UUID ruleSet(UUID catalog, String version, String fingerprint) {
        var id = UUID.randomUUID();
        jdbc.update("""
                insert into repaircalc.rule_set
                    (id, version, fingerprint, catalog_release_id, taxonomy_version, bundle, manifest)
                values (?, ?, ?, ?, 'work-taxonomy-v1', '{"profiles":[{"id":"covering-area"}]}'::jsonb, '{"dataClass":"test"}'::jsonb)
                """, id, version, fingerprint, catalog);
        return id;
    }

    private void publish(UUID id, boolean active) {
        jdbc.update("update repaircalc.rule_set set status = 'PUBLISHED', active = ?, published_at = current_timestamp where id = ?", active, id);
    }

    private static String fingerprint() {
        return UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class PostgresConfiguration {
        @Bean
        @ServiceConnection
        PostgreSQLContainer postgres() {
            return new PostgreSQLContainer("postgres:17.11")
                    .withDatabaseName("repaircalc_rule_set")
                    .withUsername("repaircalc_test")
                    .withPassword("isolated-test-password");
        }
    }
}
