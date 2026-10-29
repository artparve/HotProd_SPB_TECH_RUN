package ru.repaircalc.catalog.web;

import java.util.List;
import java.sql.SQLException;

import org.springframework.dao.DataAccessException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.repaircalc.catalog.CatalogReadService;
import ru.repaircalc.catalog.ProductView;
import ru.repaircalc.common.web.ServiceUnavailableException;

@RestController
@RequestMapping("/api/v1/products")
public class CatalogController {
    private final CatalogReadService catalog;

    public CatalogController(CatalogReadService catalog) {
        this.catalog = catalog;
    }

    @GetMapping
    public List<ProductView> products() {
        try {
            return catalog.listProducts();
        } catch (CannotCreateTransactionException | DataAccessException exception) {
            // Acquisition and completion can fail outside the service's transaction proxy boundary.
            throw new ServiceUnavailableException("catalog_unavailable", CatalogReadService.UNAVAILABLE_MESSAGE);
        } catch (TransactionSystemException exception) {
            if (exception.contains(SQLException.class) || exception.contains(DataAccessException.class)) {
                throw new ServiceUnavailableException("catalog_unavailable", CatalogReadService.UNAVAILABLE_MESSAGE);
            }
            throw exception;
        }
    }
}
