package ru.repaircalc.operations;

import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import ru.repaircalc.inspirations.InspirationService;
import ru.repaircalc.storage.MongoSchema;

@Component
@ConditionalOnProperty(name = "repaircalc.command", havingValue = "import-inspirations")
public class InspirationImportCommand implements ApplicationRunner {
  private final InspirationService service;
  private final MongoSchema schema;
  private final String manifest;

  public InspirationImportCommand(
      InspirationService service,
      MongoSchema schema,
      @Value("${INSPIRATIONS_MANIFEST:../data/inspirations/manifest.json}") String manifest) {
    this.service = service;
    this.schema = schema;
    this.manifest = manifest;
  }

  public void run(ApplicationArguments args) throws Exception {
    schema.initialize();
    System.out.println("Imported inspirations: " + service.importManifest(Path.of(manifest)));
  }
}
