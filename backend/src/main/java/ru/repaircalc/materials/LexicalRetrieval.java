package ru.repaircalc.materials;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Reproducible local BM25 baseline; no model download or external vector service. */
public final class LexicalRetrieval {
  private LexicalRetrieval() {}
  public static List<UUID> rank(Map<UUID, String> documents, String query) {
    var tokens = new HashMap<UUID, List<String>>();
    documents.forEach((id, text) -> tokens.put(id, terms(text)));
    double average = Math.max(1, tokens.values().stream().mapToInt(List::size).average().orElse(1));
    var queryTerms = new HashSet<>(terms(query == null ? "" : query));
    var documentFrequencies = new HashMap<String, Long>();
    for (var document : tokens.values())
      for (String term : new HashSet<>(document))
        if (queryTerms.contains(term)) documentFrequencies.merge(term, 1L, Long::sum);
    var scores = new HashMap<UUID, Double>();
    for (var entry : tokens.entrySet()) {
      var frequencies = entry.getValue().stream().collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
      double score = 0;
      for (String term : queryTerms) {
        long tf = frequencies.getOrDefault(term, 0L);
        long df = documentFrequencies.getOrDefault(term, 0L);
        double idf = Math.log(1 + (tokens.size() - df + .5) / (df + .5));
        score += idf * tf * 2.2 / (tf + 1.2 * (.25 + .75 * entry.getValue().size() / average));
      }
      scores.put(entry.getKey(), score);
    }
    return documents.keySet().stream().sorted(Comparator.<UUID>comparingDouble(id -> -scores.get(id))
        .thenComparing(UUID::toString)).toList();
  }
  private static List<String> terms(String text) {
    return Arrays.stream(text.toLowerCase(Locale.ROOT).replace('ё', 'е').split("[^\\p{L}\\p{N}]+"))
        .filter(s -> !s.isBlank()).limit(2000).toList();
  }
}
