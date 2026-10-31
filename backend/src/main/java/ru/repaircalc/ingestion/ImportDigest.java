package ru.repaircalc.ingestion;

import java.nio.charset.StandardCharsets;
import ru.repaircalc.storage.MongoStore;

/** Prepared text artifacts retain the same identity on Windows and Linux checkouts. */
public final class ImportDigest {
  private ImportDigest() {}

  public static String text(byte[] bytes) {
    return MongoStore.sha(new String(bytes, StandardCharsets.UTF_8).replace("\r\n", "\n"));
  }
}
