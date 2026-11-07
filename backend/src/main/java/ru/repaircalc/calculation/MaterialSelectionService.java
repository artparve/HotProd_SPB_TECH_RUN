package ru.repaircalc.calculation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;
import ru.repaircalc.common.web.ApiStatusException;
import ru.repaircalc.materials.KnowledgeService;
import ru.repaircalc.storage.MongoStore;

/** Original inputs and allowed IDs are server-owned, never compatibility flags supplied by a client. */
@Service
public class MaterialSelectionService {
  private final MongoStore store;
  private final Clock clock;
  private final CalculationSnapshotService snapshots;
  private final KnowledgeService knowledge;
  private final ProjectCalculationService calculations;
  public MaterialSelectionService(MongoStore store, Clock clock, CalculationSnapshotService snapshots,
      KnowledgeService knowledge, ProjectCalculationService calculations) {
    this.store = store;
    this.clock = clock;
    this.snapshots = snapshots;
    this.knowledge = knowledge;
    this.calculations = calculations;
  }

  public void remember(MaterialRecommendationService.Request request, MaterialRecommendationService.Result result) {
    // Dimension-free searches are valid, but cannot be used as a calculation confirmation.
    if (request.room() == null) return;
    store.insert("material_recommendation", store.document(new Saved(request, result, clock.instant().plusSeconds(3600).toString()))
        .append("_id", result.requestId().toString()).append("expiresAt", java.util.Date.from(clock.instant().plusSeconds(3600))));
  }

