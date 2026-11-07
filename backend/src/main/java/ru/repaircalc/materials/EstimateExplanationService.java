package ru.repaircalc.materials;

import java.time.Duration;
import java.util.Map;
import org.springframework.stereotype.Service;
import ru.repaircalc.calculation.ProductEstimate;
import ru.repaircalc.llm.LlmClient;

/** The model receives a Java calculation and cannot change its quantities or money. */
@Service
public class EstimateExplanationService {
  private final LlmClient llm;
  public EstimateExplanationService(LlmClient llm) { this.llm = llm; }

  public Explanation explain(ProductEstimate estimate) {
    if (!llm.configured()) return new Explanation("unavailable", null, null);
    var context = Map.of("lines", estimate.lines().stream().map(line -> Map.of(
        "name", line.product().name(), "quantity", line.quantity(), "unitPrice", line.product().price(),
        "saleUnit", line.product().saleUnit(), "total", line.total(), "formula", line.formula())).toList(),
        "materials", estimate.materials(), "labor", estimate.labor(), "total", estimate.total(),
        "warnings", estimate.warnings());
    try {
      var response = llm.call("project/explain", context, Duration.ofSeconds(65));
      if (!response.isObject() || response.size() != 2 || !response.path("model").isTextual()
          || response.path("model").asText().isBlank() || response.path("model").asText().length() > 256
          || !response.path("explanation").isTextual() || response.path("explanation").asText().isBlank()
          || response.path("explanation").asText().length() > 6000)
        return new Explanation("unavailable", null, null);
      return new Explanation("unverified_model_text", response.path("model").asText(), response.path("explanation").asText());
    } catch (LlmClient.Failure | IllegalArgumentException e) {
      return new Explanation("unavailable", null, null);
    }
  }
  public record Explanation(String status, String model, String explanation) {}
}
