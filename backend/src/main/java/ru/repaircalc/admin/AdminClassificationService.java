package ru.repaircalc.admin;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
public class AdminClassificationService {
    private final ObjectProvider<JdbcTemplate> provider;
    private final ObjectProvider<PlatformTransactionManager> managers;
    private final AdminProductService products;
    private final JsonMapper json;
    public AdminClassificationService(ObjectProvider<JdbcTemplate> provider, ObjectProvider<PlatformTransactionManager> managers,
                                      AdminProductService products, JsonMapper json) {
        this.provider = provider; this.managers = managers; this.products = products; this.json = json;
    }
    public List<Job> list(UUID productId) {
        products.get(productId);
        return jobs("where product_id = ? order by created_at desc, id desc limit 50", productId);
    }
    public Job enqueue(UUID productId, String match, String actor, String requestId) {
        AdminProductService.requireMatch(match);
        var manager = managers.getIfAvailable();
        if (manager == null) throw new AdminException(503, "admin_unavailable", "Хранилище недоступно.");
        return new TransactionTemplate(manager).execute(tx -> {
            jdbc().queryForList("select id from repaircalc.admin_product where id = ? for update", productId);
            var product = products.get(productId);
            if (!AdminProductService.etag(product).equals(match)) throw new AdminException(412, "version_mismatch", "Перечитайте карточку перед классификацией.");
            var pending = jobs("where version_id = ? and status in ('queued','running')", product.versionId());
            if (!pending.isEmpty()) return pending.getFirst();
            var id = UUID.randomUUID();
            jdbc().update("insert into repaircalc.admin_classification_job(id,product_id,version_id,input,created_by) values (?,?,?,?::jsonb,?)",
                    id, productId, product.versionId(), json.writeValueAsString(input(product)), actor);
            jdbc().update("""
                    insert into repaircalc.admin_audit_event(id,request_id,actor,action,product_id,details)
                    values (?,?,?,'classify',?,jsonb_build_object('jobId',?::text,'versionId',?::text))
                    """, UUID.randomUUID(), requestId, actor, productId, id.toString(), product.versionId().toString());
            return jobs("where id = ?", id).getFirst();
        });
    }
    private JsonNode input(AdminProductService.Product p) {
        var s = p.snapshot();
        var n = json.createObjectNode().put("source", p.sourceNamespace()).put("source_id", p.externalId())
                .put("name", s.path("name").asText()).put("description", s.path("description").asText(""))
                .put("marketing_description", s.path("application").asText("") + "\n" + s.path("limitations").asText(""));
        var categories = n.putArray("categories");
        if (s.path("category").path("state").asText().equals("confirmed")) {
            String category = s.path("category").path("value").asText();
            categories.addObject().put("id", category).put("name", category);
        }
        var attributes = n.putArray("attributes");
        for (var a : s.path("attributes")) attributes.add(a.deepCopy());
        return n;
    }
    private List<Job> jobs(String clause, Object... args) {
        return jdbc().query("select * from repaircalc.admin_classification_job " + clause, (r,i) ->
                new Job(r.getObject("id",UUID.class),r.getObject("version_id",UUID.class),r.getString("status"),r.getInt("attempts"),
                    json.readTree(r.getString("input")),r.getString("result") == null ? null : json.readTree(r.getString("result")),
                    r.getString("error_code"),r.getTimestamp("created_at").toInstant(),r.getTimestamp("finished_at") == null ? null : r.getTimestamp("finished_at").toInstant()), args);
    }
    private JdbcTemplate jdbc() {
        var j = provider.getIfAvailable();
        if (j == null) throw new AdminException(503,"admin_unavailable","Хранилище недоступно.");
        return j;
    }
    public record Job(UUID id, UUID versionId, String status, int attempts, JsonNode input, JsonNode result,
                      String errorCode, Instant createdAt, Instant finishedAt) { }
}
