package ru.repaircalc.ingestion;

import static org.mockito.Mockito.*;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import ru.repaircalc.inspirations.InspirationService;
import ru.repaircalc.operations.InspirationImportCommand;
import ru.repaircalc.storage.MongoSchema;

class InspirationBootstrapTest {
  @Test
  void ordinaryPhotoImportNeverChangesTheCatalog() throws Exception {
    var photos = mock(InspirationService.class);
    var schema = mock(MongoSchema.class);
    var expansion = mock(CatalogExpansionService.class);
    new InspirationImportCommand(photos, schema, expansion, "photos/manifest.json", false, "data")
        .run(new DefaultApplicationArguments());
    verifyNoInteractions(expansion);
    verify(photos).importManifest(Path.of("photos/manifest.json"));
  }

  @Test
  void preserveDataBootstrapExplicitlyExpandsBeforeImportingPhotos() throws Exception {
    var photos = mock(InspirationService.class);
    var schema = mock(MongoSchema.class);
    var expansion = mock(CatalogExpansionService.class);
    new InspirationImportCommand(photos, schema, expansion, "photos/manifest.json", true, "data")
        .run(new DefaultApplicationArguments());
    var order = inOrder(schema, expansion, photos);
    order.verify(schema).initialize();
    order.verify(expansion).expand(Path.of("data/catalog-expanded.json"),
        Path.of("data/work-mappings-expanded.json"), Path.of("data/rules-expanded.json"));
    order.verify(photos).importManifest(Path.of("photos/manifest.json"));
  }
}
