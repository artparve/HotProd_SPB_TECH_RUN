package ru.repaircalc.materials;

import java.time.Clock;
import java.time.LocalDate;
import java.util.*;
import org.bson.Document;
import org.springframework.stereotype.Service;
import ru.repaircalc.calculation.CalculationSnapshotService;
import ru.repaircalc.common.web.ApiStatusException;
import ru.repaircalc.storage.MongoStore;

/** Immutable releases, atomic pointer replacement, and retained releases for rollback. */
@Service
public class KnowledgeService {
  private final MongoStore store;
  private final CalculationSnapshotService snapshots;
  private final Clock clock;
  private final jakarta.validation.Validator validator;

  public KnowledgeService(MongoStore store, CalculationSnapshotService snapshots, Clock clock, jakarta.validation.Validator validator) {
    this.store = store;
    this.snapshots = snapshots;
    this.clock = clock;
    this.validator = validator;
  }

  public Release publish(KnowledgeBundle bundle, String actor) {
    var snapshot = snapshots.resolve();
    validate(bundle, snapshot);
    String checksum = MongoStore.sha(store.document(bundle).toJson());
    String id = UUID.randomUUID().toString();
    var release = new Release(id, bundle, checksum, "bm25-v1", clock.instant().toString(), actor,
        new Manifest("none", 0, "unicode-lower-yo-v1", "operator-v1", bundle.chunks().size(),
            (int) bundle.chunks().stream().map(KnowledgeBundle.Chunk::productId).distinct().count()));
    // One document contains the complete corpus and manifest. No partially built index is visible.
    store.insert("knowledge_release", store.document(release).append("_id", id));
    activate(id);
    return release;
  }

  public Release activate(String id) {
    var document = store.id("knowledge_release", id);
    if (document == null) throw new ApiStatusException(404, "knowledge_not_found", "Knowledge release not found.");
    var release = store.read(document, Release.class);
    if (store.id("knowledge_revocation", id) != null) throw invalid();
    validate(release.bundle(), snapshots.resolve());
    store.mongo.getCollection("knowledge_pointer").replaceOne(
        new Document("_id", "active"), new Document("_id", "active").append("releaseId", id),
        new com.mongodb.client.model.ReplaceOptions().upsert(true));
    return release;
  }

  public Release active(CalculationSnapshotService.Snapshot snapshot) {
    try { return readActive(snapshot); }
    catch (tools.jackson.core.JacksonException | IllegalArgumentException | NullPointerException invalidIndex) { return null; }
  }

  private Release readActive(CalculationSnapshotService.Snapshot snapshot) {
    var pointer = store.id("knowledge_pointer", "active");
    if (pointer == null) return null;
    var document = store.id("knowledge_release", pointer.getString("releaseId"));
    if (document == null) return null;
    if (store.id("knowledge_revocation", pointer.getString("releaseId")) != null) return null;
    var release = store.read(document, Release.class);
    // Mismatched catalog, expired evidence or unsupported index versions never confirm a decision.
    try {
      validate(release.bundle(), snapshot);
      if (!"bm25-v1".equals(release.retrievalVersion()) || release.manifest() == null
          || !new Manifest("none", 0, "unicode-lower-yo-v1", "operator-v1", release.bundle().chunks().size(),
              (int) release.bundle().chunks().stream().map(KnowledgeBundle.Chunk::productId).distinct().count()).equals(release.manifest())) return null;
      if (!MongoStore.sha(store.document(release.bundle()).toJson()).equals(release.checksum())) return null;
      return release;
    } catch (ApiStatusException e) {
      return null;
    }
  }

