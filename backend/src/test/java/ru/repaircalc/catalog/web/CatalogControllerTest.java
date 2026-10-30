package ru.repaircalc.catalog.web;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ru.repaircalc.catalog.CatalogReadService;

@WebMvcTest(CatalogController.class)
class CatalogControllerTest {
  @Autowired MockMvc mvc;
  @MockitoBean CatalogReadService catalog;

  @Test
  void readsDegradeExplicitly() throws Exception {
    when(catalog.listProducts())
        .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("secret"));
    mvc.perform(get("/api/v1/products"))
        .andExpect(status().isOk())
        .andExpect(content().json("[]"))
        .andExpect(header().string("X-RepairCalc-Degraded", "catalog_unavailable"));
  }

  @Test
  void programmingErrorsRemainErrors() throws Exception {
    when(catalog.listProducts()).thenThrow(new IllegalStateException("secret"));
    mvc.perform(get("/api/v1/products"))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.code").value("internal_error"));
  }
}
