package ru.repaircalc.calculation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.stereotype.Service;
import ru.repaircalc.catalog.ProductView;
import ru.repaircalc.materials.*;
import ru.repaircalc.rules.RuleSetBundle;

@Service
public class MaterialRecommendationService {
  private final CalculationSnapshotService snapshots;
  private final KnowledgeService knowledge;
  private final MaterialExplanationService explanations;
  private final MaterialSelectionService selections;
  private final io.micrometer.core.instrument.MeterRegistry metrics;
  private final ReferenceRagService references;

  public MaterialRecommendationService(CalculationSnapshotService snapshots, KnowledgeService knowledge,
      MaterialExplanationService explanations, MaterialSelectionService selections, io.micrometer.core.instrument.MeterRegistry metrics, ReferenceRagService references) {
    this.snapshots = snapshots;
    this.knowledge = knowledge;
    this.explanations = explanations;
    this.selections = selections;
    this.metrics = metrics;
    this.references = references;
  }

  public Result recommend(Request request) {
    if (request.room() != null) request.room().validate();
    if (new HashSet<>(request.works()).size() != request.works().size())
      throw new ru.repaircalc.common.web.ApiStatusException(422, "validation_failed", "Duplicate works.");
    var snapshot = snapshots.resolve();
    long verificationNanos = 0, retrievalNanos = 0;
    KnowledgeService.Release release = null;
    var warnings = new ArrayList<String>();
    warnings.add(ProjectCalculationService.EDUCATIONAL);
    try { release = knowledge.active(snapshot); }
    catch (org.springframework.dao.DataAccessException | com.mongodb.MongoException e) {
      warnings.add("База знаний временно недоступна; подтверждение совместимости отключено.");
    }
    if (release == null) warnings.add("Нет актуальной опубликованной базы знаний. Показаны кандидаты для проверки.");
    var questions = questions(request);
    if (release != null) {
      for (var rule : release.bundle().requirements()) {
        if (!request.works().contains(rule.work())) continue;
        for (String field : rule.conditions())
          if (condition(request.conditions(), field) == null && questions.stream().noneMatch(q -> q.field().equals(field)))
            questions.add(new Question(field, "Условие требуется опубликованным правилом для " + rule.kind(), List.of()));
      }
    }
    var assignments = new HashMap<UUID, RuleSetBundle.Assignment>();
    snapshot.rules().bundle().assignments().forEach(a -> assignments.put(a.productId(), a));
    var categories = new ArrayList<Category>();
    var seen = new HashSet<String>();
    for (var kit : snapshot.rules().bundle().kits()) {
      if (!request.works().contains(kit.work())) continue;
      String key = kit.work() + ":" + kit.kind() + ":" + kit.coveringFamily();
      if (!seen.add(key)) continue;
      var evaluated = new ArrayList<Candidate>();
      for (var p : snapshot.catalog().products()) {
        if (!p.kind().equals(kit.kind()) || !p.works().contains(kit.work())) continue;
        long verifyStart = System.nanoTime();
        var candidate = evaluate(p, kit.work(), request, snapshot, release, assignments.get(p.id()));
        verificationNanos += System.nanoTime() - verifyStart;
        if (!"fail".equals(candidate.compatibility())) evaluated.add(candidate);
      }
      // Confirmed and unknown candidates are ranked independently. Unknown cannot displace a pass.
      var documents = new HashMap<UUID, String>();
      for (var candidate : evaluated) {
        String facts = candidate.sources().stream().map(Evidence::text).reduce("", (a, b) -> a + " " + b);
        documents.put(candidate.productId(), candidate.name() + " " + facts);
      }
      long retrievalStart = System.nanoTime();
      var ranking = LexicalRetrieval.rank(documents, request.query());
      retrievalNanos += System.nanoTime() - retrievalStart;
      var rankPositions = new HashMap<UUID, Integer>();
      for (int i = 0; i < ranking.size(); i++) rankPositions.put(ranking.get(i), i);
      evaluated.sort(Comparator.comparingInt((Candidate c) -> "pass".equals(c.compatibility()) ? 0 : 1)
          .thenComparingInt(c -> rankPositions.get(c.productId())));
      categories.add(new Category(kit.work(), kit.kind(), kit.coveringFamily(),
          evaluated.stream().limit(10).toList()));
    }
    for (String work : request.works())
      if (categories.stream().noneMatch(c -> c.work().equals(work))) warnings.add("Нет правила категории для работы: " + work);
    warnings.add("Подготовка основания и полный состав строительной технологии не подтверждены этим подбором.");
    if (request.designPreferences() != null && !request.designPreferences().isBlank())
      warnings.add("Дизайнерские предпочтения сохранены как запрос; подтверждённых дизайнерских свойств в текущем контракте нет.");
    boolean incomplete = categories.isEmpty() || categories.stream().anyMatch(c -> c.candidates().stream().noneMatch(p -> "pass".equals(p.compatibility())));
    String status = !questions.isEmpty() ? "needs_clarification" : incomplete ? "partial" : "ready";
    long generationStart = System.nanoTime();
    var explanation = explanations.explain(request, categories, Boolean.TRUE.equals(request.explain()));
    var referenceAnswer = Boolean.TRUE.equals(request.referenceSearch())
        ? references.search(request.query(), request.works(), Boolean.TRUE.equals(request.explain()))
        : ReferenceRagService.empty("disabled", "not_requested");
    if (Boolean.TRUE.equals(request.referenceSearch()))
      warnings.add("Справочные PDF — черновые документы без привязки к учебным SKU. Они не подтверждают товары и не разрешают расчёт.");
    long generationNanos = System.nanoTime() - generationStart;
    metrics.timer("repaircalc.material.verification").record(verificationNanos, java.util.concurrent.TimeUnit.NANOSECONDS);
    metrics.timer("repaircalc.material.retrieval").record(retrievalNanos, java.util.concurrent.TimeUnit.NANOSECONDS);
    metrics.timer("repaircalc.material.generation").record(generationNanos, java.util.concurrent.TimeUnit.NANOSECONDS);
    metrics.counter("repaircalc.material.requests", "status", status).increment();
    if (release == null) metrics.counter("repaircalc.material.fallback", "reason", "knowledge_unavailable").increment();
    if (explanation != null && explanation.fallbackReason() != null)
      metrics.counter("repaircalc.material.fallback", "reason", explanation.fallbackReason()).increment();
    UUID id = UUID.randomUUID();
    org.slf4j.LoggerFactory.getLogger(getClass()).info(
        "material_recommendation id={} catalog={} rules={} knowledge={} retrieval=bm25-v1 status={} verificationMs={} retrievalMs={} generationMs={}",
        id, snapshot.catalog().releaseId(), snapshot.rules().id(), release == null ? "none" : release.id(),
        status, verificationNanos / 1_000_000, retrievalNanos / 1_000_000, generationNanos / 1_000_000);
    var result = new Result(id, status, snapshot.catalog().releaseId(), snapshot.rules().id(),
        release == null ? null : release.id(), release == null ? null : release.checksum(),
        "bm25-v1", questions, categories, warnings, explanation, referenceAnswer);
    selections.remember(request, result);
    return result;
  }

