package ru.repaircalc.admin;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
public final class AdminProductService {
    private static final long PUBLISH_LOCK = 73492026001L;
    private final ObjectProvider<JdbcTemplate> jdbcProvider;
    private final ObjectProvider<PlatformTransactionManager> manager;
    private final JsonMapper json;
    private final AdminProductValidator validator;

    public AdminProductService(ObjectProvider<JdbcTemplate> jdbc, ObjectProvider<PlatformTransactionManager> manager,
                               JsonMapper json, AdminProductValidator validator) {
        this.jdbcProvider = jdbc;
        this.manager = manager;
        this.json = json;
        this.validator = validator;
    }

    public Product create(JsonNode input, String actor, String requestId) {
        var snapshot = validator.validate(input, actor);
        return transaction(() -> {
            var jdbc = jdbc();
            jdbc.query("select pg_advisory_xact_lock(?)", rs -> { }, PUBLISH_LOCK);
            var namespace = snapshot.path("sourceNamespace").asText();
            var externalId = snapshot.path("externalId").asText();
            var duplicates = jdbc.queryForList("select id from repaircalc.admin_product where source_namespace = ? and external_id = ?", UUID.class, namespace, externalId);
            if (!duplicates.isEmpty()) throw new AdminException(409, "duplicate_identity", "Карточка с такой внешней идентичностью уже существует.",
                    List.of(new AdminException.Detail("id", "existing_product", duplicates.getFirst().toString())));
            var publicIds = jdbc.queryForList("select distinct product_id from repaircalc.source_record where source_namespace = ? and external_id = ?", UUID.class, namespace, externalId);
            if (publicIds.size() > 1) throw new AdminException(409, "ambiguous_identity", "Исходная идентичность связана с несколькими UUID.");
            UUID id = publicIds.isEmpty() ? UUID.randomUUID() : publicIds.getFirst();
            if (!jdbc.queryForList("select id from repaircalc.admin_product where id = ?", UUID.class, id).isEmpty()) {
                throw new AdminException(409, "duplicate_identity", "Товар уже имеет административную карточку.", List.of(new AdminException.Detail("id", "existing_product", id.toString())));
            }
            var versionId = UUID.randomUUID();
            jdbc.update("insert into repaircalc.admin_product(id, source_namespace, external_id, current_version_id, created_by, updated_by) values (?, ?, ?, ?, ?, ?)", id, namespace, externalId, versionId, actor, actor);
            insertVersion(id, versionId, 1, snapshot, actor);
            audit(id, null, versionId, "create", actor, requestId);
            return get(id);
        });
    }

    public Product update(UUID id, String ifMatch, JsonNode input, String actor, String requestId) {
        requireMatch(ifMatch);
        var snapshot = validator.validate(input, actor);
        return transaction(() -> {
            var rows = jdbc().queryForList("select id from repaircalc.admin_product where id = ? for update", UUID.class, id);
            if (rows.isEmpty()) throw missing();
            var before = get(id);
            if (!etag(before).equals(ifMatch)) throw new AdminException(412, "version_mismatch", "Карточка изменилась. Перечитайте её перед сохранением.");
            if (!before.sourceNamespace().equals(snapshot.path("sourceNamespace").asText()) || !before.externalId().equals(snapshot.path("externalId").asText())) {
                throw new AdminException(409, "immutable_identity", "Внешнюю идентичность карточки нельзя изменить.");
            }
            var version = UUID.randomUUID();
            insertVersion(id, version, before.version() + 1, snapshot, actor);
            jdbc().update("update repaircalc.admin_product set current_version_id = ?, status = 'draft', updated_at = current_timestamp, updated_by = ? where id = ?", version, actor, id);
            audit(id, before.versionId(), version, "update", actor, requestId);
            return get(id);
        });
    }

    public Product get(UUID id) {
        return safe(() -> {
            var products = jdbc().query("""
                    select p.*, v.version, v.snapshot::text from repaircalc.admin_product p
                    join repaircalc.admin_product_version v on v.id = p.current_version_id where p.id = ?
                    """, this::product, id);
            if (products.isEmpty()) throw missing();
            return products.getFirst();
        });
    }

