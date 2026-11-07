package ru.repaircalc.materials;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import ru.repaircalc.calculation.MaterialRecommendationService.*;
import ru.repaircalc.llm.LlmClient;
import tools.jackson.databind.json.JsonMapper;

class MaterialExplanationServiceTest {
  @Test
  void validatesIdsAndFallsBackOnForeignEvidenceOrTransportErrors() {
    var llm = mock(LlmClient.class);
    when(llm.configured()).thenReturn(true);
    var id = UUID.randomUUID();
    var evidence = new Evidence("c1", "s1", "Источник", "https://example.test", "v1", "a".repeat(64), "1", "Факт");
    var candidate = new Candidate(id, "v1", "Товар", "pass", "ready", "Проверено", List.of(), List.of(evidence));
    var categories = List.of(new Category("paint", "paint", null, List.of(candidate)));
    var request = new Request(List.of("paint"), new Conditions("normal", "normal", true), "");
    var service = new MaterialExplanationService(llm);
    var json = JsonMapper.builder().build();
    when(llm.call(eq("materials/explain"), any(), any())).thenReturn(json.readTree(
        "{\"model\":\"test\",\"items\":[{\"productId\":\"" + id + "\",\"text\":\"Объяснение\",\"chunkIds\":[\"c1\"]}]}"));
    assertEquals("unverified_model_text", service.explain(request, categories, true).status());
    when(llm.call(eq("materials/explain"), any(), any())).thenReturn(json.readTree(
        "{\"model\":\"test\",\"items\":[{\"productId\":\"" + id + "\",\"text\":\"Объяснение\",\"chunkIds\":[\"foreign\"]}]}"));
    var fallback = service.explain(request, categories, true);
    assertEquals("deterministic", fallback.status());
    assertEquals("Проверено", fallback.items().getFirst().text());
    when(llm.call(eq("materials/explain"), any(), any())).thenThrow(new LlmClient.Failure(503, "model_busy"));
    assertEquals("deterministic", service.explain(request, categories, true).status());
    assertEquals("deterministic", service.explain(request, List.of(), true).status());
  }
}
