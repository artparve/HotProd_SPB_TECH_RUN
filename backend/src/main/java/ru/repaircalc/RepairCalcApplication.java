package ru.repaircalc;

import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@org.springframework.scheduling.annotation.EnableScheduling
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
        if (commands == null || commands.size() != 1
                || !("import-catalog".equals(commands.getFirst()) || "import-rules".equals(commands.getFirst()) || "import-inspirations".equals(commands.getFirst()))) {
            System.err.println("Supported operator commands: --repaircalc.command=import-catalog|import-rules|import-inspirations");
            System.exit(2);
            return;
        }
        application.setWebApplicationType(WebApplicationType.NONE);
        int exitCode;
        try (var context = application.run(args)) {
            exitCode = SpringApplication.exit(context);
        } catch (Exception exception) {
            System.err.println("Operator command failed; check the failing phase in the application logs.");
            exitCode = 1;
        }
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }
}
