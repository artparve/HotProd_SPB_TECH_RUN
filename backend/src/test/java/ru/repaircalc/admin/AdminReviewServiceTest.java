package ru.repaircalc.admin;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class AdminReviewServiceTest {
  @Test
  void broadClassifierWorkDoesNotMapWithoutSurfaceAndKindContext() throws Exception {
    var json = JsonMapper.builder().build();
    var service = new AdminReviewService(json, null, null, "../data/classification/taxonomy.json");
    var c = json.readTree("{\"work_types\":[\"paint\"],\"surface_locations\":[\"facade\"]}");
    assertThat(service.mapWorks(c, "paint")).isEmpty();
    var wall = json.readTree("{\"work_types\":[\"paint\"],\"surface_locations\":[\"wall\"]}");
    assertThat(service.mapWorks(wall, "paint")).containsExactly("paint");
    assertThat(service.mapWorks(wall, "primer")).isEmpty();
    assertThat(
            service.mapWorks(json.readTree("{\"work_types\":[\"tile\",\"priming\"]}"), "adhesive"))
        .isEmpty();
  }

  @Test
  void correctionsAreClosedVocabularyAndLeaveModelOutputUntouched() throws Exception {
    var json = JsonMapper.builder().build();
    var service = new AdminReviewService(json, null, null, "../data/classification/taxonomy.json");
    var c =
        json.readTree(
            "{\"work_types\":[{\"label\":\"paint\"}],\"surface_locations\":[],\"substrates\":[],\"properties\":{}}");
    var original = c.deepCopy();
    var decisions =
        json.readTree(
            "[{\"group\":\"work_types\",\"label\":\"paint\",\"action\":\"correct\",\"value\":\"wallpaper\",\"reason\":\"Проверено"
                + " по этикетке\"}]");
    assertThat(service.validateDecisions(c, decisions).path("work_types").get(0).asText())
        .isEqualTo("wallpaper");
    assertThat(c).isEqualTo(original);
    var invalid = json.readTree(decisions.toString().replace("wallpaper", "invented"));
    assertThatThrownBy(() -> service.validateDecisions(c, invalid))
        .isInstanceOf(AdminException.class);
  }
}
