package ru.repaircalc.admin;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ru.repaircalc.rules.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Copy-on-publish: retain all other products, assignments and historical snapshots. */
@Service
public class AdminPublicationService {
    private static final long LOCK=73492026001L;
    private final ObjectProvider<JdbcTemplate> provider;
    private final ObjectProvider<PlatformTransactionManager> managers;
    private final AdminProductService products;
    private final JsonMapper json;
    private final RuleSetValidator validator;
    public AdminPublicationService(ObjectProvider<JdbcTemplate> provider,ObjectProvider<PlatformTransactionManager> managers,
                                   AdminProductService products,JsonMapper json,RuleSetValidator validator) {
        this.provider=provider; this.managers=managers; this.products=products; this.json=json; this.validator=validator;
    }
    public Readiness readiness(UUID id) { return inspect(products.get(id)); }
    private Readiness inspect(AdminProductService.Product p) {
        var blockers=new ArrayList<String>(); var s=p.snapshot();
        for(var key:List.of("category","price","currency","priceDate","kind","unit","saleUnit"))
            if(!confirmed(s,key)) blockers.add("Требуется подтверждение: "+key);
        if(s.path("sources").isEmpty()) blockers.add("Добавьте источник карточки.");
        var reviews=jdbc().query("select to_jsonb(r)::text from repaircalc.admin_label_review r where version_id=? order by created_at desc,id desc limit 1",(r,i)->json.readTree(r.getString(1)),p.versionId());
        JsonNode review=reviews.isEmpty()?null:reviews.getFirst();
        if(review==null) blockers.add("Нет проверки классификации для текущей версии.");
        else if(review.path("java_works").isEmpty()) blockers.add("Нет проверенного сопоставления с Java-таксономией.");
        var base=base();
        if(base==null) blockers.add("Нет активной совместимой пары каталога и правил.");
        if(base!=null && review!=null && blockers.isEmpty()) {
            try {
                var old=json.readValue(base.path("bundle").toString(),RuleSetBundle.class);
                var profile=old.profiles().stream().filter(x->x.id().equals(review.path("profile_id").asText())).findFirst().orElse(null);
                if(profile==null) blockers.add("Выбранный профиль отсутствует в действующих правилах.");
                else {
                    if(profile.consumption()!=null && (!confirmed(s,"consumption") || !confirmed(s,"consumptionUnit")
                            || !s.path("consumptionUnit").path("value").asText().equals(profile.unit()))) blockers.add("Подтвердите норму расхода и её единицу.");
                    if(profile.strategy()==RuleSetBundle.Strategy.WALLPAPER) {
                        for(var field:List.of("rollWidth","rollLength","rapport")) if(!confirmed(s,field)) blockers.add("Требуется подтверждение: "+field);
                    } else if(!confirmed(s,"packageSize")) blockers.add("Подтвердите фасовку.");
                    var test=new RuleSetBundle(old.schemaVersion(),old.version(),old.dataClass(),old.catalog(),old.sources(),old.profiles(),
                            List.of(new RuleSetBundle.Assignment(p.id(),profile.id(),null)),old.laborTariffs());
                    validator.validateShape(test); validator.validateProducts(test,List.of(facts(p)));
                }
            } catch(RuleSetImportException|IllegalArgumentException e) { blockers.add("Профиль несовместим с данными товара: "+e.getMessage()); }
        }
        return new Readiness(blockers.isEmpty(),blockers,review==null?null:UUID.fromString(review.path("id").asText()),base==null?null:UUID.fromString(base.path("id").asText()));
    }
    public JsonNode publish(UUID id,String match,String actor,String requestId) {
        AdminProductService.requireMatch(match);
        var manager=managers.getIfAvailable(); if(manager==null) throw new AdminException(503,"admin_unavailable","Хранилище недоступно.");
        try {
            return new TransactionTemplate(manager).execute(tx->{
                jdbc().query("select pg_advisory_xact_lock(?)",r->{},LOCK);
                jdbc().queryForList("select id from repaircalc.admin_product where id=? for update",id);
                var p=products.get(id);
                if(!AdminProductService.etag(p).equals(match)) throw new AdminException(412,"version_mismatch","Карточка изменилась.");
                var prior=jdbc().query("select to_jsonb(x)::text from repaircalc.admin_publication x where version_id=?",(r,i)->json.readTree(r.getString(1)),p.versionId());
                if(!prior.isEmpty()) return prior.getFirst();
                var readiness=inspect(p);
                if(!readiness.ready()) throw new AdminException(422,"not_ready","Карточка не готова к публикации.",readiness.blockers().stream().map(x->new AdminException.Detail("readiness","blocked",x)).toList());
                var base=base();
                var review=jdbc().queryForObject("select to_jsonb(r)::text from repaircalc.admin_label_review r where id=?",String.class,readiness.reviewId());
                var r=json.readTree(review); var s=p.snapshot();
                UUID release=UUID.randomUUID(), rules=UUID.randomUUID();
                String version="admin-catalog-"+release, ruleVersion="admin-rules-"+rules;
                String fingerprint=sha(base.path("fingerprint").asText()+p.versionId()+review+base.path("rule_fingerprint").asText());
                var manifest=(ObjectNode)base.path("manifest").deepCopy();
                manifest.put("baseReleaseId",base.path("id").asText()).put("adminVersionId",p.versionId().toString())
                        .put("reviewId",readiness.reviewId().toString()).put("mappingVersion",AdminReviewService.MAPPING);
                jdbc().update("insert into repaircalc.catalog_release(id,version,fingerprint,manifest) values (?,?,?,?::jsonb)",release,version,fingerprint,manifest.toString());
                jdbc().update("insert into repaircalc.product(id) values (?) on conflict do nothing",id);
                jdbc().update("""
                    insert into repaircalc.product_revision(id,release_id,product_id,cod_good,name,category,kind,price,package_size,unit,sale_unit,wet,high_load,unheated,description,source,consumption,roll_width,roll_length,rapport)
                    select gen_random_uuid(),?,product_id,cod_good,name,category,kind,price,package_size,unit,sale_unit,wet,high_load,unheated,description,source,consumption,roll_width,roll_length,rapport
                    from repaircalc.product_revision where release_id=? and product_id<>?
                    """,release,readiness.baseReleaseId(),id);
                jdbc().update("""
                    insert into repaircalc.product_revision(id,release_id,product_id,cod_good,name,category,kind,price,package_size,unit,sale_unit,wet,high_load,unheated,description,source,consumption,roll_width,roll_length,rapport)
                    values (?,?,?,(select cod_good from repaircalc.product_revision where release_id=? and product_id=?),?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """,UUID.randomUUID(),release,id,readiness.baseReleaseId(),id,s.path("name").asText(),value(s,"category"),value(s,"kind"),decimal(s,"price"),decimal(s,"packageSize"),value(s,"unit"),value(s,"saleUnit"),bool(s,"wet"),bool(s,"highLoad"),bool(s,"unheated"),
                        s.path("description").asText("")+"\n"+s.path("application").asText("")+"\n"+s.path("limitations").asText(""),"admin-reviewed:"+readiness.reviewId(),decimal(s,"consumption"),decimal(s,"rollWidth"),decimal(s,"rollLength"),decimal(s,"rapport"));
                jdbc().update("insert into repaircalc.source_record(id,release_id,product_id,source_namespace,external_id,raw) select gen_random_uuid(),?,product_id,source_namespace,external_id,raw from repaircalc.source_record where release_id=? and not (source_namespace=? and external_id=?)",release,readiness.baseReleaseId(),p.sourceNamespace(),p.externalId());
                jdbc().update("insert into repaircalc.source_record(id,release_id,product_id,source_namespace,external_id,raw) values (?,?,?,?,?,?::jsonb)",UUID.randomUUID(),release,id,p.sourceNamespace(),p.externalId(),s.toString());
                jdbc().update("insert into repaircalc.product_work(release_id,product_id,taxonomy_version,work_code,mapping_version,evidence,source) select ?,product_id,taxonomy_version,work_code,mapping_version,evidence,source from repaircalc.product_work where release_id=? and product_id<>?",release,readiness.baseReleaseId(),id);
                for(var work:r.path("java_works")) jdbc().update("insert into repaircalc.product_work(release_id,product_id,taxonomy_version,work_code,mapping_version,evidence,source) values (?,?,?,?,?,?,?)",
                        release,id,"work-taxonomy-v1",work.asText(),AdminReviewService.MAPPING,r.path("decisions").toString(),"admin-reviewed:"+readiness.reviewId());
                var bundle=(ObjectNode)base.path("bundle").deepCopy(); bundle.put("version",ruleVersion);
                var reference=(ObjectNode)bundle.path("catalog"); reference.put("version",version).put("fingerprint",fingerprint);
                var assignments=bundle.putArray("assignments");
                for(var a:base.path("bundle").path("assignments")) if(!a.path("productId").asText().equals(id.toString())) assignments.add(a.deepCopy());
                assignments.addObject().put("productId",id.toString()).put("profileId",r.path("profile_id").asText()).putNull("blockedReason");
                var typed=json.readValue(bundle.toString(),RuleSetBundle.class);
                validator.validateShape(typed);
                var facts=jdbc().query("select product_id,kind,unit,package_size,consumption,roll_width,roll_length,rapport from repaircalc.product_revision where release_id=?",(rs,i)->new RuleSetValidator.ProductFacts(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getBigDecimal(4),rs.getBigDecimal(5),rs.getBigDecimal(6),rs.getBigDecimal(7),rs.getBigDecimal(8)),release);
                validator.validateProducts(typed,facts);
                // Rule guard requires a published catalog. This state remains private until commit.
                jdbc().update("update repaircalc.catalog_release set status='PUBLISHED',published_at=now() where id=?",release);
                var ruleManifest=json.createObjectNode().put("schemaVersion",1).put("dataClass","test").put("taxonomyVersion","work-taxonomy-v1").put("catalogFingerprint",fingerprint).put("baseRuleSetId",base.path("rule_id").asText());
                jdbc().update("insert into repaircalc.rule_set(id,version,fingerprint,catalog_release_id,taxonomy_version,bundle,manifest) values (?,?,?,?,?,?::jsonb,?::jsonb)",rules,ruleVersion,sha(bundle.toString()),release,"work-taxonomy-v1",bundle.toString(),ruleManifest.toString());
                jdbc().update("update repaircalc.rule_set set status='PUBLISHED',published_at=now(),active=true where id=?",rules);
                jdbc().update("update repaircalc.catalog_release set active=false where active");
                jdbc().update("update repaircalc.catalog_release set active=true where id=?",release);
                UUID publication=UUID.randomUUID();
                jdbc().update("insert into repaircalc.admin_publication(id,product_id,version_id,review_id,release_id,rule_set_id,created_by) values (?,?,?,?,?,?,?)",publication,id,p.versionId(),readiness.reviewId(),release,rules,actor);
                jdbc().update("update repaircalc.admin_product set status='published',updated_at=now(),updated_by=? where id=?",actor,id);
                jdbc().update("insert into repaircalc.admin_audit_event(id,request_id,actor,action,product_id,details) values (?,?,?,'publish',?,jsonb_build_object('publicationId',?::text))",UUID.randomUUID(),requestId,actor,id,publication.toString());
                return json.readTree(jdbc().queryForObject("select to_jsonb(x)::text from repaircalc.admin_publication x where id=?",String.class,publication));
            });
        } catch(DataAccessException e) { throw new AdminException(503,"publication_failed","Публикация отменена; предыдущий каталог сохранён."); }
    }
    private JsonNode base() {
        var rows=jdbc().query("""
            select jsonb_build_object('id',c.id,'fingerprint',c.fingerprint,'manifest',c.manifest,'bundle',r.bundle,'rule_id',r.id,'rule_fingerprint',r.fingerprint)::text
            from repaircalc.catalog_release c join repaircalc.rule_set r on r.catalog_release_id=c.id
            where c.active and c.status='PUBLISHED' and r.active and r.status='PUBLISHED'
              and c.manifest->>'taxonomyVersion'='work-taxonomy-v1' and c.manifest->>'dataClass'='test'
              and r.bundle->'catalog'->>'fingerprint'=c.fingerprint and r.bundle->'catalog'->>'version'=c.version
              and r.taxonomy_version='work-taxonomy-v1'
            """,(r,i)->json.readTree(r.getString(1)));
        return rows.size()==1?rows.getFirst():null;
    }
    private RuleSetValidator.ProductFacts facts(AdminProductService.Product p) {var s=p.snapshot();return new RuleSetValidator.ProductFacts(p.id(),value(s,"kind"),value(s,"unit"),decimal(s,"packageSize"),decimal(s,"consumption"),decimal(s,"rollWidth"),decimal(s,"rollLength"),decimal(s,"rapport"));}
    private static boolean confirmed(JsonNode s,String key){return s.path(key).path("state").asText().equals("confirmed");}
    private static String value(JsonNode s,String key){return confirmed(s,key)?s.path(key).path("value").asText():null;}
    private static BigDecimal decimal(JsonNode s,String key){return confirmed(s,key)?new BigDecimal(s.path(key).path("value").asText()):null;}
    private static Boolean bool(JsonNode s,String key){return confirmed(s,key)?s.path(key).path("value").asBoolean():null;}
    private static String sha(String input){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private JdbcTemplate jdbc(){var j=provider.getIfAvailable();if(j==null)throw new AdminException(503,"admin_unavailable","Хранилище недоступно.");return j;}
    public record Readiness(boolean ready,List<String> blockers,UUID reviewId,UUID baseReleaseId){}
}
