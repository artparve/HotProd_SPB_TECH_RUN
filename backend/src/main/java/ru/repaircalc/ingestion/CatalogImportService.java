package ru.repaircalc.ingestion;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import jakarta.validation.Validator;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.type.LogicalType;

@Service
public class CatalogImportService {
    public static final String IMPORTER_VERSION = "prepared-catalog-v1";
    // All prepared publishers share this transaction-scoped PostgreSQL advisory lock.
    private static final long PUBLISH_LOCK = 73492026001L;
    private static final Set<String> KINDS = Set.of("covering", "paint", "primer", "wallpaper", "adhesive", "socket", "cable");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final JsonMapper json;
    private final Validator validator;

    public CatalogImportService(JdbcTemplate jdbc, PlatformTransactionManager transactionManager, JsonMapper json, Validator validator) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.json = json.rebuild()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .withCoercionConfig(LogicalType.Integer, config -> config.setCoercion(CoercionInputShape.String, CoercionAction.Fail))
                .withCoercionConfig(LogicalType.Boolean, config -> config
                        .setCoercion(CoercionInputShape.String, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail))
                .withCoercionConfig(LogicalType.Textual, config -> config
                        .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail))
                .build();
        this.validator = validator;
    }

    public ImportResult importFiles(Path catalogFile, Path mappingsFile) {
        final byte[] catalogBytes;
        final byte[] mappingBytes;
        final PreparedCatalog.Catalog catalog;
        final PreparedCatalog.Mappings mappings;
        try {
            catalogBytes = Files.readAllBytes(catalogFile);
            mappingBytes = Files.readAllBytes(mappingsFile);
            catalog = json.readValue(catalogBytes, PreparedCatalog.Catalog.class);
            mappings = json.readValue(mappingBytes, PreparedCatalog.Mappings.class);
        } catch (IOException | IllegalArgumentException exception) {
            throw new CatalogImportException("Cannot read prepared catalog files; check paths and JSON format.");
        } catch (tools.jackson.core.JacksonException exception) {
            throw new CatalogImportException("Prepared catalog files must contain valid version-one JSON.");
        }
        validate(catalog, mappings);
        String catalogHash = sha256(catalogBytes);
        String mappingHash = sha256(mappingBytes);
        String fingerprint = sha256((IMPORTER_VERSION + "\n" + catalogHash + "\n" + mappingHash)
                .getBytes(StandardCharsets.UTF_8));
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("schemaVersion", 1);
        manifest.put("dataClass", catalog.dataClass());
        manifest.put("importerVersion", IMPORTER_VERSION);
        manifest.put("catalogSha256", catalogHash);
        manifest.put("mappingsSha256", mappingHash);
        manifest.put("mappingVersion", mappings.version());
        manifest.put("taxonomyVersion", mappings.taxonomyVersion());
        try {
            return transactions.execute(status -> publish(catalog, mappings, fingerprint, mappingHash, json.writeValueAsString(manifest)));
        } catch (DataAccessException exception) {
            throw new CatalogImportException("Catalog publication failed; the previous active release was preserved.");
        }
    }

    private ImportResult publish(PreparedCatalog.Catalog catalog, PreparedCatalog.Mappings mappings,
                                 String fingerprint, String mappingHash, String manifest) {
        jdbc.query("select pg_advisory_xact_lock(?)", result -> { }, PUBLISH_LOCK);
        var existing = jdbc.queryForList("select id, fingerprint, status from repaircalc.catalog_release where version = ?", catalog.version());
        if (!existing.isEmpty()) {
            var release = existing.getFirst();
            if (!fingerprint.equals(release.get("fingerprint")) || !"PUBLISHED".equals(release.get("status"))) {
                throw new CatalogImportException("Catalog version already exists with different content or publication state.");
            }
            return new ImportResult((UUID) release.get("id"), catalog.version(), fingerprint, catalog.products().size(), true);
        }
        var priorMappingHashes = jdbc.queryForList("""
                select distinct manifest->>'mappingsSha256' from repaircalc.catalog_release
                where status = 'PUBLISHED' and manifest->>'taxonomyVersion' = ? and manifest->>'mappingVersion' = ?
                """, String.class, mappings.taxonomyVersion(), mappings.version());
        if (priorMappingHashes.stream().anyMatch(hash -> !mappingHash.equals(hash))) {
            throw new CatalogImportException("Mapping version already exists with different file content; assign a new mapping version.");
        }
        var knownWorks = new HashSet<>(jdbc.queryForList(
                "select code from repaircalc.work_type where taxonomy_version = ?", String.class, mappings.taxonomyVersion()));
        if (knownWorks.isEmpty() || mappings.mappings().stream().flatMap(mapping -> mapping.works().stream())
                .anyMatch(code -> !knownWorks.contains(code))) {
            throw new CatalogImportException("Unknown work taxonomy or work code.");
        }
        for (var product : catalog.products()) {
            for (var source : product.sources()) {
                var previousIds = jdbc.queryForList("""
                        select distinct product_id from repaircalc.source_record
                        where source_namespace = ? and external_id = ?
                        union select id from repaircalc.admin_product where source_namespace = ? and external_id = ?
                        """, UUID.class, source.namespace(), source.externalId(), source.namespace(), source.externalId());
                if (previousIds.stream().anyMatch(id -> !id.equals(product.id()))) {
                    throw new CatalogImportException("An existing source identity cannot be assigned to another product UUID.");
                }
            }
        }
        UUID releaseId = UUID.randomUUID();
        jdbc.update("""
                insert into repaircalc.catalog_release (id, version, fingerprint, manifest, status, active)
                values (?, ?, ?, cast(? as jsonb), 'DRAFT', false)
                """, releaseId, catalog.version(), fingerprint, manifest);
        for (var product : catalog.products()) {
            jdbc.update("insert into repaircalc.product (id) values (?) on conflict (id) do nothing", product.id());
            jdbc.update("""
                    insert into repaircalc.product_revision
                    (id, release_id, product_id, name, category, kind, price, package_size, unit, sale_unit,
                     wet, high_load, unheated, description, source, consumption, roll_width, roll_length, rapport)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, UUID.randomUUID(), releaseId, product.id(), product.name(), product.category(), product.kind(),
                    product.price(), product.packageSize(), product.unit(), product.saleUnit(), product.wet(),
                    product.highLoad(), product.unheated(), product.description(), product.source(), product.consumption(),
                    product.rollWidth(), product.rollLength(), product.rapport());
            for (var source : product.sources()) {
                jdbc.update("""
                        insert into repaircalc.source_record
                        (id, release_id, product_id, source_namespace, external_id, raw)
                        values (?, ?, ?, ?, ?, cast(? as jsonb))
                        """, UUID.randomUUID(), releaseId, product.id(), source.namespace(), source.externalId(),
                        json.writeValueAsString(source.raw()));
            }
        }
        for (var mapping : mappings.mappings()) {
            for (var work : mapping.works()) {
                jdbc.update("""
                        insert into repaircalc.product_work
                        (release_id, product_id, taxonomy_version, work_code, mapping_version, evidence, source)
                        values (?, ?, ?, ?, ?, ?, 'manual_test_fixture')
                        """, releaseId, mapping.productId(), mappings.taxonomyVersion(), work, mappings.version(), mapping.evidence());
            }
        }
        jdbc.update("update repaircalc.catalog_release set active = false where active");
        jdbc.update("""
                update repaircalc.catalog_release set status = 'PUBLISHED', active = true, published_at = current_timestamp
                where id = ?
                """, releaseId);
        return new ImportResult(releaseId, catalog.version(), fingerprint, catalog.products().size(), false);
    }

    private void validate(PreparedCatalog.Catalog catalog, PreparedCatalog.Mappings mappings) {
        if (catalog == null || mappings == null) {
            throw new CatalogImportException("Catalog and mappings must be JSON objects.");
        }
        var errors = new java.util.TreeSet<String>();
        validator.validate(catalog).forEach(error -> errors.add("catalog." + error.getPropertyPath()));
        validator.validate(mappings).forEach(error -> errors.add("mappings." + error.getPropertyPath()));
        if (!errors.isEmpty()) {
            throw new CatalogImportException("Invalid prepared fields: " + String.join(", ", errors));
        }
        if (catalog.schemaVersion() != 1 || mappings.schemaVersion() != 1 || !"test".equals(catalog.dataClass())) {
            throw new CatalogImportException("Only version-one prepared test catalogs are supported.");
        }
        Set<UUID> products = new HashSet<>();
        Set<SourceIdentity> sources = new HashSet<>();
        for (var product : catalog.products()) {
            if (!products.add(product.id()) || !KINDS.contains(product.kind())) {
                throw new CatalogImportException("Duplicate product UUID or unknown product kind.");
            }
            if (!product.source().contains("ТЕСТОВЫЕ")) {
                throw new CatalogImportException("Test catalog product.source must explicitly identify test data (ТЕСТОВЫЕ).");
            }
            if ("wallpaper".equals(product.kind()) && product.packageSize() != null) {
                throw new CatalogImportException("Wallpaper packageSize must be null; store roll geometry separately.");
            }
            for (var source : product.sources()) {
                if (source.raw().isNull() || !sources.add(new SourceIdentity(source.namespace(), source.externalId()))) {
                    throw new CatalogImportException("Missing raw source data or duplicate namespaced source identity.");
                }
            }
        }
        Set<UUID> mapped = new HashSet<>();
        for (var mapping : mappings.mappings()) {
            if (!products.contains(mapping.productId()) || !mapped.add(mapping.productId())
                    || new HashSet<>(mapping.works()).size() != mapping.works().size()) {
                throw new CatalogImportException("Mappings contain an unknown product, duplicate product, or duplicate work code.");
            }
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be provided by the JDK", exception);
        }
    }

    private record SourceIdentity(String namespace, String externalId) { }
    public record ImportResult(UUID releaseId, String version, String fingerprint, int productCount, boolean noOp) { }
}
