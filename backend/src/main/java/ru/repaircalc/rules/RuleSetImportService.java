package ru.repaircalc.rules;
import java.nio.file.*;
import java.util.*;
import org.bson.Document;
import org.springframework.stereotype.Service;
import org.springframework.data.mongodb.core.query.Update;
import ru.repaircalc.storage.*;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.cfg.*;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.type.LogicalType;
import static ru.repaircalc.storage.MongoStore.*;
@Service
public class RuleSetImportService {
    public static final String IMPORTER_VERSION="prepared-rules-v1";
    private final MongoStore store;
    private final JsonMapper json;
    private final RuleSetValidator validator;
    public RuleSetImportService(MongoStore store,JsonMapper json,RuleSetValidator validator){this.store=store;this.json=strictMapper(json);this.validator=validator;}
    public static JsonMapper strictMapper(JsonMapper mapper) {
        return mapper.rebuild().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
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
        try {
            byte[] bytes=Files.readAllBytes(file);var bundle=json.readValue(bytes,RuleSetBundle.class);validator.validateShape(bundle);
            return store.transaction(()->publish(bundle,sha(IMPORTER_VERSION+"\n"+sha(bytes))));
        } catch(java.io.IOException e) {throw new RuleSetImportException("Cannot read rules file.");}
    }
    private ImportResult publish(RuleSetBundle bundle,String fingerprint) {
        var prior=store.one("rule_set",query("version",bundle.version()));
        if(prior!=null) {
            if(!fingerprint.equals(prior.getString("fingerprint")))throw new RuleSetImportException("Rule version exists with different content.");
            return new ImportResult(UUID.fromString(prior.getString("_id")),UUID.fromString(prior.getString("catalogReleaseId")),bundle.version(),fingerprint,true);
        }
        var catalog=store.one("catalog_release",query("version",bundle.catalog().version()));
        if(catalog==null || !bundle.catalog().fingerprint().equals(catalog.getString("fingerprint")) || !bundle.catalog().taxonomyVersion().equals(catalog.get("manifest",Document.class).getString("taxonomyVersion")))throw new RuleSetImportException("Rules must reference an exact catalog fingerprint and taxonomy.");
        String catalogId=catalog.getString("_id"),id=UUID.randomUUID().toString();
        var products=store.find("product_revision",query("releaseId",catalogId)).stream().map(d->ru.repaircalc.catalog.CatalogReadService.view(store,d)).toList();
        validator.validateProducts(bundle,products.stream().map(RuleSetImportService::facts).toList());
        store.insert("rule_set",new Document("_id",id).append("catalogReleaseId",catalogId).append("version",bundle.version()).append("fingerprint",fingerprint).append("bundle",store.document(bundle)));
        // Seed re-runs return above; they never revert a later admin publication.
        store.mongo.upsert(query("_id","active"),new Update().set("catalogReleaseId",catalogId).set("ruleSetId",id).inc("generation",1),"publication_pointer");
        return new ImportResult(UUID.fromString(id),UUID.fromString(catalogId),bundle.version(),fingerprint,false);
    }
    public static RuleSetValidator.ProductFacts facts(ru.repaircalc.catalog.ProductView p){return new RuleSetValidator.ProductFacts(p.id(),p.kind(),p.unit(),p.packageSize(),p.consumption(),p.rollWidth(),p.rollLength(),p.rapport());}
    public record ImportResult(UUID ruleSetId,UUID catalogReleaseId,String version,String fingerprint,boolean noOp){}
}
