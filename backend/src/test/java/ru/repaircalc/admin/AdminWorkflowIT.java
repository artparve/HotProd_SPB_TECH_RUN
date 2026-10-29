package ru.repaircalc.admin;

import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.postgresql.PostgreSQLContainer;
import ru.repaircalc.ingestion.CatalogImportService;
import ru.repaircalc.rules.RuleSetImportService;
import ru.repaircalc.calculation.CalculationSnapshotService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles({"test","postgres-test"})
@Import(AdminWorkflowIT.PostgresConfiguration.class)
class AdminWorkflowIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;
    @Autowired AdminProductService products;
    @Autowired AdminClassificationService jobs;
    @Autowired AdminReviewService reviews;
    @Autowired AdminPublicationService publications;
    @Autowired CatalogImportService catalogs;
    @Autowired RuleSetImportService rules;
    @Autowired CalculationSnapshotService snapshots;

    @Test void versionedQueueReviewAtomicPublicationAndRollback() throws Exception {
        var original=catalogs.importFiles(Path.of("data/catalog-v1.json"),Path.of("data/work-mappings-v1.json"));
        rules.importFile(Path.of("data/rules-v1.json"));
        var before=snapshots.resolve();
        var d=draft();
        var p=products.create(d,"test-admin","create");
        var job=jobs.enqueue(p.id(),AdminProductService.etag(p),"test-admin","classify");
        assertThat(jobs.enqueue(p.id(),AdminProductService.etag(p),"test-admin","repeat").id()).isEqualTo(job.id());
        assertThat(jobs.list(p.id()).getFirst().input().path("description").asText()).contains("стен");
        assertThatThrownBy(()->jobs.enqueue(p.id(),"\"old\"","test-admin","stale")).isInstanceOf(AdminException.class).hasMessageContaining("Перечитайте");
        var model=json.readTree("""
          {"taxonomy_version":"repair-product-taxonomy-v2","classification":{
            "scope":"in_scope","work_types":[{"label":"paint","evidence":[{"field":"description","quote":"стен"}]}],
            "surface_locations":[{"label":"wall","evidence":[{"field":"description","quote":"стен"}]}],"substrates":[],
            "properties":{"wet_room_suitable":{"state":"unknown","evidence":[]}},"needs_review":false,"review_reasons":[]}}
          """);
        succeed(job,model);
        var body=json.createObjectNode().put("jobId",job.id().toString()).put("profileId","paint-012-v1");
        body.set("decisions",json.readTree("""
          [{"group":"work_types","label":"paint","action":"accept","reason":"Проверено применение"},
           {"group":"surface_locations","label":"wall","action":"accept","reason":"Указаны стены"},
           {"group":"properties","label":"wet_room_suitable","action":"reject","reason":"Не заявлено"}]
          """));
        var incomplete=body.deepCopy(); ((tools.jackson.databind.node.ArrayNode)incomplete.path("decisions")).remove(2);
        assertThatThrownBy(()->reviews.review(p.id(),AdminProductService.etag(p),incomplete,"test-admin","invalid")).isInstanceOf(AdminException.class);
        reviews.review(p.id(),AdminProductService.etag(p),body,"test-admin","review");
        assertThat(publications.readiness(p.id()).blockers()).anyMatch(x->x.contains("норму"));
        assertThatThrownBy(()->publications.publish(p.id(),AdminProductService.etag(p),"test-admin","blocked")).isInstanceOf(AdminException.class);
        // Saving confirmed facts invalidates the previous classification/review.
        d.set("consumption",confirmed(json.getNodeFactory().numberNode(new java.math.BigDecimal("0.12"))));
        d.set("consumptionUnit",confirmed(json.getNodeFactory().stringNode("л")));
        var current=products.update(p.id(),AdminProductService.etag(p),d,"test-admin","update");
        assertThatThrownBy(()->reviews.review(current.id(),AdminProductService.etag(current),body,"test-admin","stale")).isInstanceOf(AdminException.class).hasMessageContaining("текущей версии");
        var next=jobs.enqueue(current.id(),AdminProductService.etag(current),"test-admin","classify2");
        succeed(next,model); body.put("jobId",next.id().toString());
        reviews.review(current.id(),AdminProductService.etag(current),body,"test-admin","review2");
        assertThat(publications.readiness(current.id()).ready()).isTrue();
        // Failure at the end must restore the old active pair and discard all cloned rows.
        jdbc.execute("create function repaircalc.fail_workflow_audit() returns trigger language plpgsql as $$ begin if NEW.action='publish' then raise exception 'test failure'; end if; return NEW; end; $$");
        jdbc.execute("create trigger fail_workflow_audit before insert on repaircalc.admin_audit_event for each row execute function repaircalc.fail_workflow_audit()");
        try { assertThatThrownBy(()->publications.publish(current.id(),AdminProductService.etag(current),"test-admin","rollback")).isInstanceOf(AdminException.class); }
        finally { jdbc.execute("drop trigger fail_workflow_audit on repaircalc.admin_audit_event");jdbc.execute("drop function repaircalc.fail_workflow_audit()"); }
        assertThat(snapshots.resolve().catalog().releaseId()).isEqualTo(original.releaseId());
        assertThat(jdbc.queryForObject("select count(*) from repaircalc.catalog_release",Integer.class)).isEqualTo(1);
        var published=publications.publish(current.id(),AdminProductService.etag(current),"test-admin","publish");
        assertThat(publications.publish(current.id(),AdminProductService.etag(current),"test-admin","retry")).isEqualTo(published);
        var after=snapshots.resolve();
        assertThat(after.catalog().products()).hasSize(13);
        assertThat(after.catalog().products()).containsAll(before.catalog().products());
        assertThat(after.rules().bundle().assignments()).containsAll(before.rules().bundle().assignments());
        assertThat(after.catalog().products().stream().filter(x->x.id().equals(current.id())).findFirst().orElseThrow().wet()).isNull();
        assertThat(after.catalog().products().stream().filter(x->x.id().equals(current.id())).findFirst().orElseThrow().works()).containsExactly("paint");
        assertThat(after.rules().bundle().catalog().fingerprint()).isEqualTo(after.catalog().fingerprint());
        assertThat(jdbc.queryForObject("select count(*) from repaircalc.product_revision where release_id=?",Integer.class,original.releaseId())).isEqualTo(12);
        assertThatThrownBy(()->jdbc.update("update repaircalc.admin_classification_job set result='{}' where id=?",job.id())).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(products.get(current.id()).status()).isEqualTo("published");
        // Updating an existing public identity replaces exactly one revision and retains every other product.
        var edited=products.update(current.id(),AdminProductService.etag(current),d.put("name","Updated paint"),"test-admin","edit");
        var last=jobs.enqueue(edited.id(),AdminProductService.etag(edited),"test-admin","classify3");
        succeed(last,model); body.put("jobId",last.id().toString()); reviews.review(edited.id(),AdminProductService.etag(edited),body,"test-admin","review3");
        publications.publish(edited.id(),AdminProductService.etag(edited),"test-admin","publish3");
        assertThat(snapshots.resolve().catalog().products()).hasSize(13).containsAll(before.catalog().products());
        // Two independent publishers must clone the latest committed base, never lose each other's product.
        var one=products.create(draftWithNorm(),"test-admin","parallel-create1");
        var two=products.create(draftWithNorm(),"test-admin","parallel-create2");
        for(var card:List.of(one,two)) {
            var j=jobs.enqueue(card.id(),AdminProductService.etag(card),"test-admin","parallel-classify");
            succeed(j,model); var decision=body.deepCopy().put("jobId",j.id().toString());
            reviews.review(card.id(),AdminProductService.etag(card),decision,"test-admin","parallel-review");
        }
        var a=java.util.concurrent.CompletableFuture.supplyAsync(()->publications.publish(one.id(),AdminProductService.etag(one),"test-admin","parallel1"));
        var b=java.util.concurrent.CompletableFuture.supplyAsync(()->publications.publish(two.id(),AdminProductService.etag(two),"test-admin","parallel2"));
        a.get(20,java.util.concurrent.TimeUnit.SECONDS); b.get(20,java.util.concurrent.TimeUnit.SECONDS);
        var finalCatalog=snapshots.resolve().catalog();
        assertThat(finalCatalog.products()).hasSize(15).containsAll(before.catalog().products());
        assertThat(finalCatalog.products().stream().map(x->x.id())).contains(one.id(),two.id(),current.id());
    }
    private void succeed(AdminClassificationService.Job job,JsonNode result) {
        jdbc.update("update repaircalc.admin_classification_job set status='succeeded',result=?::jsonb,finished_at=now() where id=?",result.toString(),job.id());
    }
    private ObjectNode draftWithNorm(){
        var d=draft(); d.set("consumption",confirmed(json.getNodeFactory().numberNode(new java.math.BigDecimal("0.12"))));
        d.set("consumptionUnit",confirmed(json.getNodeFactory().stringNode("л"))); return d;
    }
    private ObjectNode draft(){
        var d=json.createObjectNode().put("sourceNamespace","workflow").put("externalId",UUID.randomUUID().toString()).put("name","Тестовая краска").put("description","Краска для стен");
        for(var entry:Map.of("category","Краски","kind","paint","price","1234.50","currency","RUB","priceDate","2026-10-30","unit","л","saleUnit","бан.").entrySet()) d.set(entry.getKey(),confirmed(json.getNodeFactory().stringNode(entry.getValue())));
        d.set("packageSize",confirmed(json.getNodeFactory().numberNode(2.5))); d.set("sources",json.readTree("[{\"label\":\"Тестовая этикетка\"}]"));return d;
    }
    private ObjectNode confirmed(JsonNode value){return json.createObjectNode().put("state","confirmed").put("source","Тестовая этикетка").set("value",value);}
    @TestConfiguration(proxyBeanMethods=false) static class PostgresConfiguration {
        @Bean @ServiceConnection PostgreSQLContainer postgres(){return new PostgreSQLContainer("postgres:17.11");}
    }
}
