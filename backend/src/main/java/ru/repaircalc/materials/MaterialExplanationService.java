package ru.repaircalc.materials;

import java.time.Duration;
import java.util.*;
import org.springframework.stereotype.Service;
import ru.repaircalc.calculation.MaterialRecommendationService.*;
import ru.repaircalc.llm.LlmClient;

/** Model prose is always unverified and cannot alter candidates or compatibility. */
@Service
public class MaterialExplanationService {
  private final LlmClient llm;
  public MaterialExplanationService(LlmClient llm) { this.llm = llm; }

  public Explanation explain(Request request, List<Category> categories, boolean enabled) {
    var candidates = new LinkedHashMap<UUID, Candidate>();
    categories.forEach(c -> c.candidates().stream().filter(p -> "pass".equals(p.compatibility()))
        .forEach(p -> candidates.putIfAbsent(p.productId(), p)));
    var selected = candidates.values().stream().limit(10).toList();
    var fallback = selected.stream().map(c -> new Item(c.productId(), c.reason(),
        c.sources().stream().map(Evidence::chunkId).toList())).toList();
    if (!enabled || selected.isEmpty() || !llm.configured())
      return new Explanation("deterministic", null, fallback,
          !enabled ? "disabled" : selected.isEmpty() ? "no_confirmed_candidates" : "llm_unavailable");
    var context = selected.stream().map(c -> Map.of("productId", c.productId().toString(),
        "name", c.name(), "facts", c.sources().stream().limit(10).map(e ->
            Map.of("chunkId", e.chunkId(), "text", e.text())).toList())).toList();
    try {
      var response = llm.call("materials/explain", Map.of("query", Objects.toString(request.query(), ""),
          "products", context), Duration.ofSeconds(35));
      if (!response.isObject() || response.size() != 2 || !response.has("items") || !response.has("model")
          || !response.path("model").isTextual() || response.path("model").asText().length() > 256
          || !response.path("items").isArray() || response.path("items").isEmpty()
          || response.path("items").size() > selected.size()) throw new IllegalArgumentException();
      var seen = new HashSet<UUID>();
      var items = new ArrayList<Item>();
      for (var item : response.path("items")) {
        if (!item.isObject() || item.size() != 3 || !item.path("productId").isTextual()
            || !item.path("text").isTextual() || item.path("text").asText().isBlank()
            || item.path("text").asText().length() > 1500 || !item.path("chunkIds").isArray()
            || item.path("chunkIds").isEmpty() || item.path("chunkIds").size() > 10) throw new IllegalArgumentException();
        var id = UUID.fromString(item.path("productId").asText());
        var candidate = selected.stream().filter(c -> c.productId().equals(id)).findFirst().orElseThrow();
        if (!seen.add(id)) throw new IllegalArgumentException();
        var allowed = candidate.sources().stream().limit(10).map(Evidence::chunkId).toList();
        var ids = new ArrayList<String>();
        for (var node : item.path("chunkIds")) {
          if (!node.isTextual() || !allowed.contains(node.asText()) || ids.contains(node.asText())) throw new IllegalArgumentException();
          ids.add(node.asText());
        }
        items.add(new Item(id, item.path("text").asText(), ids));
      }
      return new Explanation("unverified_model_text", response.path("model").asText(), items, null);
    } catch (LlmClient.Failure | IllegalArgumentException | NoSuchElementException e) {
      return new Explanation("deterministic", null, fallback, "llm_unavailable_or_invalid_response");
    }
  }
  public record Item(UUID productId, String text, List<String> chunkIds) {}
  public record Explanation(String status, String model, List<Item> items, String fallbackReason) {}
}
