package ru.repaircalc.rules;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
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
import ru.repaircalc.ingestion.CatalogImportService;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles({"test", "postgres-test"})
@Import(RuleSetImportIT.PostgresConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RuleSetImportIT {
    private static final Path RULES = Path.of("data/rules-v1.json");

    @Autowired RuleSetImportService importer;
    @Autowired CatalogImportService catalogs;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;
    @TempDir Path directory;

    private CatalogImportService.ImportResult catalog;

    @BeforeEach
    void publishReferencedCatalog() {
        catalog = catalogs.importFiles(Path.of("data/catalog-v1.json"), Path.of("data/work-mappings-v1.json"));
    }

    @Test
    void publishesTypedFixtureWithExactCatalogProvenanceAndAllProductAssignments() throws Exception {
        var document = rules();
        assertThat(document.path("catalog").path("fingerprint").asText()).isEqualTo(catalog.fingerprint());
        Path file = file(document);

        var result = importer.importFile(file);

        assertThat(result.noOp()).isFalse();
        assertThat(result.catalogReleaseId()).isEqualTo(catalog.releaseId());
        assertThat(result.version()).isEqualTo(document.path("version").asText());
        assertThat(result.fingerprint()).isEqualTo(
                sha256(("prepared-rules-v1\n" + sha256(Files.readAllBytes(file))).getBytes(StandardCharsets.UTF_8)));
        assertThat(active()).isEqualTo(result.ruleSetId());
        assertThat(jdbc.queryForObject("select status from repaircalc.rule_set where id = ?",
                String.class, result.ruleSetId())).isEqualTo("PUBLISHED");
        assertThat(jdbc.queryForObject("select taxonomy_version from repaircalc.rule_set where id = ?",
                String.class, result.ruleSetId())).isEqualTo("work-taxonomy-v1");
        var stored = json.readTree(jdbc.queryForObject("select bundle::text from repaircalc.rule_set where id = ?",
                String.class, result.ruleSetId()));
        assertThat(stored.path("profiles").size()).isEqualTo(8);
        assertThat(stored.path("assignments").size()).isEqualTo(12);
        assertThat(stored.path("laborTariffs").size()).isEqualTo(5);
        int calculable = 0;
        int blocked = 0;
        for (var assignment : stored.path("assignments")) {
            if (assignment.path("profileId").isString()) {
                calculable++;
            } else {
                blocked++;
                assertThat(assignment.path("productId").asText()).isEqualTo("518d72ad-2dc4-3dd4-b462-2b93f1c4a177");
                assertThat(assignment.path("blockedReason").asText()).isNotBlank();
            }
        }
        assertThat(calculable).isEqualTo(11);
        assertThat(blocked).isEqualTo(1);
        for (var source : stored.path("sources")) {
            assertThat(source.path("description").asText()).contains("ТЕСТОВЫЕ");
        }
    }

    @Test
    void repeatDoesNotDuplicateOrReactivateAnOldRuleSet() throws Exception {
        Path firstFile = file(rules());
        var first = importer.importFile(firstFile);
        var repeated = importer.importFile(firstFile);
        assertThat(repeated.noOp()).isTrue();
        assertThat(repeated.ruleSetId()).isEqualTo(first.ruleSetId());
        assertThat(repeated.fingerprint()).isEqualTo(first.fingerprint());

        var next = importer.importFile(file(rules()));
        long countBefore = count();
        var old = importer.importFile(firstFile);

        assertThat(old.noOp()).isTrue();
        assertThat(old.ruleSetId()).isEqualTo(first.ruleSetId());
        assertThat(active()).isEqualTo(next.ruleSetId());
        assertThat(count()).isEqualTo(countBefore);
        assertThat(activeCount()).isEqualTo(1);
    }

    @Test
    void changingBytesUnderTheSameVersionIsRejectedEvenForEquivalentJson() throws Exception {
        Path original = file(rules());
        var before = importer.importFile(original);
        long countBefore = count();
        Path changed = directory.resolve("same-document-different-bytes.json");
        Files.writeString(changed, Files.readString(original) + "\n", StandardCharsets.UTF_8);

        assertRejected(changed, before.ruleSetId(), countBefore);
    }

    @Test
    void incompatibleCatalogVersionFingerprintOrTaxonomyCannotReplaceActiveRules() throws Exception {
        var before = importer.importFile(file(rules()));
        long countBefore = count();
        var unknownCatalog = rules();
        ((ObjectNode) unknownCatalog.path("catalog")).put("version", "missing-catalog-" + UUID.randomUUID());
        assertRejected(file(unknownCatalog), before.ruleSetId(), countBefore);
        var wrongFingerprint = rules();
        ((ObjectNode) wrongFingerprint.path("catalog")).put("fingerprint", "0".repeat(64));
        assertRejected(file(wrongFingerprint), before.ruleSetId(), countBefore);
        var wrongTaxonomy = rules();
        ((ObjectNode) wrongTaxonomy.path("catalog")).put("taxonomyVersion", "work-taxonomy-missing");
        assertRejected(file(wrongTaxonomy), before.ruleSetId(), countBefore);
    }

    @Test
    void assignmentsRequireCompleteUniqueProductsAndExactlyOneResolution() throws Exception {
        var before = importer.importFile(file(rules()));
        long countBefore = count();
        var missing = rules();
        ((ArrayNode) missing.path("assignments")).remove(11);
        assertRejected(file(missing), before.ruleSetId(), countBefore);
        var duplicate = rules();
        ((ArrayNode) duplicate.path("assignments")).add(assignment(duplicate, 0).deepCopy());
        assertRejected(file(duplicate), before.ruleSetId(), countBefore);
        var unknownProduct = rules();
        assignment(unknownProduct, 0).put("productId", UUID.randomUUID().toString());
        assertRejected(file(unknownProduct), before.ruleSetId(), countBefore);
        var unknownProfile = rules();
        assignment(unknownProfile, 0).put("profileId", "missing-profile");
        assertRejected(file(unknownProfile), before.ruleSetId(), countBefore);
        var contradictory = rules();
        assignment(contradictory, 0).put("blockedReason", "Cannot be both calculable and blocked.");
        assertRejected(file(contradictory), before.ruleSetId(), countBefore);
        var unresolved = rules();
        assignment(unresolved, 0).putNull("profileId");
        assignment(unresolved, 0).putNull("blockedReason");
        assertRejected(file(unresolved), before.ruleSetId(), countBefore);
    }

    @Test
    void profilesRejectMissingOrConflictingNormsWrongUnitsAndInvalidPolicies() throws Exception {
        var before = importer.importFile(file(rules()));
        long countBefore = count();
        var missingNorm = rules();
        profile(missingNorm, "paint-012-v1").putNull("consumption");
        assertRejected(file(missingNorm), before.ruleSetId(), countBefore);
        var invalidNorm = rules();
        profile(invalidNorm, "paint-012-v1").put("consumption", 0);
        assertRejected(file(invalidNorm), before.ruleSetId(), countBefore);
        var conflictingNorm = rules();
        profile(conflictingNorm, "paint-012-v1").put("consumption", 0.15);
        assertRejected(file(conflictingNorm), before.ruleSetId(), countBefore);
        var wrongUnit = rules();
        profile(wrongUnit, "paint-012-v1").put("unit", "кг");
        assertRejected(file(wrongUnit), before.ruleSetId(), countBefore);
        var duplicate = rules();
        ((ArrayNode) duplicate.path("profiles")).add(profile(duplicate, "covering-v1").deepCopy());
        assertRejected(file(duplicate), before.ruleSetId(), countBefore);
        var socketReserve = rules();
        profile(socketReserve, "socket-v1").put("reservePolicy", "USER_PERCENT");
        assertRejected(file(socketReserve), before.ruleSetId(), countBefore);
        var primerLayers = rules();
        profile(primerLayers, "primer-v1").put("layerPolicy", "INPUT");
        assertRejected(file(primerLayers), before.ruleSetId(), countBefore);
        var negativeTrim = rules();
        profile(negativeTrim, "wallpaper-v1").put("trimAllowance", -0.1);
        assertRejected(file(negativeTrim), before.ruleSetId(), countBefore);
    }

    @Test
    void sourcesAndLaborTariffsRequireKnownReferencesUniqueCodesAndCompatibleUnits() throws Exception {
        var before = importer.importFile(file(rules()));
        long countBefore = count();
        var missingSources = rules();
        missingSources.set("sources", json.createArrayNode());
        assertRejected(file(missingSources), before.ruleSetId(), countBefore);
        var duplicateSource = rules();
        var repeated = ((ObjectNode) duplicateSource.path("sources").get(0)).deepCopy();
        repeated.put("description", "ТЕСТОВЫЕ данные с конфликтующим описанием источника.");
        ((ArrayNode) duplicateSource.path("sources")).add(repeated);
        assertRejected(file(duplicateSource), before.ruleSetId(), countBefore);
        var unknownSource = rules();
        profile(unknownSource, "covering-v1").put("sourceId", "missing-source");
        assertRejected(file(unknownSource), before.ruleSetId(), countBefore);
        var negativeTariff = rules();
        tariff(negativeTariff, 0).put("amount", -1);
        assertRejected(file(negativeTariff), before.ruleSetId(), countBefore);
        var duplicateTariff = rules();
        ((ArrayNode) duplicateTariff.path("laborTariffs")).add(tariff(duplicateTariff, 0).deepCopy());
        assertRejected(file(duplicateTariff), before.ruleSetId(), countBefore);
        var wrongUnit = rules();
        tariff(wrongUnit, 0).put("unit", "кг");
        assertRejected(file(wrongUnit), before.ruleSetId(), countBefore);
        var missingTariffSource = rules();
        tariff(missingTariffSource, 0).put("sourceId", "missing-source");
        assertRejected(file(missingTariffSource), before.ruleSetId(), countBefore);
    }

    @Test
    void strictJsonRejectsCoercionsUnknownFieldsDuplicateKeysAndMalformedInput() throws Exception {
        var before = importer.importFile(file(rules()));
        long countBefore = count();
        String valid = json.writeValueAsString(rules());
        var wrongTextType = rules();
        wrongTextType.put("dataClass", 1);
        var enumOrdinal = rules();
        profile(enumOrdinal, "covering-v1").put("strategy", 0);
        var policyOrdinal = rules();
        profile(policyOrdinal, "covering-v1").put("reservePolicy", 0);
        var nestedTypo = rules();
        profile(nestedTypo, "covering-v1").put("consumptoin", 1);
        for (String bad : List.of(
                valid.replace("\"schemaVersion\":1", "\"schemaVersion\":1.5"),
                valid.replace("\"schemaVersion\":1", "\"schemaVersion\":\"1\""),
                valid.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"schemaVersion\":1"),
                valid.replaceFirst("\\{", "{\"unexpected\":true,"),
                json.writeValueAsString(wrongTextType),
                json.writeValueAsString(enumOrdinal),
                json.writeValueAsString(policyOrdinal),
                json.writeValueAsString(nestedTypo),
                "{\"schemaVersion\":")) {
            assertThat(bad).isNotEqualTo(valid);
            Path invalid = directory.resolve(UUID.randomUUID() + "-invalid.json");
            Files.writeString(invalid, bad, StandardCharsets.UTF_8);
            assertRejected(invalid, before.ruleSetId(), countBefore);
        }
    }

    @Test
    void persistenceFailureAfterWritesRestoresThePreviousActiveRuleSet() throws Exception {
        var before = importer.importFile(file(rules()));
        long countBefore = count();
        var failed = rules();
        failed.put("version", "fail-publish-" + UUID.randomUUID());
        Path invalid = file(failed);
        jdbc.execute("""
                create function repaircalc.reject_test_rule_publish() returns trigger language plpgsql as $$
                begin
                    if new.version like 'fail-publish-%' and new.status = 'PUBLISHED' then
                        raise exception 'Synthetic rule publication failure';
                    end if;
                    return new;
                end; $$
                """);
        jdbc.execute("""
                create trigger zz_reject_test_rule_publish before update on repaircalc.rule_set
                for each row execute function repaircalc.reject_test_rule_publish()
                """);
        try {
            assertRejected(invalid, before.ruleSetId(), countBefore);
        } finally {
            jdbc.execute("drop trigger zz_reject_test_rule_publish on repaircalc.rule_set");
            jdbc.execute("drop function repaircalc.reject_test_rule_publish()");
        }
    }

    @Test
    void competingPublishersLeaveTwoCompleteImmutableRuleSetsAndOneActive() throws Exception {
        Path firstFile = file(rules());
        Path secondFile = file(rules());
        var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> { start.await(); return importer.importFile(firstFile); });
            var second = workers.submit(() -> { start.await(); return importer.importFile(secondFile); });
            start.countDown();
            var one = first.get(30, TimeUnit.SECONDS);
            var two = second.get(30, TimeUnit.SECONDS);

            assertThat(one.ruleSetId()).isNotEqualTo(two.ruleSetId());
            assertThat(active()).isIn(one.ruleSetId(), two.ruleSetId());
            assertThat(activeCount()).isEqualTo(1);
            for (UUID id : List.of(one.ruleSetId(), two.ruleSetId())) {
                assertThat(jdbc.queryForObject("select status from repaircalc.rule_set where id = ?", String.class, id))
                        .isEqualTo("PUBLISHED");
                assertThat(jdbc.queryForObject(
                        "select jsonb_array_length(bundle->'assignments') from repaircalc.rule_set where id = ?",
                        Integer.class, id)).isEqualTo(12);
                assertThat(jdbc.queryForObject(
                        "select jsonb_array_length(bundle->'profiles') from repaircalc.rule_set where id = ?",
                        Integer.class, id)).isEqualTo(8);
            }
        }
    }

    private void assertRejected(Path file, UUID previousActive, long previousCount) {
        assertThatThrownBy(() -> importer.importFile(file)).isInstanceOf(RuleSetImportException.class);
        assertThat(active()).isEqualTo(previousActive);
        assertThat(count()).isEqualTo(previousCount);
        assertThat(activeCount()).isEqualTo(1);
    }

    private ObjectNode rules() throws Exception {
        var value = (ObjectNode) json.readTree(Files.readString(RULES, StandardCharsets.UTF_8));
        value.put("version", "rule-import-test-" + UUID.randomUUID());
        return value;
    }

    private Path file(ObjectNode value) throws Exception {
        Path path = directory.resolve(UUID.randomUUID() + "-rules.json");
        Files.writeString(path, json.writeValueAsString(value), StandardCharsets.UTF_8);
        return path;
    }

    private static ObjectNode assignment(ObjectNode document, int index) {
        return (ObjectNode) document.path("assignments").get(index);
    }

    private static ObjectNode profile(ObjectNode document, String id) {
        for (var node : document.path("profiles")) {
            if (node.path("id").asText().equals(id)) {
                return (ObjectNode) node;
            }
        }
        throw new AssertionError("Missing fixture profile " + id);
    }

    private static ObjectNode tariff(ObjectNode document, int index) {
        return (ObjectNode) document.path("laborTariffs").get(index);
    }

    private UUID active() {
        return jdbc.queryForObject("select id from repaircalc.rule_set where active and catalog_release_id = ?",
                UUID.class, catalog.releaseId());
    }

    private int activeCount() {
        return jdbc.queryForObject("select count(*) from repaircalc.rule_set where active and catalog_release_id = ?",
                Integer.class, catalog.releaseId());
    }

    private long count() {
        return jdbc.queryForObject("select count(*) from repaircalc.rule_set", Long.class);
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class PostgresConfiguration {
        @Bean @ServiceConnection
        PostgreSQLContainer postgres() {
            return new PostgreSQLContainer("postgres:17.11").withDatabaseName("rule_set_import")
                    .withUsername("repaircalc_test").withPassword("isolated-test-password");
        }
    }
}
