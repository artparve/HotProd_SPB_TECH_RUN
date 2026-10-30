package ru.repaircalc.admin;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Service;
import ru.repaircalc.storage.MongoStore;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static ru.repaircalc.storage.MongoStore.query;
@Service
public class AdminReviewService {
    public static final String MAPPING="classifier-java-reviewed-v2",TAXONOMY="repair-product-taxonomy-v2";
    public static final List<String> GROUPS=List.of("work_types","surface_locations","substrates","properties");
    private final JsonMapper json;private final MongoStore store;private final AdminProductService products;private final JsonNode taxonomy;
    public AdminReviewService(JsonMapper json,MongoStore store,AdminProductService products,@Value("${CLASSIFICATION_TAXONOMY:../data/classification/taxonomy.json}")String path)throws IOException{this.json=json;this.store=store;this.products=products;this.taxonomy=json.readTree(Files.readString(Path.of(path)));}
    public JsonNode options(){var n=json.createObjectNode();for(var g:GROUPS)n.set(g,taxonomy.path(g));n.put("taxonomyVersion",TAXONOMY).put("mappingVersion",MAPPING);var p=store.id("publication_pointer","active");var rules=p==null?null:store.id("rule_set",p.getString("ruleSetId"));n.set("profiles",rules==null?json.createArrayNode():store.tree(rules.get("bundle",Document.class)).path("profiles"));return n;}
    public List<JsonNode> list(UUID id){products.get(id);return store.find("admin_label_review",query("product_id",id.toString()).with(Sort.by(Sort.Direction.DESC,"created_at","id")).limit(50)).stream().map(store::tree).toList();}
    public JsonNode review(UUID id,String match,JsonNode body,String actor,String requestId){return store.transaction(()->{
        var p=products.matched(id,match);
        if(store.one("admin_publication",query("version_id",p.versionId().toString()))!=null)throw new AdminException(409,"already_published","Создайте новую версию.");
        if(!body.isObject() || !Set.of("jobId","decisions","profileId").equals(body.propertyNames()))invalid("Проверьте поля решения.");
        UUID jobId;try{jobId=UUID.fromString(body.path("jobId").asText());}catch(IllegalArgumentException e){throw invalidException("Неверный UUID задания.");}
        var job=store.id("admin_classification_job",jobId);
        if(job==null || !id.toString().equals(job.getString("productId")) || !p.versionId().toString().equals(job.getString("versionId")) || !"succeeded".equals(job.getString("status")))throw new AdminException(409,"stale_classification","Нужна успешная классификация текущей версии.");
        var result=store.tree(job.get("result",Document.class));if(!TAXONOMY.equals(result.path("taxonomy_version").asText()))invalid("Неизвестная таксономия.");
        var approved=validateDecisions(result.path("classification"),body.path("decisions"));var works=mapWorks(approved,p.snapshot().path("kind").path("value").asText());
        String profile=body.path("profileId").asText();if(!body.path("profileId").isString()||profile.isBlank()||profile.length()>500)invalid("Выберите профиль.");
        String rid=UUID.randomUUID().toString();var r=new Document("_id",rid).append("id",rid).append("product_id",id.toString()).append("version_id",p.versionId().toString()).append("job_id",jobId.toString()).append("decisions",store.document(Map.of("items",body.path("decisions"))).get("items")).append("approved",store.document(approved)).append("java_works",works).append("mapping_version",MAPPING).append("profile_id",profile).append("created_by",actor).append("created_at",java.time.Instant.now().toString());
        products.touch(p,"review",actor);store.insert("admin_label_review",r);products.audit(id,"review",actor,requestId,new Document("reviewId",rid));return store.tree(r);
    });}
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
        if(labels.contains("priming") && surfaces.contains("wall") && kind.equals("primer")){works.add("paint");works.add("wallpaper");}
        if(labels.contains("tile") && surfaces.contains("floor") && kind.equals("adhesive"))works.add("floor");
        return works;
    }
    private static void invalid(String message){throw invalidException(message);}
    private static AdminException invalidException(String message){return new AdminException(422,"invalid_review",message);}
}
