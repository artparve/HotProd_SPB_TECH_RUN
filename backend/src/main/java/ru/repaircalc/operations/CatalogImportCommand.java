package ru.repaircalc.operations;

import java.nio.file.Path;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import ru.repaircalc.ingestion.CatalogImportException;
import ru.repaircalc.ingestion.CatalogImportService;

@Component
@ConditionalOnProperty(name = "repaircalc.command", havingValue = "import-catalog")
public class CatalogImportCommand implements ApplicationRunner {
    private final CatalogImportService importer;
    private final Environment environment;

    public CatalogImportCommand(CatalogImportService importer, Environment environment) {
        this.importer = importer;
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        String catalog = environment.getProperty("repaircalc.catalog.file");
        String mappings = environment.getProperty("repaircalc.catalog.mappings");
        if (catalog == null || catalog.isBlank() || mappings == null || mappings.isBlank()) {
            throw new CatalogImportException("Set --repaircalc.catalog.file and --repaircalc.catalog.mappings.");
        }
        var result = importer.importFiles(Path.of(catalog), Path.of(mappings));
        System.out.printf("Catalog %s: version=%s release=%s products=%d fingerprint=%s%n",
                result.noOp() ? "unchanged" : "published", result.version(), result.releaseId(),
                result.productCount(), result.fingerprint());
    }
}
