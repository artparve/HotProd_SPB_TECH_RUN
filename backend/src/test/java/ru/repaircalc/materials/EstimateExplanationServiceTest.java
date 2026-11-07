package ru.repaircalc.materials;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import ru.repaircalc.calculation.ProductEstimate;
import ru.repaircalc.llm.LlmClient;
import tools.jackson.databind.json.JsonMapper;

class EstimateExplanationServiceTest {
  @Test
  void modelFailureCannotModifyJavaEstimateAndForeignFieldsAreRejected() {
    var llm = mock(LlmClient.class);
    when(llm.configured()).thenReturn(true);
    var service = new EstimateExplanationService(llm);
    var estimate = new ProductEstimate(UUID.randomUUID(), Instant.now(), "api", List.of(),
        "200.20", "50.00", "250.20", List.of("Предварительно"), new ProductEstimate.Versions("catalog", "rules", "java"));
    var json = JsonMapper.builder().build();
    when(llm.call(eq("project/explain"), any(), any())).thenReturn(json.readTree("{\"model\":\"test\",\"explanation\":\"Пояснение\"}"));
    assertEquals("unverified_model_text", service.explain(estimate).status());
    when(llm.call(eq("project/explain"), any(), any())).thenReturn(json.readTree("{\"model\":\"test\",\"explanation\":\"Ответ\",\"total\":\"0\"}"));
    assertEquals("unavailable", service.explain(estimate).status());
    when(llm.call(eq("project/explain"), any(), any())).thenThrow(new LlmClient.Failure(503, "model_busy"));
    assertEquals("unavailable", service.explain(estimate).status());
    assertEquals("250.20", estimate.total());
  }
}
