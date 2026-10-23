package ru.repaircalc.catalog.web;

import java.sql.SQLException;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;
import ru.repaircalc.catalog.CatalogReadService;
import ru.repaircalc.common.web.ApiExceptionHandler;
import ru.repaircalc.common.web.RequestIdFilter;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CatalogController.class)
@Import({CatalogController.class, ApiExceptionHandler.class, RequestIdFilter.class})
class CatalogControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean CatalogReadService catalog;

    @Test
    void dependencyFailuresOutsideTheTransactionProxyReturnSafe503() throws Exception {
        for (var failure : new RuntimeException[]{
                new CannotCreateTransactionException("secret"),
                new DataAccessResourceFailureException("secret"),
                new TransactionSystemException("secret", new SQLException("secret"))}) {
            doThrow(failure).when(catalog).listProducts();
            mvc.perform(get("/api/v1/products"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("catalog_unavailable"))
                    .andExpect(content().string(not(containsString("secret"))));
        }
    }

    @Test
    void unexpectedFailuresStillReturn500() throws Exception {
        for (var failure : new RuntimeException[]{new IllegalStateException("secret"),
                new TransactionSystemException("secret", new IllegalStateException("secret"))}) {
            doThrow(failure).when(catalog).listProducts();
            mvc.perform(get("/api/v1/products"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.code").value("internal_error"))
                    .andExpect(content().string(not(containsString("secret"))));
        }
    }
}
