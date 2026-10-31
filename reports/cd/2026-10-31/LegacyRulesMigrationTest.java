package ru.repaircalc.rules;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class LegacyRulesMigrationTest {
  @Test
  void migratedRulesSatisfyTheCurrentJavaContract() throws Exception {
    var mapper = JsonMapper.builder().build();
    try (var factory = jakarta.validation.Validation.buildDefaultValidatorFactory()) {
      var validator = new RuleSetValidator(factory.getValidator());
      var rules = mapper.readTree(Files.readString(Path.of("/work/migrated-rules-check.json")));
      for (var rule : rules) {
        var bundle = mapper.treeToValue(rule, RuleSetBundle.class);
        assertDoesNotThrow(() -> validator.validateShape(bundle), bundle.version());
      }
    }
  }
}
