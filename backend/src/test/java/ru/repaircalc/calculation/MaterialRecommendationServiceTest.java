package ru.repaircalc.calculation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import ru.repaircalc.catalog.*;
import ru.repaircalc.materials.*;
import ru.repaircalc.rules.*;

class MaterialRecommendationServiceTest {
  @Test
  void absentEvidenceNeverBecomesConfirmedAndNoModelIsRequired() {
    var product = product();
    var snapshot = snapshot(product);
    var snapshots = mock(CalculationSnapshotService.class);
    when(snapshots.resolve()).thenReturn(snapshot);
    var knowledge = mock(KnowledgeService.class);
    var explanations = mock(MaterialExplanationService.class);
    var selections = mock(MaterialSelectionService.class);
    var service = new MaterialRecommendationService(snapshots, knowledge, explanations, selections, new io.micrometer.core.instrument.simple.SimpleMeterRegistry(), mock(ReferenceRagService.class));
    var request = new MaterialRecommendationService.Request(List.of("paint"),
        new MaterialRecommendationService.Conditions("high", null, null), "белая");
    var result = service.recommend(request);
    assertEquals("needs_clarification", result.status());
    assertEquals(2, result.questions().size());
    var candidate = result.categories().getFirst().candidates().getFirst();
    assertEquals("unknown", candidate.compatibility());
    assertEquals("unavailable", candidate.calculationReadiness());
    assertTrue(candidate.sources().isEmpty());
  }

  @Test
  void confirmedWetClaimPassesAndExplicitNegativeOrConflictCannotPass() {
    UUID id = UUID.randomUUID();
    var positive = new KnowledgeBundle.Claim(id, "wet", "true", "confirmed", List.of("chunk"));
    var negative = new KnowledgeBundle.Claim(id, "wet", "false", "confirmed", List.of("chunk"));
    var conflict = new KnowledgeBundle.Claim(id, "wet", "true", "conflict", List.of("chunk"));
    assertEquals("pass", MaterialRecommendationService.check(List.of(positive), "wet", "true", true).status());
    assertEquals("fail", MaterialRecommendationService.check(List.of(negative), "wet", "true", true).status());
    assertEquals("unknown", MaterialRecommendationService.check(List.of(positive, negative), "wet", "true", true).status());
    assertEquals("unknown", MaterialRecommendationService.check(List.of(positive, conflict), "wet", "true", true).status());
    assertEquals("unknown", MaterialRecommendationService.check(List.of(), "wet", "true", true).status());
  }

  @Test
  void evidenceIsScopedToProductAndUnknownSubstrateBlocksRecommendation() {
    var product = product();
    var snapshot = snapshot(product);
    var chunk = new KnowledgeBundle.Chunk("chunk", product.id(), KnowledgeService.revision(snapshot.catalog().releaseId(), product.id()), "source", "Применение", "Учебный факт");
    var source = new KnowledgeBundle.Source("source", "Тест", "Тест", "https://example.test/doc", "v1", LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1), "a".repeat(64), "approved", "confirmed");
    var claims = List.of(new KnowledgeBundle.Claim(product.id(), "work", "paint", "confirmed", List.of("chunk")));
    var bundle = new KnowledgeBundle(snapshot.catalog().releaseId(), snapshot.rules().id(), List.of(source), List.of(chunk), claims, List.of(new KnowledgeBundle.Requirement("paint", "paint", List.of("humidity", "load", "heated"))), List.of());
    var release = new KnowledgeService.Release("k1", bundle, "checksum", "bm25-v1", "", "expert");
    var request = new MaterialRecommendationService.Request(List.of("paint"), new MaterialRecommendationService.Conditions("normal", "normal", true), "");
    var pass = MaterialRecommendationService.evaluate(product, "paint", request, snapshot, release, null);
    assertEquals("pass", pass.compatibility());
    assertEquals("unavailable", pass.calculationReadiness());
    var unknown = MaterialRecommendationService.evaluate(product, "paint", new MaterialRecommendationService.Request(List.of("paint"),
        new MaterialRecommendationService.Conditions("normal", "normal", true, "бетон", null, null, null, null), ""), snapshot, release, null);
    assertEquals("unknown", unknown.compatibility());
    assertEquals("chunk", unknown.sources().getFirst().chunkId());
  }

  static ProductView product() {
    return new ProductView(UUID.randomUUID(), "Краска белая", "paint", "paint", "100", null,
        "kg", "kg", List.of("paint"), null, null, null, "", "", null, null, null, null);
  }
  static CalculationSnapshotService.Snapshot snapshot(ProductView product) {
    var bundle = mock(RuleSetBundle.class);
    when(bundle.kits()).thenReturn(List.of(new RuleSetBundle.Kit("paint", "paint", RuleSetBundle.Basis.WALL, null, null)));
    when(bundle.assignments()).thenReturn(List.of());
    var catalogId = UUID.randomUUID();
    return new CalculationSnapshotService.Snapshot(new CatalogSnapshot(catalogId, "v", "f", "t", List.of(product)),
        new RuleSetSnapshot(UUID.randomUUID(), catalogId, "v", "f", bundle));
  }
}
