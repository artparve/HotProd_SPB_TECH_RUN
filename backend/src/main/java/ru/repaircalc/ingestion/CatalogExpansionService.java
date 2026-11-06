package ru.repaircalc.ingestion;

import static ru.repaircalc.storage.MongoStore.*;

import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import org.bson.Document;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.stereotype.Service;
import ru.repaircalc.catalog.CatalogReadService;
import ru.repaircalc.rules.*;
import ru.repaircalc.storage.MongoStore;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Add the curated room variants to an existing publication; never replace operator-owned data. */
@Service
public class CatalogExpansionService {
  private final MongoStore store;
  private final CatalogImportService catalogs;
  private final RuleSetValidator validator;
  private final JsonMapper json;

  public CatalogExpansionService(MongoStore store, CatalogImportService catalogs,
      RuleSetValidator validator, JsonMapper json) {
    this.store = store;
    this.catalogs = catalogs;
    this.validator = validator;
    this.json = RuleSetImportService.strictMapper(json);
  }

  public int expand(Path catalogFile, Path mappingsFile, Path rulesFile) throws java.io.IOException {
    var rules = json.readValue(Files.readAllBytes(rulesFile), RuleSetBundle.class);
    validator.validateShape(rules);
    // Importing a catalog only stages an immutable release, it does not change the active pair.
    var staged = catalogs.importFiles(catalogFile, mappingsFile);
    if (!staged.fingerprint().equals(rules.catalog().fingerprint())
        || !staged.version().equals(rules.catalog().version()))
      throw new CatalogImportException("Expansion rules do not match the staged catalog.");
    var candidateIds = store.find("source_record", query("releaseId", staged.releaseId().toString())
        .addCriteria(Criteria.where("namespace").is("repaircalc.synthetic-rooms")))
        .stream().map(d -> d.getString("productId")).collect(Collectors.toSet());
    if (candidateIds.size() != 100)
      throw new CatalogImportException("Expected exactly 100 curated room variants.");
    return store.transaction(() -> merge(staged, rules, candidateIds));
  }

  private int merge(CatalogImportService.ImportResult staged, RuleSetBundle incoming, Set<String> candidates) {
    var pointer = store.id("publication_pointer", "active");
    if (pointer == null) throw new CatalogImportException("Initialize a catalog/rule publication before expansion.");
    String oldRelease = pointer.getString("catalogReleaseId"), oldRules = pointer.getString("ruleSetId");
    var catalog = store.id("catalog_release", oldRelease);
    var currentRules = store.id("rule_set", oldRules);
    if (catalog == null || currentRules == null || !oldRelease.equals(currentRules.getString("catalogReleaseId")))
      throw new CatalogImportException("Active publication is inconsistent.");
    var base = (ObjectNode) store.tree(currentRules.get("bundle", Document.class));
    if (!catalog.getString("fingerprint").equals(base.path("catalog").path("fingerprint").asText())
        || !incoming.catalog().taxonomyVersion().equals(base.path("catalog").path("taxonomyVersion").asText()))
      throw new CatalogImportException("Expansion cannot change the active taxonomy or repair an invalid pair.");
    var existing = store.find("product_revision", query("releaseId", oldRelease));
    var missing = new HashSet<>(candidates);
    existing.forEach(d -> missing.remove(d.getString("id")));
    if (missing.isEmpty()) return 0;

    String release = UUID.randomUUID().toString(), ruleId = UUID.randomUUID().toString();
    String version = "rooms-extension-" + release;
    String fingerprint = sha(catalog.getString("fingerprint") + "\n" + currentRules.getString("fingerprint")
        + "\n" + staged.fingerprint() + "\n" + String.join(",", new TreeSet<>(missing)));
    var manifest = new Document(catalog.get("manifest", Document.class));
    manifest.put("baseReleaseId", oldRelease);
    manifest.put("roomsExpansionFingerprint", staged.fingerprint());
    store.insert("catalog_release", new Document("_id", release).append("version", version)
        .append("fingerprint", fingerprint).append("manifest", manifest));
    existing.forEach(d -> copy("product_revision", d, release));
    var added = store.find("product_revision", query("releaseId", staged.releaseId().toString()))
        .stream().filter(d -> missing.contains(d.getString("id"))).toList();
    if (added.size() != missing.size()) throw new CatalogImportException("Expansion products are missing.");
    added.forEach(d -> copy("product_revision", d, release));
    store.find("source_record", query("releaseId", oldRelease)).forEach(d -> copy("source_record", d, release));
    store.find("source_record", query("releaseId", staged.releaseId().toString())).stream()
        .filter(d -> missing.contains(d.getString("productId"))).forEach(d -> copy("source_record", d, release));

    base.put("version", "rooms-rules-" + ruleId);
    ((ObjectNode) base.path("catalog")).put("version", version).put("fingerprint", fingerprint);
    // Namespace new profiles/sources; never overwrite a same-named operator profile.
    String prefix = "rooms-" + staged.fingerprint() + "-";
    Set<String> sourceIds = new HashSet<>(), profileIds = new HashSet<>();
    base.path("sources").forEach(n -> sourceIds.add(n.path("id").asText()));
    base.path("profiles").forEach(n -> profileIds.add(n.path("id").asText()));
    for (var source : incoming.sources()) {
      String id = prefix + source.id();
      var node = (ObjectNode) json.valueToTree(source);
      node.put("id", id);
      if (sourceIds.add(id)) base.withArray("sources").add(node);
      else if (!base.path("sources").valueStream().anyMatch(node::equals))
        throw new CatalogImportException("Expansion source ID conflict.");
    }
    for (var profile : incoming.profiles()) {
      String id = prefix + profile.id();
      var node = (ObjectNode) json.valueToTree(profile);
      node.put("id", id).put("sourceId", prefix + profile.sourceId());
      if (profileIds.add(id)) base.withArray("profiles").add(node);
      else if (!base.path("profiles").valueStream().anyMatch(node::equals))
        throw new CatalogImportException("Expansion profile ID conflict.");
    }
    for (var assignment : incoming.assignments()) {
      if (!missing.contains(assignment.productId().toString())) continue;
      var node = (ObjectNode) json.valueToTree(assignment);
      if (assignment.profileId() != null) node.put("profileId", prefix + assignment.profileId());
      base.withArray("assignments").add(node);
    }
    var merged = json.treeToValue(base, RuleSetBundle.class);
    validator.validateShape(merged);
    validator.validateProducts(merged, store.find("product_revision", query("releaseId", release)).stream()
        .map(d -> RuleSetImportService.facts(CatalogReadService.view(store, d))).toList());
    store.insert("rule_set", new Document("_id", ruleId).append("catalogReleaseId", release)
        .append("version", merged.version()).append("fingerprint", sha(base.toString())).append("bundle", store.document(base)));
    if (store.update("publication_pointer", query("_id", "active")
        .addCriteria(Criteria.where("catalogReleaseId").is(oldRelease))
        .addCriteria(Criteria.where("ruleSetId").is(oldRules)),
        new Update().set("catalogReleaseId", release).set("ruleSetId", ruleId).inc("generation", 1)) != 1)
      throw new CatalogImportException("Active publication changed; retry expansion.");
    return missing.size();
  }

  private void copy(String collection, Document original, String release) {
    var doc = new Document(original);
    doc.put("_id", UUID.randomUUID().toString());
    doc.put("releaseId", release);
    store.insert(collection, doc);
  }
}
