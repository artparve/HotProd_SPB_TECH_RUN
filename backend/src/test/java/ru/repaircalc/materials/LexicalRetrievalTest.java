package ru.repaircalc.materials;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class LexicalRetrievalTest {
  @Test
  void russianSearchAndTiesHaveReproducibleOrder() {
    var a = UUID.fromString("00000000-0000-0000-0000-000000000001");
    var b = UUID.fromString("00000000-0000-0000-0000-000000000002");
    assertEquals(List.of(b, a), LexicalRetrieval.rank(Map.of(a, "Краска белая", b, "Грунтовка бетон"), "БЕТОН"));
    assertEquals(List.of(a, b), LexicalRetrieval.rank(Map.of(b, "равно", a, "равно"), ""));
    assertEquals(List.of(), LexicalRetrieval.rank(Map.of(), "бетон"));
  }
}
