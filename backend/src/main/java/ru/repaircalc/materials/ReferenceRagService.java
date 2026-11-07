package ru.repaircalc.materials;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import ru.repaircalc.llm.LlmClient;
import ru.repaircalc.storage.MongoStore;
import tools.jackson.databind.json.JsonMapper;

/** Opt-in research references. These documents NEVER create SKU claims or calculation permission. */
@Service
public class ReferenceRagService {
  private final JsonMapper json;
  private final LlmClient llm;
  private final boolean enabled;
  private final String directory;
  private final Duration explanationTimeout;
  private volatile Loaded cached;
  public ReferenceRagService(JsonMapper json, LlmClient llm, boolean enabled, String directory) {
    this(json, llm, enabled, directory, 60);
  }
  @org.springframework.beans.factory.annotation.Autowired
  public ReferenceRagService(JsonMapper json, LlmClient llm,
      @Value("${RAG_REFERENCE_SEARCH_ENABLED:false}") boolean enabled,
      @Value("${RAG_REFERENCE_INDEX:}") String directory,
      @Value("${RAG_REFERENCE_TIMEOUT_SECONDS:60}") int timeoutSeconds) {
    if (timeoutSeconds < 1 || timeoutSeconds > 1800) throw new IllegalArgumentException("Reference timeout must be 1..1800 seconds");
    this.explanationTimeout = Duration.ofSeconds(timeoutSeconds + 5L);
    this.json = json; this.llm = llm; this.enabled = enabled; this.directory = directory;
  }

  public Answer search(String query, List<String> works, boolean explain) {
    if (!enabled) return empty("disabled", "research_reference_search_disabled");
    Answer fallbackAnswer = null;
    try {
      var loaded = load();
      var corpus = loaded.corpus();
      var docs = new HashMap<String, Document>();
      corpus.documents().forEach(d -> docs.put(d.id(), d));
      var texts = new LinkedHashMap<UUID, String>();
      var chunks = new HashMap<UUID, Chunk>();
      for (var chunk : corpus.chunks()) {
        var source = docs.get(chunk.sourceId());
        UUID id = UUID.nameUUIDFromBytes(chunk.id().getBytes(StandardCharsets.UTF_8));
        texts.put(id, source.title() + " " + chunk.text());
        chunks.put(id, chunk);
      }
      String search = query == null || query.isBlank() ? String.join(" ", works.stream().map(w -> switch (w) {
        case "floor" -> "ламинат покрытие"; case "paint" -> "краска грунтовка";
        case "wallpaper" -> "обои"; default -> "розетки кабель";
      }).toList()) : query;
      var selected = new ArrayList<Fragment>();
      var counts = new HashMap<String, Integer>();
      for (UUID id : LexicalRetrieval.rank(texts, search)) {
        var chunk = chunks.get(id);
        var source = docs.get(chunk.sourceId());
        if (!overlaps(texts.get(id), search) || counts.getOrDefault(source.id(), 0) >= 2) continue;
        selected.add(new Fragment(chunk.id(), source.id(), source.title(), source.publisher(), source.url(),
            source.sha256(), source.version(), source.versionNote(), chunk.page(), chunk.text(),
            source.reviewStatus(), source.rightsStatus(), false));
        counts.merge(source.id(), 1, Integer::sum);
        if (selected.size() == 6) break;
      }
      if (selected.isEmpty()) return new Answer("empty", loaded.id(), "research_draft", false, List.of(), List.of(), null, "no_relevant_fragments");
      var fallback = new Answer("deterministic", loaded.id(), "research_draft", false, selected, List.of(), null,
          !explain ? "explanation_disabled" : "llm_unavailable");
      fallbackAnswer = fallback;
      if (!explain || !llm.configured()) return fallback;
      var response = llm.call("references/explain", Map.of("query", search, "fragments", selected.stream().map(f ->
          Map.of("chunkId", f.chunkId(), "title", f.title(), "page", f.page(), "text", f.text(),
              "reviewStatus", f.reviewStatus(), "versionNote", f.versionNote())).toList()), explanationTimeout);
      if (!response.isObject() || response.size() != 2 || !response.path("model").isTextual()
          || response.path("model").asText().length() > 256 || !response.path("items").isArray()
          || response.path("items").isEmpty() || response.path("items").size() > 6) return fallback;
      var allowed = selected.stream().map(Fragment::chunkId).toList();
      var items = new ArrayList<Item>();
      for (var item : response.path("items")) {
        if (!item.isObject() || item.size() != 2 || !item.path("text").isTextual()
            || item.path("text").asText().isBlank() || item.path("text").asText().length() > 1500
            || !item.path("chunkIds").isArray() || item.path("chunkIds").isEmpty()
            || item.path("chunkIds").size() > 6) return fallback;
        var ids = new ArrayList<String>();
        for (var node : item.path("chunkIds")) {
          if (!node.isTextual() || !allowed.contains(node.asText()) || ids.contains(node.asText())) return fallback;
          ids.add(node.asText());
        }
        items.add(new Item(item.path("text").asText(), ids));
      }
      return new Answer("unverified_model_text", loaded.id(), "research_draft", false, selected, items,
          response.path("model").asText(), null);
    } catch (LlmClient.Failure failure) {
      return fallbackAnswer != null ? fallbackAnswer : empty("unavailable", "llm_unavailable");
    } catch (java.io.IOException | IllegalArgumentException | NullPointerException | tools.jackson.core.JacksonException failure) {
      return empty("unavailable", "reference_index_invalid_or_unavailable");
    }
  }

