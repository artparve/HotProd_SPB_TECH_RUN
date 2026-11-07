package ru.repaircalc.calculation.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.*;
import org.springframework.web.bind.annotation.*;
import ru.repaircalc.calculation.*;
import ru.repaircalc.common.web.ServiceUnavailableException;

@RestController
@RequestMapping("/api/v1")
public class ProjectCalculationController {
  private final ProjectCalculationService service;
  private final MaterialRecommendationService materialRecommendations;
  private final MaterialSelectionService materialSelections;
  private final ru.repaircalc.materials.EstimateExplanationService estimateExplanations;

  public ProjectCalculationController(ProjectCalculationService service, MaterialRecommendationService materialRecommendations, MaterialSelectionService materialSelections, ru.repaircalc.materials.EstimateExplanationService estimateExplanations) {
    this.service = service;
    this.materialRecommendations = materialRecommendations;
    this.materialSelections = materialSelections;
    this.estimateExplanations = estimateExplanations;
  }

  @PostMapping("/material-recommendations")
  public MaterialRecommendationService.Result materialRecommendations(
      @Valid @RequestBody MaterialRecommendationService.Request request) {
    return materialRecommendations.recommend(request);
  }

  @PostMapping("/material-recommendations/{id}/estimate")
  public ProductEstimate applyMaterials(@PathVariable UUID id,
      @Valid @RequestBody MaterialSelectionService.Apply request) {
    return materialSelections.apply(id, request);
  }

  @PostMapping("/estimate")
  public ProductEstimate estimate(@Valid @RequestBody EstimateRequest request) {
    return service.estimate(request.project(), request.selections());
  }

  @PostMapping("/material-recommendations/{id}/preview")
  public ProductEstimate previewMaterials(@PathVariable UUID id,
      @Valid @RequestBody MaterialSelectionService.Preview request) {
    return materialSelections.preliminary(id, request);
  }

  @PostMapping("/material-recommendations/{id}/preview/explanation")
  public ru.repaircalc.materials.EstimateExplanationService.Explanation previewExplanation(@PathVariable UUID id,
      @Valid @RequestBody MaterialSelectionService.Preview request) {
    return estimateExplanations.explain(materialSelections.preliminary(id, request));
  }

  @PostMapping("/recommendations")
  public ProjectCalculationService.Recommendations recommendations(
      @Valid @RequestBody RecommendationRequest request) {
    try {
      return service.recommend(request.room());
    } catch (ServiceUnavailableException
        | org.springframework.dao.DataAccessException
        | com.mongodb.MongoException e) {
      return new ProjectCalculationService.Recommendations(
          List.of(),
          List.of("Каталог временно недоступен. Повторите подбор позже."),
          "unavailable");
    }
  }

  public record EstimateRequest(
      @NotNull @Valid ProjectInput project, Map<String, String> selections) {}

  public record RecommendationRequest(@NotNull @Valid ProjectInput.Room room) {}
}
