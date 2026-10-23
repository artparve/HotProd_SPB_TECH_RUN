package ru.repaircalc.catalog.persistence;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;

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
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles({"test", "postgres-test"})
@Import(CatalogPersistenceIT.PostgresConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CatalogPersistenceIT {
    @Autowired
    private DataSource dataSource;
    @Autowired
    private CatalogReleaseRepository releases;
    @Autowired
    private ProductRepository products;
    @Autowired
    private ProductRevisionRepository revisions;
    @Autowired
    private SourceRecordRepository sources;

    private JdbcTemplate jdbc;

    @BeforeEach
    void isolateActiveRelease() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("update repaircalc.catalog_release set active = false where active");
    }

    @Test
    void jpaReadsStableProductIdentityAndUnknownValuesAcrossReleases() {
        var product = product();
        var first = release();
        revision(first, product, "Repeated supplier code");
        source(first, product, "supplier-json", "7",
                "{\"characteristics\":[{\"name\":\"size\",\"value\":null},{\"name\":\"size\",\"value\":\"1\"},{\"name\":\"size\",\"value\":\"2\"}]}");
        publish(first, true);

        var second = release();
        revision(second, product, "Repeated supplier code");
        jdbc.update("update repaircalc.product_revision set name = 'Updated name', price = 45.67 where release_id = ?", second);
        source(second, product, "supplier-json", "7", "{\"newRevision\":true}");

        assertThat(products.findById(product)).isPresent();
        assertThat(releases.findByActiveTrue()).get().extracting(CatalogReleaseEntity::getId).isEqualTo(first);
        var firstRelease = releases.findById(first).orElseThrow();
        assertThat(firstRelease.getStatus()).isEqualTo(CatalogReleaseStatus.PUBLISHED);
        assertThat(firstRelease.getManifest()).contains("prepared-v1");
        assertThat(firstRelease.getPublishedAt()).isNotNull();
        assertThat(releases.findByVersion(firstRelease.getVersion())).isPresent();
        assertThat(releases.findByFingerprint(firstRelease.getFingerprint())).isPresent();

        var before = revisions.findByReleaseIdAndProductId(first, product).orElseThrow();
        var after = revisions.findByReleaseIdAndProductId(second, product).orElseThrow();
        assertThat(before.getProductId()).isEqualTo(after.getProductId()).isEqualTo(product);
        assertThat(before.getPrice()).isEqualByComparingTo("12.34");
        assertThat(after.getPrice()).isEqualByComparingTo("45.67");
        assertThat(before.getPackageSize()).isNull();
        assertThat(before.getWet()).isNull();
        assertThat(before.getHighLoad()).isNull();
        assertThat(before.getUnheated()).isNull();
        assertThat(before.getConsumption()).isNull();
        assertThat(before.getRapport()).isNull();
        assertThat(revisions.findByReleaseIdOrderByProductIdAsc(first)).hasSize(1);

        var raw = sources.findByReleaseIdAndSourceNamespaceAndExternalId(first, "supplier-json", "7").orElseThrow();
        assertThat(raw.getRaw()).contains("characteristics", "null");
        assertThat(sources.findByReleaseIdOrderBySourceNamespaceAscExternalIdAsc(first)).hasSize(1);
        assertThat(jdbc.queryForObject(
                "select jsonb_array_length(raw->'characteristics') from repaircalc.source_record where id = ?",
                Integer.class, raw.getId())).isEqualTo(3);
        assertThat(jdbc.queryForObject(
                "select raw->'characteristics'->0->'value' = 'null'::jsonb from repaircalc.source_record where id = ?",
                Boolean.class, raw.getId())).isTrue();
    }

    @Test
    void identitiesAreUniqueWithinReleaseAndSourceNamespaceWhileCodGoodCanRepeat() {
        var release = release();
        var firstProduct = product();
        var secondProduct = product();
        revision(release, firstProduct, "same-code");
        revision(release, secondProduct, "same-code");
        source(release, firstProduct, "supplier-json", "7", "{}");
        source(release, secondProduct, "supplier-xml", "7", "{}");

        assertThat(revisions.findByReleaseIdOrderByProductIdAsc(release)).hasSize(2);
        assertThat(sources.findByReleaseIdOrderBySourceNamespaceAscExternalIdAsc(release)).hasSize(2);
        assertThatThrownBy(() -> revision(release, firstProduct, "different-code"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> source(release, secondProduct, "supplier-json", "7", "{}"))
                .isInstanceOf(DataIntegrityViolationException.class);

        var existing = releases.findById(release).orElseThrow();
        assertThatThrownBy(() -> jdbc.update(
                "insert into repaircalc.catalog_release(id, version, fingerprint, manifest) values (?, ?, ?, '{}'::jsonb)",
                UUID.randomUUID(), existing.getVersion(), fingerprint()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                "insert into repaircalc.catalog_release(id, version, fingerprint, manifest) values (?, ?, ?, '{}'::jsonb)",
                UUID.randomUUID(), UUID.randomUUID().toString(), existing.getFingerprint()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsInvalidKnownValuesAndBrokenForeignKeys() {
        var release = release();
        var product = product();
        var revision = revision(release, product, null);
        for (var assignment : new String[]{"price = 0", "price = -1", "price = 'NaN'::numeric",
                "package_size = 0", "package_size = 'NaN'::numeric", "package_size = 'Infinity'::numeric",
                "consumption = -1", "consumption = 'NaN'::numeric", "consumption = 'Infinity'::numeric",
                "roll_width = 0", "roll_width = 'NaN'::numeric", "roll_width = 'Infinity'::numeric",
                "roll_length = -1", "roll_length = 'NaN'::numeric", "roll_length = 'Infinity'::numeric",
                "rapport = -1", "rapport = 'NaN'::numeric", "rapport = 'Infinity'::numeric", "kind = 'unsupported'"}) {
            assertThatThrownBy(() -> jdbc.update("update repaircalc.product_revision set " + assignment + " where id = ?", revision))
                    .as(assignment).isInstanceOf(DataIntegrityViolationException.class);
        }
        jdbc.update("update repaircalc.product_revision set package_size = 1.23456789, rapport = 0 where id = ?", revision);
        assertThat(revisions.findById(revision).orElseThrow().getPackageSize())
                .isEqualByComparingTo(new BigDecimal("1.23456789"));
        assertThatThrownBy(() -> revision(UUID.randomUUID(), product, null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> revision(release, UUID.randomUUID(), null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> source(release, UUID.randomUUID(), "supplier", "missing", "{}"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("delete from repaircalc.product where id = ?", product))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void onlyOnePublishedReleaseCanBeActiveAndSwitchPreservesOldContent() {
        var first = release();
        var second = release();
        assertThatThrownBy(() -> jdbc.update("update repaircalc.catalog_release set active = true where id = ?", first))
                .isInstanceOf(DataIntegrityViolationException.class);
        publish(first, true);
        assertThatThrownBy(() -> publish(second, true)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(releases.findById(second).orElseThrow().getStatus()).isEqualTo(CatalogReleaseStatus.DRAFT);

        jdbc.update("update repaircalc.catalog_release set active = false where id = ?", first);
        publish(second, true);
        assertThat(releases.findByActiveTrue()).get().extracting(CatalogReleaseEntity::getId).isEqualTo(second);
        assertThat(releases.findById(first).orElseThrow().getStatus()).isEqualTo(CatalogReleaseStatus.PUBLISHED);
    }

    @Test
    void publishedMetadataAndContentAreImmutableExceptActiveFlag() {
        var release = release();
        var product = product();
        var revision = revision(release, product, null);
        var source = source(release, product, "supplier", "7", "{}");
        publish(release, true);

        for (var assignment : new String[]{"version = 'changed'", "fingerprint = '" + fingerprint() + "'",
                "manifest = '{\"changed\":true}'::jsonb", "status = 'DRAFT'",
                "created_at = created_at + interval '1 second'", "published_at = published_at + interval '1 second'"}) {
            assertThatThrownBy(() -> jdbc.update("update repaircalc.catalog_release set " + assignment + " where id = ?", release))
                    .as(assignment).isInstanceOf(DataIntegrityViolationException.class);
        }
        assertThatThrownBy(() -> jdbc.update("delete from repaircalc.catalog_release where id = ?", release))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update repaircalc.product_revision set name = 'changed' where id = ?", revision))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("delete from repaircalc.product_revision where id = ?", revision))
                .isInstanceOf(DataIntegrityViolationException.class);
        var anotherProduct = product();
        assertThatThrownBy(() -> revision(release, anotherProduct, null)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update repaircalc.source_record set raw = '{\"changed\":true}'::jsonb where id = ?", source))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("delete from repaircalc.source_record where id = ?", source))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> source(release, product, "other", "8", "{}"))
                .isInstanceOf(DataIntegrityViolationException.class);

        jdbc.update("update repaircalc.catalog_release set active = false where id = ?", release);
        assertThat(releases.findById(release).orElseThrow().isActive()).isFalse();
        assertThat(revisions.findById(revision).orElseThrow().getName()).isEqualTo("Test product");
    }

    @Test
    void parentLockPreventsPublicationRacingContentWrite() throws Exception {
        var release = release();
        var product = product();
        var revision = revision(release, product, null);
        try (var writer = dataSource.getConnection(); var publisher = dataSource.getConnection()) {
            writer.setAutoCommit(false);
            try (var update = writer.prepareStatement("update repaircalc.product_revision set name = 'Committed before publication' where id = ?")) {
                update.setObject(1, revision);
                update.executeUpdate();
            }
            try (var timeout = publisher.createStatement()) {
                timeout.execute("set lock_timeout = '250ms'");
            }
            try (var publish = publisher.prepareStatement(
                    "update repaircalc.catalog_release set status = 'PUBLISHED', published_at = current_timestamp where id = ?")) {
                publish.setObject(1, release);
                assertThatThrownBy(publish::executeUpdate).isInstanceOf(SQLException.class)
                        .satisfies(exception -> assertThat(((SQLException) exception).getSQLState()).isEqualTo("55P03"));
                writer.commit();
                assertThat(publish.executeUpdate()).isEqualTo(1);
            }
        }
        assertThat(revisions.findById(revision).orElseThrow().getName()).isEqualTo("Committed before publication");
        assertThat(releases.findById(release).orElseThrow().getStatus()).isEqualTo(CatalogReleaseStatus.PUBLISHED);
    }

    private UUID release() {
        var id = UUID.randomUUID();
        jdbc.update("insert into repaircalc.catalog_release(id, version, fingerprint, manifest) values (?, ?, ?, '{\"format\":\"prepared-v1\"}'::jsonb)",
                id, "catalog-" + id, fingerprint());
        return id;
    }

    private UUID product() {
        var id = UUID.randomUUID();
        jdbc.update("insert into repaircalc.product(id) values (?)", id);
        return id;
    }

    private UUID revision(UUID release, UUID product, String codGood) {
        var id = UUID.randomUUID();
        jdbc.update("""
                insert into repaircalc.product_revision
                    (id, release_id, product_id, cod_good, name, category, kind, price, unit, sale_unit, description, source)
                values (?, ?, ?, ?, 'Test product', 'Test category', 'covering', 12.34, 'м²', 'уп.', '', 'Test fixture')
                """, id, release, product, codGood);
        return id;
    }

    private UUID source(UUID release, UUID product, String namespace, String externalId, String raw) {
        var id = UUID.randomUUID();
        jdbc.update("insert into repaircalc.source_record(id, release_id, product_id, source_namespace, external_id, raw) values (?, ?, ?, ?, ?, ?::jsonb)",
                id, release, product, namespace, externalId, raw);
        return id;
    }

    private void publish(UUID release, boolean active) {
        jdbc.update("update repaircalc.catalog_release set status = 'PUBLISHED', active = ?, published_at = current_timestamp where id = ?",
                active, release);
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
                    .withDatabaseName("repaircalc_catalog_persistence")
                    .withUsername("repaircalc_test")
                    .withPassword("isolated-test-password");
        }
    }
}
