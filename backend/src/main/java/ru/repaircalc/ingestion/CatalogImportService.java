package ru.repaircalc.ingestion;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import jakarta.validation.Validator;
import org.bson.Document;
import org.springframework.stereotype.Service;
import ru.repaircalc.storage.*;
import tools.jackson.databind.json.JsonMapper;
import static ru.repaircalc.storage.MongoStore.*;
@Service
public class CatalogImportService {
    public static final String IMPORTER_VERSION = "prepared-catalog-v1";
    private static final Set<String> KINDS = Set.of("covering", "paint", "primer", "wallpaper", "adhesive", "socket", "cable");
    private final MongoStore store;
    private final MongoSchema schema;
    private final JsonMapper json;
    private final Validator validator;
    public CatalogImportService(MongoStore store, MongoSchema schema, JsonMapper json, Validator validator) {
        this.store=store; this.schema=schema; this.json=ru.repaircalc.rules.RuleSetImportService.strictMapper(json); this.validator=validator;
    }
    public ImportResult importFiles(Path catalogFile, Path mappingsFile) {
        try {
            byte[] bytes=Files.readAllBytes(catalogFile), mapBytes=Files.readAllBytes(mappingsFile);
            var catalog=json.readValue(bytes,PreparedCatalog.Catalog.class);
            var mappings=json.readValue(mapBytes,PreparedCatalog.Mappings.class);
            validate(catalog,mappings);
            String fingerprint=sha(IMPORTER_VERSION+"\n"+sha(bytes)+"\n"+sha(mapBytes));
            schema.initialize();
            return store.transaction(()->publish(catalog,mappings,fingerprint,sha(mapBytes)));
        } catch(java.io.IOException e) {throw new CatalogImportException("Cannot read prepared catalog files.");}
    }
    private ImportResult publish(PreparedCatalog.Catalog catalog, PreparedCatalog.Mappings mappings,String fingerprint,String mappingHash) {
        var prior=store.one("catalog_release",query("version",catalog.version()));
        if(prior!=null) {
            if(!fingerprint.equals(prior.getString("fingerprint")))throw new CatalogImportException("Version exists with different content.");
            return new ImportResult(UUID.fromString(prior.getString("_id")),catalog.version(),fingerprint,catalog.products().size(),true);
        }
        if(!"work-taxonomy-v1".equals(mappings.taxonomyVersion()))throw new CatalogImportException("Unknown taxonomy.");
        for(var old:store.find("catalog_release",query("manifest.mappingVersion",mappings.version())))
            if(!mappingHash.equals(old.get("manifest",Document.class).getString("mappingsSha256")))throw new CatalogImportException("Mapping version already exists with different content.");
        var map=new HashMap<UUID,List<String>>();
        for(var mapping:mappings.mappings()) {
            if(!Set.of("floor","paint","wallpaper","electrical").containsAll(mapping.works()))throw new CatalogImportException("Unknown work code.");
            map.put(mapping.productId(),mapping.works());
        }
        String release=UUID.randomUUID().toString();
        for(var product:catalog.products()) {
            for(var source:product.sources()) {
                var q=query("namespace",source.namespace()).addCriteria(org.springframework.data.mongodb.core.query.Criteria.where("externalId").is(source.externalId()));
                var identity=store.one("source_identity",q);
                if(identity!=null && !identity.getString("productId").equals(product.id().toString()))throw new CatalogImportException("Source identity belongs to another product.");
                if(identity==null)store.insert("source_identity",new Document("_id",UUID.randomUUID().toString()).append("namespace",source.namespace()).append("externalId",source.externalId()).append("productId",product.id().toString()));
                store.insert("source_record",new Document("_id",UUID.randomUUID().toString()).append("releaseId",release).append("productId",product.id().toString()).append("namespace",source.namespace()).append("externalId",source.externalId()).append("raw",store.document(source.raw())));
            }
            var doc=store.document(product);doc.remove("sources");doc.put("_id",UUID.randomUUID().toString());doc.put("releaseId",release);doc.put("works",map.getOrDefault(product.id(),List.of()));
            doc.put("price",decimal(product.price()));store.insert("product_revision",doc);
        }
        store.insert("catalog_release",new Document("_id",release).append("version",catalog.version()).append("fingerprint",fingerprint)
            .append("manifest",new Document("taxonomyVersion",mappings.taxonomyVersion()).append("dataClass",catalog.dataClass()).append("mappingVersion",mappings.version()).append("mappingsSha256",mappingHash)));
        // Import stages an immutable catalog. Only importing its matching rules activates the pair.
        return new ImportResult(UUID.fromString(release),catalog.version(),fingerprint,catalog.products().size(),false);
    }
    private void validate(PreparedCatalog.Catalog catalog, PreparedCatalog.Mappings mappings) {
        if (catalog == null || mappings == null) {
            throw new CatalogImportException("Catalog and mappings must be JSON objects.");
        }
        var errors = new java.util.TreeSet<String>();
        validator.validate(catalog).forEach(error -> errors.add("catalog." + error.getPropertyPath()));
        validator.validate(mappings).forEach(error -> errors.add("mappings." + error.getPropertyPath()));
        if (!errors.isEmpty()) {
            throw new CatalogImportException("Invalid prepared fields: " + String.join(", ", errors));
        }
        if (catalog.schemaVersion() != 1 || mappings.schemaVersion() != 1 || !"test".equals(catalog.dataClass())) {
            throw new CatalogImportException("Only version-one prepared test catalogs are supported.");
        }
        Set<UUID> products = new HashSet<>();
        Set<SourceIdentity> sources = new HashSet<>();
        for (var product : catalog.products()) {
            if (!products.add(product.id()) || !KINDS.contains(product.kind())) {
                throw new CatalogImportException("Duplicate product UUID or unknown product kind.");
            }
            if (!product.source().contains("ТЕСТОВЫЕ")) {
                throw new CatalogImportException("Test catalog product.source must explicitly identify test data (ТЕСТОВЫЕ).");
            }
            if ("wallpaper".equals(product.kind()) && product.packageSize() != null) {
                throw new CatalogImportException("Wallpaper packageSize must be null; store roll geometry separately.");
            }
            for (var source : product.sources()) {
                if (source.raw().isNull() || !sources.add(new SourceIdentity(source.namespace(), source.externalId()))) {
                    throw new CatalogImportException("Missing raw source data or duplicate namespaced source identity.");
                }
            }
        }
        Set<UUID> mapped = new HashSet<>();
        for (var mapping : mappings.mappings()) {
            if (!products.contains(mapping.productId()) || !mapped.add(mapping.productId())
                    || new HashSet<>(mapping.works()).size() != mapping.works().size()) {
                throw new CatalogImportException("Mappings contain an unknown product, duplicate product, or duplicate work code.");
            }
        }
    }

    private record SourceIdentity(String namespace, String externalId) { }
    public record ImportResult(UUID releaseId, String version, String fingerprint, int productCount, boolean noOp) { }
}
