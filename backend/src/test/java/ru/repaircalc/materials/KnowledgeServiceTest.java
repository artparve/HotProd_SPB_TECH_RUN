package ru.repaircalc.materials;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import ru.repaircalc.calculation.CalculationSnapshotService;
import ru.repaircalc.catalog.*;
import ru.repaircalc.common.web.ApiStatusException;
import ru.repaircalc.rules.*;

class KnowledgeServiceTest {
  @Test
  void rejectsDraftExpiredEvidenceAndIncorrectProductRevision() {
    var product = mock(ProductView.class);
    var id = UUID.randomUUID();
    when(product.id()).thenReturn(id);
    var catalogId = UUID.randomUUID();
    var ruleId = UUID.randomUUID();
    var rules = mock(RuleSetBundle.class);
    when(rules.kits()).thenReturn(List.of(new RuleSetBundle.Kit("paint", "paint", RuleSetBundle.Basis.WALL, null, null)));
    var snapshot = new CalculationSnapshotService.Snapshot(new CatalogSnapshot(catalogId, "v", "f", "t", List.of(product)),
        new RuleSetSnapshot(ruleId, catalogId, "v", "f", rules));
    var today = LocalDate.of(2026, 11, 7);
    var clock = Clock.fixed(Instant.parse("2026-11-07T00:00:00Z"), ZoneOffset.UTC);
    try (var factory = jakarta.validation.Validation.buildDefaultValidatorFactory()) {
      var service = new KnowledgeService(null, null, clock, factory.getValidator());
      for (String review : List.of("confirmed", "draft")) {
        var source = new KnowledgeBundle.Source("s1", "Тест", "Тест", "https://example.test", "v1", today, today.plusDays(1), "a".repeat(64), "approved", review);
        var bundle = bundle(catalogId, ruleId, id, source, KnowledgeService.revision(catalogId, id));
        if (review.equals("confirmed")) assertDoesNotThrow(() -> service.validate(bundle, snapshot));
        else assertThrows(ApiStatusException.class, () -> service.validate(bundle, snapshot));
      }
      var expired = new KnowledgeBundle.Source("s1", "Тест", "Тест", "https://example.test", "v1", today.minusDays(5), today.minusDays(1), "a".repeat(64), "approved", "confirmed");
      assertThrows(ApiStatusException.class, () -> service.validate(bundle(catalogId, ruleId, id, expired, KnowledgeService.revision(catalogId, id)), snapshot));
      var valid = new KnowledgeBundle.Source("s1", "Тест", "Тест", "https://example.test", "v1", today, today.plusDays(1), "a".repeat(64), "approved", "confirmed");
      assertThrows(ApiStatusException.class, () -> service.validate(bundle(catalogId, ruleId, id, valid, "wrong-revision"), snapshot));
    }
  }
  private static KnowledgeBundle bundle(UUID catalog, UUID rules, UUID product, KnowledgeBundle.Source source, String revision) {
    return new KnowledgeBundle(catalog, rules, List.of(source),
        List.of(new KnowledgeBundle.Chunk("c1", product, revision, "s1", "1", "Факт")),
        List.of(new KnowledgeBundle.Claim(product, "work", "paint", "confirmed", List.of("c1"))),
        List.of(new KnowledgeBundle.Requirement("paint", "paint", List.of("humidity", "load", "heated"))), List.of());
  }
}
