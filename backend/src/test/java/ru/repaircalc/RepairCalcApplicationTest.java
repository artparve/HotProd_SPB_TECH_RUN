package ru.repaircalc;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import ru.repaircalc.ingestion.CatalogImportService;

@SpringBootTest
@ActiveProfiles("test")
class RepairCalcApplicationTest {
    @MockitoBean
    private CatalogImportService importer;

    @Test
    void startsApplicationContext() {
    }
}
