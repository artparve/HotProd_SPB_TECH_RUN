package ru.repaircalc.materials;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.repaircalc.llm.LlmClient;
import ru.repaircalc.storage.MongoStore;
import tools.jackson.databind.json.JsonMapper;

class ReferenceRagServiceTest {
  @TempDir Path directory;
  private final JsonMapper json = JsonMapper.builder().build();

  @Test
  void draftReferenceNeverBecomesSkuEvidenceAndWrongModelCitationFallsBack() throws Exception {
    index();
    assertThrows(IllegalArgumentException.class, () -> new ReferenceRagService(json, mock(LlmClient.class), true, directory.toString(), 0));
    var llm = mock(LlmClient.class);
    when(llm.configured()).thenReturn(true);
    when(llm.call(eq("references/explain"), any(), any())).thenReturn(json.readTree(
        "{\"model\":\"test\",\"items\":[{\"text\":\"Черновой фрагмент\",\"chunkIds\":[\"c1\"]}]}"));
    var service = new ReferenceRagService(json, llm, true, directory.toString());
    var result = service.search("Quick-Step подогрев", List.of("floor"), true);
    assertEquals("unverified_model_text", result.status());
    assertFalse(result.usableForDecisions());
    assertEquals("draft", result.fragments().getFirst().reviewStatus());
    assertEquals(1, result.fragments().getFirst().page());
    assertNull(result.fragments().getFirst().url());
    when(llm.call(eq("references/explain"), any(), any())).thenReturn(json.readTree(
        "{\"model\":\"test\",\"items\":[{\"text\":\"Выдумка\",\"chunkIds\":[\"foreign\"]}]}"));
    var invalid = service.search("Quick-Step", List.of("floor"), true);
    assertEquals("deterministic", invalid.status());
    assertFalse(invalid.fragments().isEmpty());
    when(llm.call(eq("references/explain"), any(), any())).thenThrow(new LlmClient.Failure(503, "timeout"));
    assertEquals("deterministic", service.search("Quick-Step", List.of("floor"), true).status());
    assertEquals("disabled", new ReferenceRagService(json, llm, false, directory.toString()).search("Quick-Step", List.of("floor"), true).status());
  }

  @Test
  void checksumAndManifestAreValidatedEvenAfterCaching() throws Exception {
    index();
    var service = new ReferenceRagService(json, mock(LlmClient.class), true, directory.toString());
    assertEquals("deterministic", service.search("Quick-Step", List.of("floor"), false).status());
    var manifest = json.readTree(Files.readAllBytes(directory.resolve("manifest.json"))).deepCopy();
    ((tools.jackson.databind.node.ObjectNode) manifest).put("chunkCount", 99);
    Files.writeString(directory.resolve("manifest.json"), json.writeValueAsString(manifest));
    assertEquals("unavailable", service.search("Quick-Step", List.of("floor"), false).status());
    index();
    Files.writeString(directory.resolve("corpus.json"), "corrupt");
    assertEquals("unavailable", service.search("Quick-Step", List.of("floor"), false).status());
  }

  private void index() throws Exception {
    var document = new ReferenceRagService.Document("s1", "Quick-Step", "Test", null, "a".repeat(64), "v1",
        "Редакция требует проверки", "2026-11-07", List.of("floor"), "draft", "not_assessed", false, 1, List.of());
    var corpus = new ReferenceRagService.Corpus(1, "research_draft", List.of(document),
        List.of(new ReferenceRagService.Chunk("c1", "s1", 1, "Quick-Step подогрев. Черновая инструкция.")));
    byte[] bytes = json.writeValueAsBytes(corpus);
    String sha = MongoStore.sha(bytes);
    Files.write(directory.resolve("corpus.json"), bytes);
    Files.writeString(directory.resolve("manifest.json"), json.writeValueAsString(Map.ofEntries(
        Map.entry("schemaVersion", 1), Map.entry("mode", "research_draft"), Map.entry("corpusSha256", sha), Map.entry("indexId", sha),
        Map.entry("usableForDecisions", false), Map.entry("productLinks", 0), Map.entry("retrievalVersion", "bm25-v1"),
        Map.entry("chunkingVersion", "pdf-layout-page-v1"), Map.entry("normalizationVersion", "unicode-lower-yo-v1"),
        Map.entry("embeddingModel", "none"), Map.entry("dimension", 0), Map.entry("documentCount", 1), Map.entry("chunkCount", 1))));
  }
}
