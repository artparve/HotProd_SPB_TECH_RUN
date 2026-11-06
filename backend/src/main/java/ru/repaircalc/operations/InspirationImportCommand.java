package ru.repaircalc.operations;

import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import ru.repaircalc.inspirations.InspirationService;
import ru.repaircalc.storage.MongoSchema;
import ru.repaircalc.ingestion.CatalogExpansionService;

@Component
@ConditionalOnProperty(name = "repaircalc.command", havingValue = "import-inspirations")
public class InspirationImportCommand implements ApplicationRunner {
  private final InspirationService service;
  private final MongoSchema schema;
  private final String manifest;
  private final CatalogExpansionService expansion;
  private final boolean expandCatalog;
  private final Path data;

  public InspirationImportCommand(
      InspirationService service,
      MongoSchema schema,
      CatalogExpansionService expansion,
      @Value("${INSPIRATIONS_MANIFEST:../data/inspirations/manifest.json}") String manifest,
      @Value("${EXPAND_ROOM_CATALOG:false}") boolean expandCatalog,
      @Value("${DATA_DIR:../data}") String data) {
    this.service = service;
    this.schema = schema;
    this.manifest = manifest;
    this.expansion = expansion;
    this.expandCatalog = expandCatalog;
    this.data = Path.of(data);
  }

  public void run(ApplicationArguments args) throws Exception {
    schema.initialize();
    if (expandCatalog)
      System.out.println("Added room products: " + expansion.expand(data.resolve("catalog-expanded.json"),
          data.resolve("work-mappings-expanded.json"), data.resolve("rules-expanded.json")));
    System.out.println("Imported inspirations: " + service.importManifest(Path.of(manifest)));
  }
}
