package ru.repaircalc.ingestion;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles({"test", "postgres-test"})
@Import(CatalogImportIT.PostgresConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CatalogImportIT {
    @Autowired CatalogImportService importer;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;
    @TempDir Path directory;

    @Test
    void publishesPreparedCatalogWithRawDataMappingsAndManifestThenRepeatsWithoutDuplicates() throws Exception {
        var catalog = catalog();
        var raw = (ObjectNode) product(catalog, 0).get("sources").get(0).get("raw");
        raw.set("characteristics", json.readTree("[{\"name\":\"size\",\"value\":1},{\"name\":\"size\",\"value\":2}]"));
        var files = files(catalog, mappings());
        var first = importer.importFiles(files.catalog(), files.mappings());
        var second = importer.importFiles(files.catalog(), files.mappings());

        assertThat(first.noOp()).isFalse();
        assertThat(second.noOp()).isTrue();
        assertThat(second.releaseId()).isEqualTo(first.releaseId()).isEqualTo(active());
        assertThat(first.productCount()).isEqualTo(12);
        assertThat(first.fingerprint()).matches("[0-9a-f]{64}");
        assertThat(count("product_revision", first.releaseId())).isEqualTo(12);
        assertThat(count("source_record", first.releaseId())).isEqualTo(12);
        assertThat(count("product_work", first.releaseId())).isGreaterThan(12);
        assertThat(jdbc.queryForObject("select manifest->>'importerVersion' from repaircalc.catalog_release where id = ?",
                String.class, first.releaseId())).isEqualTo(CatalogImportService.IMPORTER_VERSION);
        var stored = jdbc.queryForObject("select raw::text from repaircalc.source_record where release_id = ? and product_id = ?",
                String.class, first.releaseId(), UUID.fromString(product(catalog, 0).get("id").asText()));
        assertThat(json.readTree(stored)).isEqualTo(raw);
    }

    @Test
    void newReleasePreservesOldRevisionAndReplayingOldFilesDoesNotReactivateIt() throws Exception {
        var oldCatalog = catalog();
        var oldFiles = files(oldCatalog, mappings());
        var old = importer.importFiles(oldFiles.catalog(), oldFiles.mappings());
        var nextCatalog = catalog();
        product(nextCatalog, 0).put("price", "999.99");
        var nextFiles = files(nextCatalog, mappings());
        var next = importer.importFiles(nextFiles.catalog(), nextFiles.mappings());
        var repeated = importer.importFiles(oldFiles.catalog(), oldFiles.mappings());

        assertThat(repeated.noOp()).isTrue();
        assertThat(active()).isEqualTo(next.releaseId());
        var productId = UUID.fromString(product(oldCatalog, 0).get("id").asText());
        assertThat(jdbc.queryForObject("select price::text from repaircalc.product_revision where release_id = ? and product_id = ?",
                String.class, old.releaseId(), productId)).isEqualTo(product(oldCatalog, 0).get("price").asText());
        assertThat(jdbc.queryForObject("select price::text from repaircalc.product_revision where release_id = ? and product_id = ?",
                String.class, next.releaseId(), productId)).isEqualTo("999.99");
    }

    @Test
    void changedContentUnderExistingVersionIsRejected() throws Exception {
        var catalog = catalog();
        var firstFiles = files(catalog, mappings());
        var first = importer.importFiles(firstFiles.catalog(), firstFiles.mappings());
        product(catalog, 0).put("name", "Changed synthetic name");
        var changed = files(catalog, mappings());
        assertThatThrownBy(() -> importer.importFiles(changed.catalog(), changed.mappings()))
                .isInstanceOf(CatalogImportException.class).hasMessageContaining("version already exists");
        assertThat(active()).isEqualTo(first.releaseId());
        assertThat(count("product_revision", first.releaseId())).isEqualTo(12);
    }

    @Test
    void invalidDocumentsUnknownReferencesAndWorkCodesLeaveActiveSnapshotUntouched() throws Exception {
        var valid = files(catalog(), mappings());
        var before = importer.importFiles(valid.catalog(), valid.mappings());
        long releases = jdbc.queryForObject("select count(*) from repaircalc.catalog_release", Long.class);
        var negativePrice = catalog();
        product(negativePrice, 0).put("price", "-1.00");
        var badFields = files(negativePrice, mappings());
        assertRejected(badFields);
        var unknownMappings = mappings();
        unknownMappings.put("version", "unknown-product-" + UUID.randomUUID());
        ((ObjectNode) unknownMappings.get("mappings").get(0)).put("productId", UUID.randomUUID().toString());
        assertRejected(files(catalog(), unknownMappings));
        var unknownWork = mappings();
        unknownWork.put("version", "unknown-work-" + UUID.randomUUID());
        ((ObjectNode) unknownWork.get("mappings").get(0)).set("works", json.readTree("[\"invented_work\"]"));
        assertRejected(files(catalog(), unknownWork));
        var duplicateWorks = mappings();
        ((ObjectNode) duplicateWorks.get("mappings").get(0)).set("works", json.readTree("[\"floor\",\"floor\"]"));
        assertRejected(files(catalog(), duplicateWorks));
        var unknownTaxonomy = mappings();
        unknownTaxonomy.put("taxonomyVersion", "not-a-taxonomy");
        assertRejected(files(catalog(), unknownTaxonomy));
        var badJson = directory.resolve("broken.json");
        Files.writeString(badJson, "{\"secret\":");
        assertThatThrownBy(() -> importer.importFiles(badJson, valid.mappings())).isInstanceOf(CatalogImportException.class);
        assertThat(active()).isEqualTo(before.releaseId());
        assertThat(jdbc.queryForObject("select count(*) from repaircalc.catalog_release", Long.class)).isEqualTo(releases);
    }

    @Test
    void existingSourceCannotBeSilentlyReassignedToDifferentUuid() throws Exception {
        var initial = files(catalog(), mappings());
        var before = importer.importFiles(initial.catalog(), initial.mappings());
        var reassigned = catalog();
        String changedId = UUID.randomUUID().toString();
        product(reassigned, 0).put("id", changedId);
        var changedMappings = mappings();
        changedMappings.put("version", "reassigned-source-" + UUID.randomUUID());
        ((ObjectNode) changedMappings.get("mappings").get(0)).put("productId", changedId);
        var invalid = files(reassigned, changedMappings);
        assertThatThrownBy(() -> importer.importFiles(invalid.catalog(), invalid.mappings()))
                .isInstanceOf(CatalogImportException.class).hasMessageContaining("source identity");
        assertThat(active()).isEqualTo(before.releaseId());
    }

    @Test
    void failureAfterWritesRollsBackReleaseAndPublication() throws Exception {
        var initial = files(catalog(), mappings());
        var before = importer.importFiles(initial.catalog(), initial.mappings());
        long releases = jdbc.queryForObject("select count(*) from repaircalc.catalog_release", Long.class);
        var broken = catalog();
        product(broken, 5).put("name", "FAIL_IMPORT");
        var invalid = files(broken, mappings());
        jdbc.execute("""
                create function repaircalc.reject_test_import() returns trigger language plpgsql as $$
                begin
                    if new.name = 'FAIL_IMPORT' then raise exception 'Test persistence failure'; end if;
                    return new;
                end; $$
                """);
        jdbc.execute("create trigger zz_reject_test_import before insert on repaircalc.product_revision for each row execute function repaircalc.reject_test_import()");
        try {
            assertThatThrownBy(() -> importer.importFiles(invalid.catalog(), invalid.mappings()))
                    .isInstanceOf(CatalogImportException.class).hasMessageContaining("previous active release was preserved");
        } finally {
            jdbc.execute("drop trigger zz_reject_test_import on repaircalc.product_revision");
            jdbc.execute("drop function repaircalc.reject_test_import()");
        }
        assertThat(active()).isEqualTo(before.releaseId());
        assertThat(jdbc.queryForObject("select count(*) from repaircalc.catalog_release", Long.class)).isEqualTo(releases);
    }

    @Test
    void competingPublishersProduceCompleteReleasesWithExactlyOneActive() throws Exception {
        var firstFiles = files(catalog(), mappings());
        var secondCatalog = catalog();
        product(secondCatalog, 0).put("price", "321.00");
        var secondFiles = files(secondCatalog, mappings());
        var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> { start.await(); return importer.importFiles(firstFiles.catalog(), firstFiles.mappings()); });
            var second = workers.submit(() -> { start.await(); return importer.importFiles(secondFiles.catalog(), secondFiles.mappings()); });
            start.countDown();
            var one = first.get(30, TimeUnit.SECONDS);
            var two = second.get(30, TimeUnit.SECONDS);
            assertThat(active()).isIn(one.releaseId(), two.releaseId());
            assertThat(jdbc.queryForObject("select count(*) from repaircalc.catalog_release where active", Integer.class)).isEqualTo(1);
            assertThat(count("product_revision", one.releaseId())).isEqualTo(12);
            assertThat(count("product_revision", two.releaseId())).isEqualTo(12);
        }
    }

    @Test
    void anExplicitEmptyCatalogCanBePublished() throws Exception {
        var empty = catalog();
        empty.set("products", json.createArrayNode());
        var noMappings = mappings();
        noMappings.put("version", "empty-mappings-" + UUID.randomUUID());
        noMappings.set("mappings", json.createArrayNode());
        var files = files(empty, noMappings);
        var result = importer.importFiles(files.catalog(), files.mappings());
        assertThat(result.productCount()).isZero();
        assertThat(active()).isEqualTo(result.releaseId());
        assertThat(count("product_revision", result.releaseId())).isZero();
    }

    @Test
    void changedMappingArtifactRequiresANewMappingVersion() throws Exception {
        var initial = files(catalog(), mappings());
        var before = importer.importFiles(initial.catalog(), initial.mappings());
        var modifiedMappings = mappings();
        ((ObjectNode) modifiedMappings.get("mappings").get(0)).put("evidence", "Changed manual evidence");
        var invalid = files(catalog(), modifiedMappings);
        assertThatThrownBy(() -> importer.importFiles(invalid.catalog(), invalid.mappings()))
                .isInstanceOf(CatalogImportException.class).hasMessageContaining("Mapping version already exists");
        assertThat(active()).isEqualTo(before.releaseId());
        modifiedMappings.put("version", "mapping-update-" + UUID.randomUUID());
        var valid = files(catalog(), modifiedMappings);
        assertThat(importer.importFiles(valid.catalog(), valid.mappings()).noOp()).isFalse();
    }

    @Test
    void permissiveJsonCoercionTyposAndDuplicateKeysCannotPublish() throws Exception {
        var baseline = files(catalog(), mappings());
        var before = importer.importFiles(baseline.catalog(), baseline.mappings());
        String valid = Files.readString(baseline.catalog());
        for (String bad : new String[]{
                valid.replace("\"schemaVersion\":1", "\"schemaVersion\":1.9"),
                valid.replace("\"schemaVersion\":1", "\"schemaVersion\":\"1\""),
                valid.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"schemaVersion\":1"),
                valid.replaceFirst("\\{", "{\"unrecognizedField\":true,"),
                valid.replaceFirst("\"wet\":false", "\"wet\":\"false\"")}) {
            assertThat(bad).isNotEqualTo(valid);
            var file = directory.resolve(UUID.randomUUID() + "-invalid.json");
            Files.writeString(file, bad);
            assertThatThrownBy(() -> importer.importFiles(file, baseline.mappings())).isInstanceOf(CatalogImportException.class);
            assertThat(active()).isEqualTo(before.releaseId());
        }
    }

    private void assertRejected(InputFiles files) {
        assertThatThrownBy(() -> importer.importFiles(files.catalog(), files.mappings())).isInstanceOf(CatalogImportException.class);
    }

    private ObjectNode catalog() throws Exception {
        var value = (ObjectNode) json.readTree(Files.readString(Path.of("data/catalog-v1.json")));
        value.put("version", "test-" + UUID.randomUUID());
        return value;
    }

    private ObjectNode mappings() throws Exception {
        return (ObjectNode) json.readTree(Files.readString(Path.of("data/work-mappings-v1.json")));
    }

    private ObjectNode product(ObjectNode catalog, int index) {
        return (ObjectNode) ((ArrayNode) catalog.get("products")).get(index);
    }

    private InputFiles files(ObjectNode catalog, ObjectNode mappings) throws Exception {
        var catalogPath = directory.resolve(UUID.randomUUID() + "-catalog.json");
        var mappingsPath = directory.resolve(UUID.randomUUID() + "-mappings.json");
        Files.writeString(catalogPath, json.writeValueAsString(catalog));
        Files.writeString(mappingsPath, json.writeValueAsString(mappings));
        return new InputFiles(catalogPath, mappingsPath);
    }

    private int count(String table, UUID releaseId) {
        // table names are test constants, never operator input.
        return jdbc.queryForObject("select count(*) from repaircalc." + table + " where release_id = ?", Integer.class, releaseId);
    }

    private UUID active() {
        return jdbc.queryForObject("select id from repaircalc.catalog_release where active", UUID.class);
    }

    private record InputFiles(Path catalog, Path mappings) { }

    @TestConfiguration(proxyBeanMethods = false)
    static class PostgresConfiguration {
        @Bean @ServiceConnection
        PostgreSQLContainer postgres() {
            return new PostgreSQLContainer("postgres:17.11").withDatabaseName("catalog_import")
                    .withUsername("repaircalc_test").withPassword("isolated-test-password");
        }
    }
}