  public ProductEstimate apply(UUID id, Apply request) {
    var document = store.id("material_recommendation", id);
    if (document == null) throw stale();
    document.remove("expiresAt");
    var saved = store.read(document, Saved.class);
    if (!java.time.Instant.parse(saved.expires()).isAfter(clock.instant())) throw stale();
    var project = request.project();
    project.validate();
    if (!Objects.equals(project.renovation(), saved.request().renovation())
        || !Objects.equals(project.object(), saved.request().object())) throw stale();
    if (project.rooms().size() != 1 || !project.rooms().getFirst().equals(saved.request().room())) throw stale();
    var room = project.rooms().getFirst();
    var conditions = saved.request().conditions();
    if (conditions == null || !Objects.equals(conditions.humidity(), room.humidity())
        || !Objects.equals(conditions.load(), room.load()) || !Objects.equals(conditions.heated(), room.heated())
        || !new HashSet<>(saved.request().works()).equals(new HashSet<>(room.works()))) throw stale();
    // Resolve the pair exactly once; the existing calculator consumes this detached immutable snapshot.
    var snapshot = snapshots.resolve();
    var result = saved.result();
    if (!snapshot.catalog().releaseId().equals(result.catalogReleaseId())
        || !snapshot.rules().id().equals(result.ruleSetId())) throw stale();
    var release = knowledge.active(snapshot);
    if (release == null || !release.id().equals(result.knowledgeReleaseId())
        || !release.checksum().equals(result.indexVersion())) throw stale();
    if (request.selections().isEmpty()) throw missing();
    for (var entry : request.selections().entrySet()) {
      var allowed = result.categories().stream().filter(c -> entry.getKey().equals(room.id() + ":" + c.kind()))
          .flatMap(c -> c.candidates().stream()).filter(c -> c.productId().toString().equals(entry.getValue())
              && "pass".equals(c.compatibility()) && "ready".equals(c.calculationReadiness())).findFirst();
      if (allowed.isEmpty()) throw missing();
      var product = snapshot.catalog().products().stream().filter(p -> p.id().equals(allowed.get().productId())).findFirst().orElseThrow(MaterialSelectionService::stale);
      var assignment = snapshot.rules().bundle().assignments().stream().filter(a -> a.productId().equals(product.id())).findFirst().orElse(null);
      for (String work : room.works()) {
        if (!product.works().contains(work)) continue;
        var checked = MaterialRecommendationService.evaluate(product, work, saved.request(), snapshot, release, assignment);
        if (!"pass".equals(checked.compatibility()) || !"ready".equals(checked.calculationReadiness())) throw missing();
      }
    }
    var selectedIds = request.selections().values().stream().map(UUID::fromString).distinct().toList();
    for (int i = 0; i < selectedIds.size(); i++) {
      for (int j = i + 1; j < selectedIds.size(); j++) {
        var a = selectedIds.get(i); var b = selectedIds.get(j);
        boolean confirmed = release.bundle().combinations().stream().anyMatch(pair ->
            "pass".equals(pair.status()) && ((pair.first().equals(a) && pair.second().equals(b))
                || (pair.first().equals(b) && pair.second().equals(a))));
        if (!confirmed) throw new ApiStatusException(422, "needs_clarification", "Совместимость выбранной связки материалов не подтверждена опубликованным правилом.");
      }
    }
    var estimate = calculations.estimate(project, request.selections(), snapshot);
    for (var line : estimate.lines())
      if (!line.product().id().toString().equals(request.selections().get(line.roomId() + ":" + line.product().kind()))) throw missing();
    var warnings = new ArrayList<>(estimate.warnings());
    warnings.add("Подбор: " + id + "; база знаний: " + release.id() + ". Проверен только поддержанный состав работ.");
    return new ProductEstimate(estimate.id(), estimate.createdAt(), estimate.mode(), estimate.lines(),
        estimate.materials(), estimate.labor(), estimate.total(), warnings, estimate.versions());
  }
  private static ApiStatusException stale() { return new ApiStatusException(409, "stale_recommendation", "Подбор устарел. Повторите подбор для текущих условий и версий."); }
  /** Explicit manual estimate. It never upgrades unknown evidence to verified compatibility. */
  public ProductEstimate preliminary(UUID id, Preview request) {
    if (!request.acceptUnverified()) throw new ApiStatusException(422, "confirmation_required", "Подтвердите предварительный расчёт без проверки применимости по документам.");
    var document = store.id("material_recommendation", id);
    if (document == null) throw stale();
    document.remove("expiresAt");
    var saved = store.read(document, Saved.class);
    if (!java.time.Instant.parse(saved.expires()).isAfter(clock.instant())) throw stale();
    var project = request.project();
    project.validate();
    if (project.rooms().size() != 1 || !project.rooms().getFirst().equals(saved.request().room())
        || !Objects.equals(project.renovation(), saved.request().renovation())
        || !Objects.equals(project.object(), saved.request().object())) throw stale();
    var room = project.rooms().getFirst();
    var conditions = saved.request().conditions();
    if (conditions == null || !Objects.equals(conditions.humidity(), room.humidity())
        || !Objects.equals(conditions.load(), room.load()) || !Objects.equals(conditions.heated(), room.heated())
        || !new HashSet<>(saved.request().works()).equals(new HashSet<>(room.works()))) throw stale();
    var snapshot = snapshots.resolve();
    if (!snapshot.catalog().releaseId().equals(saved.result().catalogReleaseId())
        || !snapshot.rules().id().equals(saved.result().ruleSetId())) throw stale();
    if (request.selections().isEmpty()) throw missing();
    for (var entry : request.selections().entrySet()) {
      boolean allowed = saved.result().categories().stream()
          .filter(c -> entry.getKey().equals(room.id() + ":" + c.kind()))
          .flatMap(c -> c.candidates().stream())
          .anyMatch(c -> c.productId().toString().equals(entry.getValue()));
      if (!allowed) throw new ApiStatusException(422, "invalid_selection", "Выберите товары из текущего подбора для этой комнаты.");
    }
    var estimate = calculations.estimate(project, request.selections(), snapshot);
    // The ordinary calculator may supply defaults. This path requires explicit choice for every line.
    for (var line : estimate.lines())
      if (!line.product().id().toString().equals(request.selections().get(line.roomId() + ":" + line.product().kind())))
        throw new ApiStatusException(422, "incomplete_selection", "Выберите товар для каждой категории, необходимой выбранной технологии.");
    var warnings = new ArrayList<>(estimate.warnings());
    warnings.add("ПРЕДВАРИТЕЛЬНЫЙ расчёт по явно выбранным товарам. Применимость и совместимость по документам не подтверждены; справочный RAG не является проверкой товаров.");
    return new ProductEstimate(estimate.id(), estimate.createdAt(), estimate.mode(), estimate.lines(),
        estimate.materials(), estimate.labor(), estimate.total(), warnings, estimate.versions());
  }
  private static ApiStatusException missing() { return new ApiStatusException(422, "needs_clarification", "Явно выберите подтверждённый товар для каждой рассчитываемой категории."); }
  public record Apply(@NotNull @Valid ProjectInput project, @NotNull @Size(max = 7) Map<@NotBlank String, @NotBlank String> selections) {}
  public record Saved(MaterialRecommendationService.Request request, MaterialRecommendationService.Result result, String expires) {}
  public record Preview(@NotNull @Valid ProjectInput project,
      @NotNull @Size(max = 7) Map<@NotBlank String, @NotBlank String> selections,
      @AssertTrue boolean acceptUnverified) {}
}
