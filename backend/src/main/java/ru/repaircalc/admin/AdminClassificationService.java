package ru.repaircalc.admin;
import java.time.Instant;
import java.util.*;
import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import ru.repaircalc.storage.MongoStore;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static ru.repaircalc.storage.MongoStore.query;
@Service
public class AdminClassificationService {
    private final MongoStore store;private final AdminProductService products;private final JsonMapper json;
    public AdminClassificationService(MongoStore store,AdminProductService products,JsonMapper json){this.store=store;this.products=products;this.json=json;}
    public List<Job> list(UUID productId){products.get(productId);return store.find("admin_classification_job",query("productId",productId.toString()).with(Sort.by(Sort.Direction.DESC,"createdAt","id")).limit(50)).stream().map(this::view).toList();}
    public Job enqueue(UUID id,String match,String actor,String requestId){return store.transaction(()->{
        var p=products.matched(id,match);var prior=store.one("admin_classification_job",query("versionId",p.versionId().toString()));if(prior!=null)return view(prior);
        products.touch(p,p.status(),actor);String jobId=UUID.randomUUID().toString();var input=input(p);
        var job=new Document("_id",jobId).append("id",jobId).append("productId",id.toString()).append("versionId",p.versionId().toString()).append("status","queued").append("attempts",0).append("input",store.document(input)).append("inputHash",MongoStore.sha(input.toString())).append("result",null).append("errorCode",null).append("createdAt",Instant.now().toString()).append("finishedAt",null).append("availableAt",System.currentTimeMillis());
        store.insert("admin_classification_job",job);products.audit(id,"classify",actor,requestId,new Document("jobId",jobId));return view(job);
    });}
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

    private Job view(Document d){return new Job(UUID.fromString(d.getString("id")),UUID.fromString(d.getString("versionId")),d.getString("status"),((Number)d.get("attempts")).intValue(),store.tree(d.get("input",Document.class)),d.get("result")==null?null:store.tree(d.get("result",Document.class)),d.getString("errorCode"),Instant.parse(d.getString("createdAt")),d.getString("finishedAt")==null?null:Instant.parse(d.getString("finishedAt")));}
    public record Job(UUID id,UUID versionId,String status,int attempts,JsonNode input,JsonNode result,String errorCode,Instant createdAt,Instant finishedAt){}
}
