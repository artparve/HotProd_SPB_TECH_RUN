package ru.repaircalc.rules;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.type.LogicalType;

@Service
public class RuleSetImportService {
    public static final String IMPORTER_VERSION = "prepared-rules-v1";
    private static final long PUBLISH_LOCK = 73492026001L;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final JsonMapper json;
    private final RuleSetValidator validator;

    public RuleSetImportService(JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
                                JsonMapper json, RuleSetValidator validator) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.json = strictMapper(json);
        this.validator = validator;
    }

    static JsonMapper strictMapper(JsonMapper mapper) {
        return mapper.rebuild().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .disable(EnumFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL)
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .withCoercionConfig(LogicalType.Integer, config -> config.setCoercion(CoercionInputShape.String, CoercionAction.Fail))
                .withCoercionConfig(LogicalType.Textual, config -> config
                        .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail))
                .withCoercionConfig(LogicalType.Enum, config -> config.setCoercion(CoercionInputShape.Integer, CoercionAction.Fail))
                .build();
    }

    public ImportResult importFile(Path file) {
        final byte[] bytes;
        final RuleSetBundle bundle;
        try {
            bytes = Files.readAllBytes(file);
            bundle = json.readValue(bytes, RuleSetBundle.class);
        } catch (IOException | IllegalArgumentException | tools.jackson.core.JacksonException exception) {
            throw new RuleSetImportException("Cannot read version-one rules; check the path and JSON format.");
        }
        validator.validateShape(bundle);
        String fileHash = sha256(bytes);
        String fingerprint = sha256((IMPORTER_VERSION + "\n" + fileHash).getBytes(StandardCharsets.UTF_8));
        try {
            return transactions.execute(status -> publish(bundle, fileHash, fingerprint));
        } catch (DataAccessException exception) {
            throw new RuleSetImportException("Rule publication failed; the previous active rules were preserved.");
        }
    }

    private ImportResult publish(RuleSetBundle bundle, String fileHash, String fingerprint) {
        jdbc.query("select pg_advisory_xact_lock(?)", result -> { }, PUBLISH_LOCK);
        var prior = jdbc.queryForList("select id, catalog_release_id, fingerprint, status from repaircalc.rule_set where version = ?", bundle.version());
        if (!prior.isEmpty()) {
            var row = prior.getFirst();
            if (!fingerprint.equals(row.get("fingerprint")) || !"PUBLISHED".equals(row.get("status"))) {
                throw new RuleSetImportException("Rule version already exists with different content or publication state.");
            }
            return new ImportResult((UUID) row.get("id"), (UUID) row.get("catalog_release_id"), bundle.version(), fingerprint, true);
        }
        var catalogs = jdbc.queryForList("""
                select id, fingerprint, manifest->>'taxonomyVersion' as taxonomy_version,
                       manifest->>'dataClass' as data_class
                from repaircalc.catalog_release where version = ? and status = 'PUBLISHED'
                """, bundle.catalog().version());
        if (catalogs.size() != 1 || !bundle.catalog().fingerprint().equals(catalogs.getFirst().get("fingerprint"))
                || !bundle.catalog().taxonomyVersion().equals(catalogs.getFirst().get("taxonomy_version"))
                || !bundle.dataClass().equals(catalogs.getFirst().get("data_class"))) {
            throw new RuleSetImportException("Rules must reference an exact published catalog fingerprint, taxonomy and data class.");
        }
        UUID catalogId = (UUID) catalogs.getFirst().get("id");
        var products = jdbc.query("""
                select product_id, kind, unit, package_size, consumption, roll_width, roll_length, rapport
                from repaircalc.product_revision where release_id = ?
                """, (rs, index) -> new RuleSetValidator.ProductFacts(rs.getObject("product_id", UUID.class),
                rs.getString("kind"), rs.getString("unit"), rs.getBigDecimal("package_size"),
                rs.getBigDecimal("consumption"), rs.getBigDecimal("roll_width"), rs.getBigDecimal("roll_length"),
                rs.getBigDecimal("rapport")), catalogId);
        validator.validateProducts(bundle, products);
        UUID id = UUID.randomUUID();
        var manifest = Map.of("schemaVersion", 1, "dataClass", bundle.dataClass(), "importerVersion", IMPORTER_VERSION,
                "rulesSha256", fileHash, "catalogFingerprint", bundle.catalog().fingerprint(),
                "taxonomyVersion", bundle.catalog().taxonomyVersion());
        jdbc.update("""
                insert into repaircalc.rule_set
                (id, version, fingerprint, catalog_release_id, taxonomy_version, bundle, manifest)
                values (?, ?, ?, ?, ?, cast(? as jsonb), cast(? as jsonb))
                """, id, bundle.version(), fingerprint, catalogId, bundle.catalog().taxonomyVersion(),
                json.writeValueAsString(bundle), json.writeValueAsString(manifest));
        jdbc.update("update repaircalc.rule_set set active = false where catalog_release_id = ? and active", catalogId);
        jdbc.update("update repaircalc.rule_set set status = 'PUBLISHED', active = true, published_at = current_timestamp where id = ?", id);
        return new ImportResult(id, catalogId, bundle.version(), fingerprint, false);
    }

    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256 must be provided by the JDK", exception); }
    }

    public record ImportResult(UUID ruleSetId, UUID catalogReleaseId, String version, String fingerprint, boolean noOp) { }
}