    public Page<Product> list(String q, String status, String category, String sort, int page, int size) {
        if (page < 0 || size < 1 || size > 100 || q.length() > 500 || category.length() > 4000
                || (!status.isEmpty() && !List.of("draft", "review", "published", "archived").contains(status))
                || !List.of("created", "-created", "updated", "-updated").contains(sort)) throw new AdminException(422, "invalid_filter", "Проверьте фильтры, сортировку и размер страницы (1–100).");
        return safe(() -> {
            String from = """
                    from repaircalc.admin_product p join repaircalc.admin_product_version v on v.id = p.current_version_id
                    where (? = '' or position(lower(?) in lower(v.snapshot->>'name')) > 0 or position(lower(?) in lower(p.external_id)) > 0)
                    and (? = '' or p.status = ?) and (? = '' or v.snapshot->'category'->>'value' = ?)
                    """;
            var args = new Object[]{q, q, q, status, status, category, category};
            var total = jdbc().queryForObject("select count(*) " + from, Long.class, args);
            var parameters = new java.util.ArrayList<>(java.util.Arrays.asList(args));
            parameters.add(size);
            parameters.add((long) page * size);
            String order = sort.contains("created") ? "p.created_at" : "p.updated_at";
            String direction = sort.startsWith("-") ? " desc" : " asc";
            var items = jdbc().query("select p.*, v.version, v.snapshot::text " + from + " order by " + order + direction + ", p.id" + direction + " limit ? offset ?", this::product, parameters.toArray());
            return new Page<>(items, page, size, total);
        });
    }

    public Page<Version> history(UUID id, int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw new AdminException(422, "invalid_page", "Размер страницы должен быть от 1 до 100.");
        return safe(() -> {
            get(id);
            var versions = jdbc().query("""
                    select v.*, a.request_id, a.action from repaircalc.admin_product_version v
                    left join repaircalc.admin_audit_event a on a.after_version_id = v.id
                    where v.product_id = ? order by v.version desc limit ? offset ?
                    """, (rs, n) -> new Version(rs.getObject("id", UUID.class), rs.getInt("version"),
                    json.readTree(rs.getString("snapshot")), rs.getTimestamp("created_at").toInstant(), rs.getString("created_by"), rs.getString("request_id"), rs.getString("action")), id, size, (long) page * size);
            return new Page<>(versions, page, size, jdbc().queryForObject("select count(*) from repaircalc.admin_product_version where product_id = ?", Long.class, id));
        });
    }

    private Product product(ResultSet rs, int index) throws SQLException {
        return new Product(rs.getObject("id", UUID.class), rs.getString("source_namespace"), rs.getString("external_id"),
                rs.getObject("current_version_id", UUID.class), rs.getInt("version"), rs.getString("status"), json.readTree(rs.getString("snapshot")),
                rs.getTimestamp("created_at").toInstant(), rs.getString("created_by"), rs.getTimestamp("updated_at").toInstant(), rs.getString("updated_by"));
    }

    private void insertVersion(UUID id, UUID versionId, int version, JsonNode snapshot, String actor) {
        jdbc().update("insert into repaircalc.admin_product_version(id, product_id, version, snapshot, created_by) values (?, ?, ?, ?::jsonb, ?)", versionId, id, version, json.writeValueAsString(snapshot), actor);
    }
    private void audit(UUID id, UUID before, UUID after, String action, String actor, String requestId) {
        jdbc().update("insert into repaircalc.admin_audit_event(id, request_id, actor, action, product_id, before_version_id, after_version_id) values (?, ?, ?, ?, ?, ?, ?)", UUID.randomUUID(), requestId, actor, action, id, before, after);
    }
    private JdbcTemplate jdbc() {
        var jdbc = jdbcProvider.getIfAvailable();
        if (jdbc == null) throw unavailable();
        return jdbc;
    }
    private <T> T transaction(java.util.function.Supplier<T> action) {
        return safe(() -> {
            var tx = manager.getIfAvailable();
            if (tx == null) throw unavailable();
            return new TransactionTemplate(tx).execute(status -> action.get());
        });
    }
    private <T> T safe(java.util.function.Supplier<T> action) {
        try { return action.get(); }
        catch (DataAccessException | org.springframework.transaction.TransactionException exception) { throw unavailable(); }
    }
    private static AdminException missing() { return new AdminException(404, "product_not_found", "Карточка не найдена."); }
    private static AdminException unavailable() { return new AdminException(503, "admin_unavailable", "Хранилище административных карточек временно недоступно."); }
    public static String etag(Product product) { return "\"" + product.versionId() + "\""; }
    public static void requireMatch(String match) {
        if (match == null || match.isBlank()) throw new AdminException(428, "if_match_required", "Для сохранения требуется If-Match текущей версии.");
    }
    public record Product(UUID id, String sourceNamespace, String externalId, UUID versionId, int version, String status,
                          JsonNode snapshot, Instant createdAt, String createdBy, Instant updatedAt, String updatedBy) { }
    public record Version(UUID id, int version, JsonNode snapshot, Instant createdAt, String createdBy, String requestId, String action) { }
    public record Page<T>(List<T> items, int page, int size, long total) { }
}
