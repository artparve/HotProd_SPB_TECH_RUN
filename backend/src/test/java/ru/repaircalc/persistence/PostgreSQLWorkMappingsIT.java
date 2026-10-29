package ru.repaircalc.persistence;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
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
import ru.repaircalc.catalog.persistence.ProductWorkEntity;
import ru.repaircalc.catalog.persistence.ProductWorkId;
import ru.repaircalc.catalog.persistence.ProductWorkRepository;
import ru.repaircalc.catalog.persistence.WorkTypeId;
import ru.repaircalc.catalog.persistence.WorkTypeRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles({"test", "postgres-test"})
@Import(PostgreSQLWorkMappingsIT.PostgresConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PostgreSQLWorkMappingsIT {
    private static final String TAXONOMY = "work-taxonomy-v1";
    private static final String MAPPING_VERSION = "manual-fixture-mappings-v1";
    private static final String EVIDENCE = "Synthetic persistence fixture; not a verified calculation norm.";
    private static final String SOURCE = "manual_test_fixture";

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private WorkTypeRepository workTypes;

    @Autowired
    private ProductWorkRepository productWorks;

    @Test
    void seedsFourWorkCodesWithImmutableMeanings() {
        assertThat(workTypes.findByIdTaxonomyVersionOrderByIdCodeAsc(TAXONOMY))
                .extracting(work -> work.getId().getCode())
                .containsExactly("electrical", "floor", "paint", "wallpaper");
        assertThat(workTypes.findById(new WorkTypeId(TAXONOMY, "paint")))
                .hasValueSatisfying(work -> assertThat(work.getMeaning()).isNotBlank());

        assertRejected("23514", () -> jdbc.update("""
                update repaircalc.work_type set meaning = 'A different operation'
                where taxonomy_version = ? and code = 'paint'
                """, TAXONOMY));
        assertRejected("23514", () -> jdbc.update("""
                delete from repaircalc.work_type where taxonomy_version = ? and code = 'paint'
                """, TAXONOMY));
    }

    @Test
    void oneRevisionSupportsSeveralWorksWithVersionAndEvidence() {
        var revision = draftRevision();
        productWorks.saveAllAndFlush(List.of(
                mapping(revision, "paint"),
                mapping(revision, "floor")));

        var mappings = productWorks
                .findByIdReleaseIdAndIdProductIdAndIdTaxonomyVersionOrderByIdWorkCodeAsc(
                        revision.releaseId(), revision.productId(), TAXONOMY);
        assertThat(mappings).extracting(work -> work.getId().getWorkCode())
                .containsExactly("floor", "paint");
        assertThat(mappings).allSatisfy(work -> {
            assertThat(work.getMappingVersion()).isEqualTo(MAPPING_VERSION);
            assertThat(work.getEvidence()).isEqualTo(EVIDENCE);
            assertThat(work.getSource()).isEqualTo(SOURCE);
        });
        assertThat(productWorks.findByIdReleaseIdAndIdTaxonomyVersionOrderByIdProductIdAscIdWorkCodeAsc(
                revision.releaseId(), TAXONOMY)).hasSize(2);
    }

    @Test
    void mappingReadsAreScopedToReleaseEvenForTheSameStableProduct() {
        var first = draftRevision();
        var secondRelease = draftRelease();
        insertRevision(secondRelease, first.productId());
        insertMapping(first.releaseId(), first.productId(), TAXONOMY, "paint", MAPPING_VERSION, EVIDENCE, SOURCE);
        insertMapping(secondRelease, first.productId(), TAXONOMY, "floor", MAPPING_VERSION, EVIDENCE, SOURCE);

        assertThat(productWorks.findByIdReleaseIdAndIdTaxonomyVersionOrderByIdProductIdAscIdWorkCodeAsc(
                first.releaseId(), TAXONOMY)).extracting(work -> work.getId().getWorkCode())
                .containsExactly("paint");
        assertThat(productWorks.findByIdReleaseIdAndIdTaxonomyVersionOrderByIdProductIdAscIdWorkCodeAsc(
                secondRelease, TAXONOMY)).extracting(work -> work.getId().getWorkCode())
                .containsExactly("floor");
    }

    @Test
    void rejectsUnknownWorkCodeAndUnknownTaxonomy() {
        var revision = draftRevision();
        assertRejected("23503", () -> insertMapping(revision.releaseId(), revision.productId(), TAXONOMY,
                "unknown-work", MAPPING_VERSION, EVIDENCE, SOURCE));
        assertRejected("23503", () -> insertMapping(revision.releaseId(), revision.productId(), "unknown-taxonomy",
                "paint", MAPPING_VERSION, EVIDENCE, SOURCE));
    }

    @Test
    void cannotReferenceAProductRevisionFromAnotherRelease() {
        var revision = draftRevision();
        var unrelatedRelease = draftRelease();
        assertRejected("23503", () -> insertMapping(unrelatedRelease, revision.productId(), TAXONOMY,
                "paint", MAPPING_VERSION, EVIDENCE, SOURCE));
    }

    @Test
    void preventsDuplicateWorkEvenWithDifferentMappingMetadata() {
        var revision = draftRevision();
        insertMapping(revision.releaseId(), revision.productId(), TAXONOMY,
                "paint", MAPPING_VERSION, EVIDENCE, SOURCE);

        assertRejected("23505", () -> insertMapping(revision.releaseId(), revision.productId(), TAXONOMY,
                "paint", "another-mapping-version", "Different evidence", SOURCE));
    }

    @Test
    void rejectsBlankMappingProvenanceAndProvidesExplicitFixtureSourceDefault() {
        var revision = draftRevision();
        assertRejected("23514", () -> insertMapping(revision.releaseId(), revision.productId(), TAXONOMY,
                "paint", " ", EVIDENCE, SOURCE));
        assertRejected("23514", () -> insertMapping(revision.releaseId(), revision.productId(), TAXONOMY,
                "paint", MAPPING_VERSION, " ", SOURCE));
        assertRejected("23514", () -> insertMapping(revision.releaseId(), revision.productId(), TAXONOMY,
                "paint", MAPPING_VERSION, EVIDENCE, " "));

        jdbc.update("""
                insert into repaircalc.product_work
                    (release_id, product_id, taxonomy_version, work_code, mapping_version, evidence)
                values (?, ?, ?, 'paint', ?, ?)
                """, revision.releaseId(), revision.productId(), TAXONOMY, MAPPING_VERSION, EVIDENCE);
        assertThat(productWorks.findById(new ProductWorkId(
                revision.releaseId(), revision.productId(), TAXONOMY, "paint")))
                .hasValueSatisfying(work -> assertThat(work.getSource()).isEqualTo(SOURCE));
    }

    @Test
    void draftMappingsCanBeCorrectedAndRemoved() {
        var revision = draftRevision();
        insertMapping(revision.releaseId(), revision.productId(), TAXONOMY,
                "paint", MAPPING_VERSION, EVIDENCE, SOURCE);

        assertThat(jdbc.update("""
                update repaircalc.product_work set evidence = 'Corrected fixture evidence'
                where release_id = ? and product_id = ? and taxonomy_version = ? and work_code = 'paint'
                """, revision.releaseId(), revision.productId(), TAXONOMY)).isEqualTo(1);
        assertThat(productWorks.findById(new ProductWorkId(
                revision.releaseId(), revision.productId(), TAXONOMY, "paint")))
                .hasValueSatisfying(work -> assertThat(work.getEvidence()).isEqualTo("Corrected fixture evidence"));
        assertThat(jdbc.update("""
                delete from repaircalc.product_work
                where release_id = ? and product_id = ? and taxonomy_version = ? and work_code = 'paint'
                """, revision.releaseId(), revision.productId(), TAXONOMY)).isEqualTo(1);
    }

    @Test
    void publishedMappingsRejectInsertUpdateDeleteAndMovingToADraft() {
        var revision = draftRevision();
        var otherRelease = draftRelease();
        insertRevision(otherRelease, revision.productId());
        insertMapping(revision.releaseId(), revision.productId(), TAXONOMY,
                "paint", MAPPING_VERSION, EVIDENCE, SOURCE);
        jdbc.update("""
                update repaircalc.catalog_release set status = 'PUBLISHED', published_at = current_timestamp
                where id = ?
                """, revision.releaseId());

        assertRejected("23514", () -> insertMapping(revision.releaseId(), revision.productId(), TAXONOMY,
                "floor", MAPPING_VERSION, EVIDENCE, SOURCE));
        assertRejected("23514", () -> jdbc.update("""
                update repaircalc.product_work set mapping_version = 'changed'
                where release_id = ? and product_id = ?
                """, revision.releaseId(), revision.productId()));
        assertRejected("23514", () -> jdbc.update("""
                delete from repaircalc.product_work where release_id = ? and product_id = ?
                """, revision.releaseId(), revision.productId()));
        assertRejected("23514", () -> jdbc.update("""
                update repaircalc.product_work set release_id = ? where release_id = ? and product_id = ?
                """, otherRelease, revision.releaseId(), revision.productId()));

        assertThat(productWorks.findById(new ProductWorkId(
                revision.releaseId(), revision.productId(), TAXONOMY, "paint")))
                .hasValueSatisfying(work -> assertThat(work.getMappingVersion()).isEqualTo(MAPPING_VERSION));
    }

    private ProductWorkEntity mapping(Revision revision, String workCode) {
        return new ProductWorkEntity(new ProductWorkId(
                revision.releaseId(), revision.productId(), TAXONOMY, workCode), MAPPING_VERSION, EVIDENCE, SOURCE);
    }

    private Revision draftRevision() {
        UUID releaseId = draftRelease();
        UUID productId = UUID.randomUUID();
        jdbc.update("insert into repaircalc.product (id) values (?)", productId);
        insertRevision(releaseId, productId);
        return new Revision(releaseId, productId);
    }

    private UUID draftRelease() {
        UUID releaseId = UUID.randomUUID();
        String fingerprint = UUID.randomUUID().toString().replace("-", "").repeat(2);
        jdbc.update("""
                insert into repaircalc.catalog_release (id, version, fingerprint, manifest)
                values (?, ?, ?, '{"source":"manual_test_fixture"}'::jsonb)
                """, releaseId, "work-fixture-" + releaseId, fingerprint);
        return releaseId;
    }

    private void insertRevision(UUID releaseId, UUID productId) {
        jdbc.update("""
                insert into repaircalc.product_revision
                    (id, release_id, product_id, name, category, kind, price, unit, sale_unit, description, source)
                values (?, ?, ?, 'Fixture primer', 'Test category', 'primer', ?, 'л', 'банка', ?, ?)
                """, UUID.randomUUID(), releaseId, productId, new BigDecimal("100.00"), EVIDENCE, SOURCE);
    }

    private void insertMapping(UUID releaseId, UUID productId, String taxonomyVersion, String workCode,
                               String mappingVersion, String evidence, String source) {
        jdbc.update("""
                insert into repaircalc.product_work
                    (release_id, product_id, taxonomy_version, work_code, mapping_version, evidence, source)
                values (?, ?, ?, ?, ?, ?, ?)
                """, releaseId, productId, taxonomyVersion, workCode, mappingVersion, evidence, source);
    }

    private static void assertRejected(String sqlState, ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOf(DataIntegrityViolationException.class)
                .rootCause().isInstanceOf(SQLException.class)
                .satisfies(cause -> assertThat(((SQLException) cause).getSQLState()).isEqualTo(sqlState));
    }

    private record Revision(UUID releaseId, UUID productId) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class PostgresConfiguration {
        @Bean
        @ServiceConnection
        PostgreSQLContainer postgres() {
            return new PostgreSQLContainer("postgres:17.11")
                    .withDatabaseName("repaircalc_work_mapping")
                    .withUsername("repaircalc_test")
                    .withPassword("isolated-test-password");
        }
    }
}