  static Candidate evaluate(ProductView product, String work, Request request,
      CalculationSnapshotService.Snapshot snapshot, KnowledgeService.Release release, RuleSetBundle.Assignment assignment) {
    var checks = new ArrayList<Check>();
    var evidence = new ArrayList<Evidence>();
    if (release == null) checks.add(new Check("evidence", "unknown", "Нет актуальной базы знаний."));
    else {
      var bundle = release.bundle();
      var chunks = new HashMap<String, KnowledgeBundle.Chunk>();
      bundle.chunks().stream().filter(c -> c.productId().equals(product.id())).forEach(c -> chunks.put(c.id(), c));
      var sources = new HashMap<String, KnowledgeBundle.Source>();
      bundle.sources().forEach(s -> sources.put(s.id(), s));
      // All published fragments for the product travel together, including limitations.
      chunks.values().stream().sorted(Comparator.comparing(KnowledgeBundle.Chunk::id)).forEach(c -> {
        var source = sources.get(c.sourceId());
        evidence.add(new Evidence(c.id(), source.id(), source.title(), source.url(), source.version(),
            source.sha256(), c.section(), c.text()));
      });
      var rule = bundle.requirements().stream().filter(r -> r.work().equals(work) && r.kind().equals(product.kind())).findFirst();
      if (rule.isEmpty()) checks.add(new Check("rule", "unknown", "Нет проверенного правила условий этой категории."));
      else for (String field : rule.get().conditions())
        if (condition(request.conditions(), field) == null)
          checks.add(new Check(field, "unknown", "Неизвестное обязательное условие."));
      var claims = bundle.claims().stream().filter(c -> c.productId().equals(product.id())).toList();
      checks.add(check(claims, "work", work, false));
      var conditions = request.conditions();
      if (conditions != null) {
        if ("high".equals(conditions.humidity())) checks.add(check(claims, "wet", "true", true));
        if ("high".equals(conditions.load())) checks.add(check(claims, "highLoad", "true", true));
        if (Boolean.FALSE.equals(conditions.heated())) checks.add(check(claims, "unheated", "true", true));
        if (conditions.substrate() != null) checks.add(check(claims, "substrate", conditions.substrate(), false));
        if (conditions.waterContact() != null) checks.add(check(claims, "waterContact", conditions.waterContact(), false));
        if (conditions.floorHeating() != null) checks.add(check(claims, "floorHeating", conditions.floorHeating().toString(), true));
        if (conditions.covering() != null) checks.add(check(claims, "covering", conditions.covering(), false));
        if (conditions.baseState() != null) checks.add(check(claims, "baseState", conditions.baseState(), false));
      }
    }
    if (!questions(request).isEmpty()) checks.add(new Check("conditions", "unknown", "Уточните условия помещения."));
    boolean conflict = checks.stream().anyMatch(c -> c.status().equals("fail"));
    boolean unknown = checks.stream().anyMatch(c -> c.status().equals("unknown"));
    String compatibility = conflict ? "fail" : unknown ? "unknown" : "pass";
    boolean profile = assignment != null && assignment.profileId() != null
        && snapshot.rules().bundle().profiles().stream().anyMatch(p -> p.id().equals(assignment.profileId()));
    boolean price = product.price() != null && !product.price().isBlank();
    // Profile-specific arithmetic remains with the Java calculator and may ask for more parameters.
    boolean packaging = product.packageSize() != null && product.packageSize().signum() > 0;
    String readiness = "pass".equals(compatibility) && profile && price && packaging ? "ready" : "unavailable";
    String reason = "pass".equals(compatibility) ? "Проверенные утверждения соответствуют указанным условиям."
        : "Совместимость не подтверждена. См. проверки и источники.";
    return new Candidate(product.id(), KnowledgeService.revision(snapshot.catalog().releaseId(), product.id()),
        product.name(), compatibility, readiness, reason, checks, evidence);
  }

