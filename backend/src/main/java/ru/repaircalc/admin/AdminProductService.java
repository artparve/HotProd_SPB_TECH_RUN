package ru.repaircalc.admin;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;
import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.stereotype.Service;
import ru.repaircalc.storage.MongoStore;
import tools.jackson.databind.JsonNode;
import static ru.repaircalc.storage.MongoStore.query;
@Service
public class AdminProductService {
    private final MongoStore store; private final AdminProductValidator validator;
    public AdminProductService(MongoStore store,AdminProductValidator validator){this.store=store;this.validator=validator;}
    public Product create(JsonNode input,String actor,String requestId){
        var snapshot=validator.validate(input,actor);
        try{return store.transaction(()->{
            String namespace=snapshot.path("sourceNamespace").asText(),external=snapshot.path("externalId").asText();
            var identity=store.one("source_identity",query("namespace",namespace).addCriteria(Criteria.where("externalId").is(external)));
            UUID id=identity==null?UUID.randomUUID():UUID.fromString(identity.getString("productId"));
            if(identity==null)store.insert("source_identity",new Document("_id",UUID.randomUUID().toString()).append("namespace",namespace).append("externalId",external).append("productId",id.toString()));
            Instant now=Instant.now();var p=new Product(id,namespace,external,UUID.randomUUID(),1,"draft",snapshot,now,actor,now,actor);
            store.insert("admin_product",store.document(p).append("_id",id.toString()));version(p,requestId,"create");
            audit(id,"create",actor,requestId,new Document("afterVersionId",p.versionId().toString()));return p;
        });}catch(org.springframework.dao.DuplicateKeyException e){throw new AdminException(409,"duplicate_identity","Карточка с этой идентичностью уже существует.");}
    }
    public Product update(UUID id,String match,JsonNode input,String actor,String requestId){
        requireMatch(match);var snapshot=validator.validate(input,actor);
        return store.transaction(()->{
            var before=matched(id,match);
            if(!before.sourceNamespace().equals(snapshot.path("sourceNamespace").asText()) || !before.externalId().equals(snapshot.path("externalId").asText()))throw new AdminException(409,"immutable_identity","Идентичность нельзя менять.");
            var after=new Product(id,before.sourceNamespace(),before.externalId(),UUID.randomUUID(),before.version()+1,"draft",snapshot,before.createdAt(),before.createdBy(),Instant.now(),actor);
            var update=new Update();store.document(after).forEach(update::set);
            if(store.update("admin_product",query("_id",id.toString()).addCriteria(Criteria.where("versionId").is(before.versionId().toString())),update)!=1)throw mismatch();
            version(after,requestId,"update");audit(id,"update",actor,requestId,new Document("beforeVersionId",before.versionId().toString()).append("afterVersionId",after.versionId().toString()));return after;
        });
    }
    public Product get(UUID id){var d=store.id("admin_product",id);if(d==null)throw new AdminException(404,"product_not_found","Карточка не найдена.");return store.read(d,Product.class);}
    public Product matched(UUID id,String match){requireMatch(match);var p=get(id);if(!etag(p).equals(match))throw mismatch();return p;}
    public void touch(Product p,String status,String actor){if(store.update("admin_product",query("_id",p.id().toString()).addCriteria(Criteria.where("versionId").is(p.versionId().toString())),new Update().set("status",status).set("updatedBy",actor).set("updatedAt",Instant.now().toString()))!=1)throw mismatch();}
    public Page<Product> list(String text,String status,String category,String sort,int page,int size){
        pagination(page,size);
        if(text.length()>500 || category.length()>4000 || (!status.isEmpty()&&!Set.of("draft","review","published","archived").contains(status)) || !Set.of("created","-created","updated","-updated").contains(sort))throw new AdminException(422,"invalid_filter","Неверные фильтры.");
        var q=new Query();if(!text.isEmpty())q.addCriteria(new Criteria().orOperator(Criteria.where("snapshot.name").regex(Pattern.quote(text),"i"),Criteria.where("externalId").regex(Pattern.quote(text),"i")));
        if(!status.isEmpty())q.addCriteria(Criteria.where("status").is(status));if(!category.isEmpty())q.addCriteria(Criteria.where("snapshot.category.value").is(category));
        long total=store.mongo.count(q,"admin_product");q.with(Sort.by(sort.startsWith("-")?Sort.Direction.DESC:Sort.Direction.ASC,sort.contains("created")?"createdAt":"updatedAt","id")).skip((long)page*size).limit(size);
        return new Page<>(store.find("admin_product",q).stream().map(d->store.read(d,Product.class)).toList(),page,size,total);
    }
    public Page<Version> history(UUID id,int page,int size){
        pagination(page,size);get(id);var q=query("productId",id.toString());long total=store.mongo.count(q,"admin_product_version");q.with(Sort.by(Sort.Direction.DESC,"version")).skip((long)page*size).limit(size);
        return new Page<>(store.find("admin_product_version",q).stream().map(d->{var copy=new Document(d);copy.remove("productId");return store.read(copy,Version.class);}).toList(),page,size,total);
    }
    private void version(Product p,String requestId,String action){var v=new Version(p.versionId(),p.version(),p.snapshot(),p.updatedAt(),p.updatedBy(),requestId,action);store.insert("admin_product_version",store.document(v).append("_id",v.id().toString()).append("productId",p.id().toString()));}
    public void audit(UUID id,String action,String actor,String requestId,Document details){store.insert("admin_audit_event",new Document("_id",UUID.randomUUID().toString()).append("productId",id.toString()).append("action",action).append("actor",actor).append("requestId",requestId).append("details",details).append("createdAt",Instant.now().toString()));}
    private static void pagination(int page,int size){if(page<0||size<1||size>100)throw new AdminException(422,"invalid_page","Размер страницы 1–100.");}
    private static AdminException mismatch(){return new AdminException(412,"version_mismatch","Карточка изменилась. Перечитайте её.");}
    public static String etag(Product p){return "\""+p.versionId()+"\"";}
    public static void requireMatch(String match){if(match==null||match.isBlank())throw new AdminException(428,"if_match_required","Требуется If-Match текущей версии.");}
    public record Product(UUID id,String sourceNamespace,String externalId,UUID versionId,int version,String status,JsonNode snapshot,Instant createdAt,String createdBy,Instant updatedAt,String updatedBy){}
    public record Version(UUID id,int version,JsonNode snapshot,Instant createdAt,String createdBy,String requestId,String action){}
    public record Page<T>(List<T> items,int page,int size,long total){}
}
