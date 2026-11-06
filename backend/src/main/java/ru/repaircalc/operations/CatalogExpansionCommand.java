package ru.repaircalc.operations;

import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import ru.repaircalc.ingestion.CatalogExpansionService;
import ru.repaircalc.inspirations.InspirationService;

@Component
@ConditionalOnProperty(name = "repaircalc.command", havingValue = "expand-catalog")
public class CatalogExpansionCommand implements ApplicationRunner {
  private final CatalogExpansionService expansion;
  private final InspirationService inspirations;
  private final Path data, manifest;

  public CatalogExpansionCommand(CatalogExpansionService expansion, InspirationService inspirations,
      @Value("${DATA_DIR:../data}") String data,
      @Value("${INSPIRATIONS_MANIFEST:../data/inspirations/manifest.json}") String manifest) {
    this.expansion = expansion;
    this.inspirations = inspirations;
    this.data = Path.of(data);
    this.manifest = Path.of(manifest);
  }

  public void run(ApplicationArguments args) throws Exception {
    System.out.println("Added room products: " + expansion.expand(data.resolve("catalog-expanded.json"),
        data.resolve("work-mappings-expanded.json"), data.resolve("rules-expanded.json")));
    System.out.println("Imported inspirations: " + inspirations.importManifest(manifest));
  }
}
