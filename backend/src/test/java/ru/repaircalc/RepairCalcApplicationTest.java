package ru.repaircalc;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import ru.repaircalc.ingestion.CatalogImportService;
import ru.repaircalc.catalog.CatalogReadService;
import ru.repaircalc.rules.RuleSetImportService;
import ru.repaircalc.rules.RuleSetReadService;
import ru.repaircalc.calculation.CalculationSnapshotService;

@SpringBootTest
@ActiveProfiles("test")
class RepairCalcApplicationTest {
    @MockitoBean
    private CatalogImportService importer;
    @MockitoBean
    private CatalogReadService catalog;
    @MockitoBean
    private RuleSetImportService ruleImporter;
    @MockitoBean
    private RuleSetReadService rules;
    @MockitoBean
    private CalculationSnapshotService snapshots;

    @Test
    void startsApplicationContext() {
    }
}
