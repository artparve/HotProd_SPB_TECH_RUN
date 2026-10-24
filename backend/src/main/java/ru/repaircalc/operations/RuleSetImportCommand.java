package ru.repaircalc.operations;

import java.nio.file.Path;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import ru.repaircalc.rules.RuleSetImportException;
import ru.repaircalc.rules.RuleSetImportService;

@Component
@ConditionalOnProperty(name = "repaircalc.command", havingValue = "import-rules")
public class RuleSetImportCommand implements ApplicationRunner {
    private final RuleSetImportService importer;
    private final Environment environment;

    public RuleSetImportCommand(RuleSetImportService importer, Environment environment) {
        this.importer = importer;
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        String file = environment.getProperty("repaircalc.rules.file");
        if (file == null || file.isBlank()) {
            throw new RuleSetImportException("Set --repaircalc.rules.file.");
        }
        var result = importer.importFile(Path.of(file));
        System.out.printf("Rules %s: version=%s rules=%s catalog=%s fingerprint=%s%n",
                result.noOp() ? "unchanged" : "published", result.version(), result.ruleSetId(),
                result.catalogReleaseId(), result.fingerprint());
    }
}
