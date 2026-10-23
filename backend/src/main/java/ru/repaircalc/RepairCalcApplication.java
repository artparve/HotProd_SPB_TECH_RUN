package ru.repaircalc;

import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class RepairCalcApplication {
    public static void main(String[] args) {
        var options = new DefaultApplicationArguments(args);
        var application = new SpringApplication(RepairCalcApplication.class);
        if (!options.containsOption("repaircalc.command")) {
            application.run(args);
            return;
        }
        var commands = options.getOptionValues("repaircalc.command");
        if (commands == null || commands.size() != 1 || !"import-catalog".equals(commands.getFirst())) {
            System.err.println("Supported operator command: --repaircalc.command=import-catalog");
            System.exit(2);
            return;
        }
        application.setWebApplicationType(WebApplicationType.NONE);
        int exitCode;
        try (var context = application.run(args)) {
            exitCode = SpringApplication.exit(context);
        } catch (Exception exception) {
            System.err.println("Catalog import failed; no release was published by this attempt.");
            exitCode = 1;
        }
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }
}