  private static Object condition(Conditions c, String field) {
    if (c == null) return null;
    return switch (field) {
      case "humidity" -> c.humidity(); case "load" -> c.load(); case "heated" -> c.heated();
      case "substrate" -> c.substrate(); case "waterContact" -> c.waterContact();
      case "floorHeating" -> c.floorHeating(); case "covering" -> c.covering(); case "baseState" -> c.baseState();
      default -> null;
    };
  }

  static Check check(List<KnowledgeBundle.Claim> claims, String property, String value, boolean booleanProperty) {
    var related = claims.stream().filter(c -> c.property().equals(property)).toList();
    if (related.stream().anyMatch(c -> (c.status().equals("conflict") || c.status().equals("unknown"))))
      return new Check(property, "unknown", "Источники противоречат друг другу.");
    boolean positive = related.stream().anyMatch(c -> c.status().equals("confirmed") && c.value().equals(value));
    boolean negative = booleanProperty && related.stream().anyMatch(c -> c.status().equals("confirmed") && !c.value().equals(value));
    String status = positive && negative ? "unknown" : negative ? "fail" : positive ? "pass" : "unknown";
    return new Check(property, status, status.equals("pass") ? "Подтверждено источником." : "Нет однозначного подтверждения условия.");
  }

  private static List<Question> questions(Request request) {
    var result = new ArrayList<Question>();
    var c = request.conditions();
    if (c == null || c.humidity() == null) result.add(new Question("humidity", "Влажность влияет на применимость товара.", List.of("normal", "high")));
    if (c == null || c.load() == null) result.add(new Question("load", "Нагрузка влияет на применимость товара.", List.of("normal", "high")));
    if (c == null || c.heated() == null) result.add(new Question("heated", "Отопление влияет на применимость товара.", List.of("true", "false")));
    return result;
  }

  public record Conditions(@Pattern(regexp = "normal|high") String humidity,
      @Pattern(regexp = "normal|high") String load, Boolean heated,
      @Size(min = 1, max = 100) String substrate, @Size(min = 1, max = 100) String waterContact,
      Boolean floorHeating, @Size(min = 1, max = 100) String covering, @Size(min = 1, max = 100) String baseState) {
    public Conditions(String humidity, String load, Boolean heated) { this(humidity, load, heated, null, null, null, null, null); }
  }
  public record Request(
      @NotEmpty @Size(max = 4) List<@NotNull @Pattern(regexp = "floor|paint|wallpaper|electrical") String> works,
      @Valid Conditions conditions, @Size(max = 500) String query,
      @Valid ProjectInput.Room room, @Size(max = 500) String designPreferences, Boolean explain,
      @Pattern(regexp = "cosmetic|capital|designer") String renovation,
      @Pattern(regexp = "apartment|house|studio") String object, Boolean referenceSearch) {
    public Request(List<String> works, Conditions conditions, String query, ProjectInput.Room room, String designPreferences, Boolean explain, String renovation, String object) {
      this(works, conditions, query, room, designPreferences, explain, renovation, object, false);
    }
    public Request(List<String> works, Conditions conditions, String query, ProjectInput.Room room, String designPreferences, Boolean explain) {
      this(works, conditions, query, room, designPreferences, explain, null, null, false);
    }
    public Request(List<String> works, Conditions conditions, String query) { this(works, conditions, query, null, null, false, null, null, false); }
  }
  public record Question(String field, String reason, List<String> options) {}
  public record Check(String property, String status, String reason) {}
  public record Evidence(String chunkId, String sourceId, String title, String url, String version,
                         String sha256, String section, String text) {}
  public record Candidate(UUID productId, String productVersionId, String name, String compatibility,
      String calculationReadiness, String reason, List<Check> checks, List<Evidence> sources) {}
  public record Category(String work, String kind, String coveringFamily, List<Candidate> candidates) {}
  public record Result(UUID requestId, String status, UUID catalogReleaseId, UUID ruleSetId,
      String knowledgeReleaseId, String indexVersion, String retrievalVersion,
      List<Question> questions, List<Category> categories, List<String> warnings,
      MaterialExplanationService.Explanation explanation, ReferenceRagService.Answer references) {}
}
