package ru.repaircalc.admin;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdminProductValidatorTest {
    private final JsonMapper json = JsonMapper.builder().build();
    private final AdminProductValidator validator = new AdminProductValidator();
    private String wrap(String fields) { return "{\"sourceNamespace\":\"supplier\",\"externalId\":\"42\",\"name\":\"Paint\"," + fields + "}"; }

    @Test
    void preservesUnknownAndFalseAndUsesAuthenticatedAuthor() {
        var result = validator.validate(json.readTree(wrap("\"wet\":{\"state\":\"unknown\"},\"unheated\":{\"state\":\"confirmed\",\"value\":false,\"source\":\"label\",\"confirmedBy\":\"spoofed\"}")), "real-admin");
        assertThat(result.path("wet").path("state").asText()).isEqualTo("unknown");
        assertThat(result.path("wet").has("value")).isFalse();
        assertThat(result.path("unheated").path("value").asBoolean()).isFalse();
        assertThat(result.path("unheated").path("confirmedBy").asText()).isEqualTo("real-admin");
    }

    @Test
    void rejectsInvalidTypesStatesNumbersUnitsAndUnknownKeys() {
        for (var fields : new String[]{
                "\"price\":{\"state\":\"confirmed\",\"value\":1,\"source\":\"label\"}",
                "\"price\":{\"state\":\"confirmed\",\"value\":\"NaN\",\"source\":\"label\"}",
                "\"price\":{\"state\":\"confirmed\",\"value\":\"1.001\",\"source\":\"label\"}",
                "\"price\":{\"state\":\"confirmed\",\"value\":\"1\"}",
                "\"wet\":{\"state\":\"confirmed\",\"value\":\"false\",\"source\":\"label\"}",
                "\"wet\":{\"state\":\"unknown\",\"value\":false}",
                "\"wet\":{\"state\":\"conflict\"}",
                "\"packageSize\":{\"state\":\"confirmed\",\"value\":0,\"source\":\"label\"}",
                "\"rapport\":{\"state\":\"confirmed\",\"value\":-1,\"source\":\"label\"}",
                "\"unit\":{\"state\":\"confirmed\",\"value\":\"invented\",\"source\":\"label\"}",
                "\"currency\":{\"state\":\"confirmed\",\"value\":\"USD\",\"source\":\"label\"}",
                "\"priceDate\":{\"state\":\"confirmed\",\"value\":\"2026-02-30\",\"source\":\"label\"}",
                "\"kind\":{\"state\":\"confirmed\",\"value\":\"invented\",\"source\":\"label\"}",
                "\"attributes\":[{\"name\":\"x\",\"value\":1}]",
                "\"sources\":[{\"url\":\"x\"}]", "\"newField\":false"}) {
            assertThatThrownBy(() -> validator.validate(json.readTree(wrap(fields)), "admin")).as(fields).isInstanceOf(AdminException.class);
        }
        assertThat(validator.validate(json.readTree(wrap("\"rapport\":{\"state\":\"confirmed\",\"value\":0,\"source\":\"label\"}")), "admin").path("rapport").path("value").asInt()).isZero();
        assertThat(validator.validate(json.readTree(wrap("\"saleUnit\":{\"state\":\"confirmed\",\"value\":\"бан.\",\"source\":\"label\"}")), "admin").path("saleUnit").path("value").asText()).isEqualTo("бан.");
    }
}