  private Loaded load() throws java.io.IOException {
    if (directory.isBlank()) throw new IllegalArgumentException();
    Path path = Path.of(directory);
    if (Files.size(path.resolve("corpus.json")) > 16 * 1024 * 1024
        || Files.size(path.resolve("manifest.json")) > 32768) throw new IllegalArgumentException();
    byte[] bytes = Files.readAllBytes(path.resolve("corpus.json"));
    var manifest = json.readTree(Files.readAllBytes(path.resolve("manifest.json")));
    String checksum = MongoStore.sha(bytes);
    if (!checksum.equals(manifest.path("corpusSha256").asText()) || !checksum.equals(manifest.path("indexId").asText())
        || manifest.path("schemaVersion").asInt() != 1 || !"research_draft".equals(manifest.path("mode").asText())
        || !manifest.has("usableForDecisions") || manifest.path("usableForDecisions").asBoolean(true)
        || manifest.path("productLinks").asInt(-1) != 0
        || !"bm25-v1".equals(manifest.path("retrievalVersion").asText())
        || !"pdf-layout-page-v1".equals(manifest.path("chunkingVersion").asText())
        || !"unicode-lower-yo-v1".equals(manifest.path("normalizationVersion").asText())
        || !"none".equals(manifest.path("embeddingModel").asText()) || manifest.path("dimension").asInt(-1) != 0)
      throw new IllegalArgumentException();
    var existing = cached;
    var corpus = existing != null && existing.id().equals(checksum) ? existing.corpus() : json.readValue(bytes, Corpus.class);
    if (corpus.schemaVersion() != 1 || !"research_draft".equals(corpus.mode()) || corpus.documents().isEmpty()
        || corpus.documents().size() > 100 || corpus.chunks().isEmpty() || corpus.chunks().size() > 20000
        || manifest.path("documentCount").asInt() != corpus.documents().size()
        || manifest.path("chunkCount").asInt() != corpus.chunks().size()) throw new IllegalArgumentException();
    var documents = new HashMap<String, Document>();
    for (var source : corpus.documents()) {
      if (source.id() == null || documents.put(source.id(), source) != null || source.pages() < 1
          || source.title() == null || source.publisher() == null || source.version() == null || source.versionNote() == null
          || source.reviewStatus() == null || source.rightsStatus() == null || source.productionIndexApproved()
          || source.url() != null && !source.url().startsWith("https://")
          || source.sha256() == null || !source.sha256().matches("[a-f0-9]{64}")) throw new IllegalArgumentException();
    }
    var ids = new HashSet<String>();
    for (var chunk : corpus.chunks()) {
      var source = documents.get(chunk.sourceId());
      if (source == null || chunk.id() == null || !ids.add(chunk.id()) || chunk.page() < 1 || chunk.page() > source.pages()
          || chunk.text() == null || chunk.text().isBlank() || chunk.text().length() > 1200) throw new IllegalArgumentException();
    }
    var loaded = new Loaded(checksum, corpus);
    cached = loaded;
    return loaded;
  }
  private static boolean overlaps(String text, String query) {
    var tokens = new HashSet<>(Arrays.asList(text.toLowerCase(Locale.ROOT).replace('ё', 'е').split("[^\\p{L}\\p{N}]+")));
    return Arrays.stream(query.toLowerCase(Locale.ROOT).replace('ё', 'е').split("[^\\p{L}\\p{N}]+"))
        .anyMatch(t -> t.length() > 2 && tokens.contains(t));
  }
  public static Answer empty(String status, String reason) { return new Answer(status, null, "research_draft", false, List.of(), List.of(), null, reason); }
  record Loaded(String id, Corpus corpus) {}
  public record Corpus(int schemaVersion, String mode, List<Document> documents, List<Chunk> chunks) {}
  public record Document(String id, String title, String publisher, String url, String sha256, String version,
      String versionNote, String retrievedOn, List<String> tags, String reviewStatus, String rightsStatus,
      boolean productionIndexApproved, int pages, List<Integer> emptyPages) {}
  public record Chunk(String id, String sourceId, int page, String text) {}
  public record Fragment(String chunkId, String sourceId, String title, String publisher, String url, String sha256,
      String version, String versionNote, int page, String text, String reviewStatus, String rightsStatus, boolean usableForDecisions) {}
  public record Item(String text, List<String> chunkIds) {}
  public record Answer(String status, String indexId, String corpusMode, boolean usableForDecisions,
      List<Fragment> fragments, List<Item> items, String model, String fallbackReason) {}
}
