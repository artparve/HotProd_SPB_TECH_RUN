package ru.repaircalc.catalog.web;

import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.springframework.web.bind.annotation.*;
import ru.repaircalc.catalog.*;
import ru.repaircalc.common.web.ServiceUnavailableException;

@RestController
@RequestMapping("/api/v1/products")
public class CatalogController {
  private final CatalogReadService catalog;

  public CatalogController(CatalogReadService catalog) {
    this.catalog = catalog;
  }

  @GetMapping
  public List<ProductView> products(HttpServletResponse response) {
    try {
      return catalog.listProducts();
    } catch (ServiceUnavailableException
        | org.springframework.dao.DataAccessException
        | com.mongodb.MongoException e) {
      response.setHeader("X-RepairCalc-Degraded", "catalog_unavailable");
      response.setHeader("Cache-Control", "no-store");
      return List.of();
    }
  }
}