  void validate(KnowledgeBundle bundle, CalculationSnapshotService.Snapshot snapshot) {
    if (bundle == null || !validator.validate(bundle).isEmpty()) throw invalid();
    if (!bundle.catalogReleaseId().equals(snapshot.catalog().releaseId())
        || !bundle.ruleSetId().equals(snapshot.rules().id()))
      throw new ApiStatusException(409, "stale_recommendation", "Catalog or rules changed. Rebuild the knowledge release.");
    var products = new HashSet<UUID>();
    snapshot.catalog().products().forEach(p -> products.add(p.id()));
    var sources = new HashSet<String>();
    var today = LocalDate.now(clock);
    for (var source : bundle.sources()) {
      if (!sources.add(source.id()) || !"confirmed".equals(source.reviewStatus())
          || !"approved".equals(source.rightsStatus()) || source.retrievedOn().isAfter(today)
          || source.validUntil().isBefore(today) || source.validUntil().isBefore(source.retrievedOn())) throw invalid();
    }
    var chunks = new HashMap<String, KnowledgeBundle.Chunk>();
    for (var chunk : bundle.chunks()) {
      if (chunks.put(chunk.id(), chunk) != null || !products.contains(chunk.productId())
          || !sources.contains(chunk.sourceId())
          || !revision(bundle.catalogReleaseId(), chunk.productId()).equals(chunk.productVersionId())) throw invalid();
    }
    var ruleKeys = new HashSet<String>();
    for (var rule : bundle.requirements()) {
      if (!ruleKeys.add(rule.work() + ":" + rule.kind())
          || !rule.conditions().containsAll(List.of("humidity", "load", "heated"))
          || snapshot.rules().bundle().kits().stream().noneMatch(k -> k.work().equals(rule.work()) && k.kind().equals(rule.kind()))) throw invalid();
    }
    var pairKeys = new HashSet<String>();
    for (var pair : bundle.combinations()) {
      var key = java.util.stream.Stream.of(pair.first().toString(), pair.second().toString()).sorted().toList().toString();
      if (!products.contains(pair.first()) || !products.contains(pair.second()) || pair.first().equals(pair.second())
          || !pairKeys.add(key)) throw invalid();
      var linked = new HashSet<UUID>();
      for (var id : pair.chunkIds()) {
        var chunk = chunks.get(id);
        if (chunk == null || !(chunk.productId().equals(pair.first()) || chunk.productId().equals(pair.second()))) throw invalid();
        linked.add(chunk.productId());
      }
      if (!linked.containsAll(List.of(pair.first(), pair.second()))) throw invalid();
    }
    for (var claim : bundle.claims()) {
      if (!products.contains(claim.productId())) throw invalid();
      if (Set.of("wet", "highLoad", "unheated", "floorHeating").contains(claim.property())
          && !Set.of("true", "false").contains(claim.value())) throw invalid();
      if ("work".equals(claim.property())
          && !Set.of("floor", "paint", "wallpaper", "electrical").contains(claim.value())) throw invalid();
      for (var id : claim.chunkIds()) {
        var chunk = chunks.get(id);
        if (chunk == null || !chunk.productId().equals(claim.productId())) throw invalid();
      }
    }
  }

  public void revoke(String id, String actor) {
    if (store.id("knowledge_release", id) == null)
      throw new ApiStatusException(404, "knowledge_not_found", "Knowledge release not found.");
    store.mongo.getCollection("knowledge_revocation").replaceOne(new Document("_id", id),
        new Document("_id", id).append("actor", actor).append("revokedAt", clock.instant().toString()),
        new com.mongodb.client.model.ReplaceOptions().upsert(true));
  }

  public static String revision(UUID catalog, UUID product) { return catalog + ":" + product; }
  private static ApiStatusException invalid() {
    return new ApiStatusException(422, "invalid_knowledge", "Check source review, validity, product revision and evidence links.");
  }
  public record Manifest(String embeddingModel, int dimension, String normalizationVersion,
                         String chunkingVersion, int documentCount, int productCount) {}
  public record Release(String id, KnowledgeBundle bundle, String checksum, String retrievalVersion,
                        String publishedAt, String reviewedBy, Manifest manifest) {
    public Release(String id, KnowledgeBundle bundle, String checksum, String retrievalVersion, String publishedAt, String reviewedBy) {
      this(id, bundle, checksum, retrievalVersion, publishedAt, reviewedBy, null);
    }
  }
}
