package ru.repaircalc.admin;

import java.io.IOException;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
public class AdminReviewService {
    public static final String MAPPING = "classifier-java-reviewed-v1";
    public static final String TAXONOMY = "repair-product-taxonomy-v2";
    public static final List<String> GROUPS = List.of("work_types","surface_locations","substrates","properties");
    private final JsonMapper json;
    private final ObjectProvider<JdbcTemplate> provider;
    private final ObjectProvider<PlatformTransactionManager> managers;
    private final AdminProductService products;
    private final JsonNode taxonomy;
    public AdminReviewService(JsonMapper json, ObjectProvider<JdbcTemplate> provider,
                              ObjectProvider<PlatformTransactionManager> managers, AdminProductService products) throws IOException {
        this.json=json; this.provider=provider; this.managers=managers; this.products=products;
        try (var in=new ClassPathResource("classification-taxonomy.json").getInputStream()) { taxonomy=json.readTree(in); }
    }
    public JsonNode options() {
        var n=json.createObjectNode();
        for (var g:GROUPS) n.set(g,taxonomy.path(g));
        n.put("taxonomyVersion",TAXONOMY).put("mappingVersion",MAPPING);
        n.set("profiles",json.valueToTree(jdbc().queryForList("""
            select b.bundle->'profiles' as profiles from repaircalc.rule_set b
            join repaircalc.catalog_release c on c.id=b.catalog_release_id where c.active and b.active
            """).stream().map(r -> json.readTree(r.get("profiles").toString())).findFirst().orElse(json.createArrayNode())));
        return n;
    }
    public List<JsonNode> list(UUID id) {
        products.get(id);
        return jdbc().query("select to_jsonb(r)::text from repaircalc.admin_label_review r where product_id=? order by created_at desc,id desc limit 50",
                (r,i)->json.readTree(r.getString(1)),id);
    }
    public JsonNode review(UUID id,String match,JsonNode body,String actor,String requestId) {
        AdminProductService.requireMatch(match);
        var manager=managers.getIfAvailable();
        if(manager==null) throw new AdminException(503,"admin_unavailable","Хранилище недоступно.");
        return new TransactionTemplate(manager).execute(tx->{
            jdbc().queryForList("select id from repaircalc.admin_product where id=? for update",id);
            var p=products.get(id);
            if(!AdminProductService.etag(p).equals(match)) throw new AdminException(412,"version_mismatch","Карточка изменилась.");
            if(!jdbc().queryForList("select id from repaircalc.admin_publication where version_id=?",p.versionId()).isEmpty())
                throw new AdminException(409,"already_published","Создайте новую версию перед повторной проверкой.");
            if(!body.isObject() || !Set.of("jobId","decisions","profileId").equals(body.propertyNames())) invalid("Проверьте поля решения.");
            UUID jobId;
            try { jobId=UUID.fromString(body.path("jobId").asText()); } catch(IllegalArgumentException e) { throw invalidException("Неверный UUID задания."); }
            var rows=jdbc().queryForList("select result::text from repaircalc.admin_classification_job where id=? and product_id=? and version_id=? and status='succeeded'",jobId,id,p.versionId());
            if(rows.isEmpty()) throw new AdminException(409,"stale_classification","Нужен успешный результат для текущей версии карточки.");
            var result=json.readTree(rows.getFirst().get("result").toString());
            if(!TAXONOMY.equals(result.path("taxonomy_version").asText())) invalid("Неизвестная версия таксономии.");
            var approved=validateDecisions(result.path("classification"),body.path("decisions"));
            var works=mapWorks(approved,p.snapshot().path("kind").path("value").asText());
            String profile=body.path("profileId").asText();
            if(!body.path("profileId").isString() || profile.isBlank() || profile.length()>500) invalid("Выберите профиль расчёта.");
            var review=UUID.randomUUID();
            jdbc().update("""
                insert into repaircalc.admin_label_review(id,product_id,version_id,job_id,decisions,approved,java_works,mapping_version,profile_id,created_by)
                values (?,?,?,?,?::jsonb,?::jsonb,?::jsonb,?,?,?)
                """,review,id,p.versionId(),jobId,json.writeValueAsString(body.path("decisions")),json.writeValueAsString(approved),json.writeValueAsString(works),MAPPING,profile,actor);
            jdbc().update("update repaircalc.admin_product set status='review',updated_by=?,updated_at=now() where id=?",actor,id);
            jdbc().update("insert into repaircalc.admin_audit_event(id,request_id,actor,action,product_id,details) values (?,?,?,'review',?,jsonb_build_object('reviewId',?::text))",UUID.randomUUID(),requestId,actor,id,review.toString());
            return list(id).getFirst();
        });
    }
    ObjectNode validateDecisions(JsonNode classification,JsonNode decisions) {
        if(!decisions.isArray() || decisions.size()>100) invalid("Ожидается массив решений.");
        var expected=new HashSet<String>();
        for(var g:GROUPS) {
            if(g.equals("properties")) for(var key:classification.path(g).propertyNames()) expected.add(g+":"+key);
            else for(var label:classification.path(g)) expected.add(g+":"+label.path("label").asText());
        }
        var approved=json.createObjectNode(); for(var g:GROUPS) approved.putArray(g);
        var seen=new HashSet<String>();
        for(var d:decisions) {
            if(!d.isObject() || !Set.of("group","label","action","value","reason").containsAll(d.propertyNames())) invalid("Неверные поля решения.");
            String group=d.path("group").asText(), label=d.path("label").asText(), action=d.path("action").asText();
            String key=group+":"+label;
            if(!expected.contains(key) || !seen.add(key) || !Set.of("accept","reject","correct").contains(action)) invalid("Каждое предложение требует одного решения.");
            if(!d.path("reason").isString() || d.path("reason").asText().isBlank() || d.path("reason").asText().length()>4000) invalid("Укажите основание решения.");
            if(action.equals("reject")) continue;
            String value=action.equals("correct") ? d.path("value").asText() : group.equals("properties") ? classification.path(group).path(label).path("state").asText() : label;
            if(group.equals("properties")) {
                if(!Set.of("yes","no","unknown","conflict").contains(value)) invalid("Неверное состояние свойства.");
                ((tools.jackson.databind.node.ArrayNode)approved.get(group)).addObject().put("label",label).put("state",value);
            } else {
                boolean known=false; for(var entry:taxonomy.path(group)) if(entry.path("id").asText().equals(value)) known=true;
                if(!known) invalid("Метка отсутствует в таксономии.");
                var a=(tools.jackson.databind.node.ArrayNode)approved.get(group);
                for(var old:a) if(old.asText().equals(value)) invalid("Исправления создали повторную метку.");
                a.add(value);
            }
        }
        if(!seen.equals(expected) || expected.isEmpty()) invalid("Проверьте все предложения модели.");
        return approved;
    }
    List<String> mapWorks(JsonNode approved,String kind) {
        var works=new ArrayList<String>();
        var labels=new HashSet<String>(); for(var x:approved.path("work_types")) labels.add(x.asText());
        var surfaces=new HashSet<String>(); for(var x:approved.path("surface_locations")) surfaces.add(x.asText());
        // Broader Python concepts require explicit context before entering Java's four work codes.
        if(labels.contains("floor") && surfaces.contains("floor") && kind.equals("covering")) works.add("floor");
        if(labels.contains("paint") && surfaces.contains("wall") && kind.equals("paint")) works.add("paint");
        if(labels.contains("wallpaper") && surfaces.contains("wall") && kind.equals("wallpaper")) works.add("wallpaper");
        if(labels.contains("electrical") && Set.of("socket","cable").contains(kind)) works.add("electrical");
        return works;
    }
    private JdbcTemplate jdbc(){var j=provider.getIfAvailable(); if(j==null) throw new AdminException(503,"admin_unavailable","Хранилище недоступно."); return j;}
    private static void invalid(String message){throw invalidException(message);}
    private static AdminException invalidException(String message){return new AdminException(422,"invalid_review",message);}
}
