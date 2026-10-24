package ru.repaircalc.calculation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.repaircalc.catalog.CatalogReadService;
import ru.repaircalc.catalog.CatalogSnapshot;
import ru.repaircalc.common.web.ServiceUnavailableException;
import ru.repaircalc.rules.RuleSetBundle;
import ru.repaircalc.rules.RuleSetReadService;
import ru.repaircalc.rules.RuleSetSnapshot;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class CalculationSnapshotServiceTest {
    private final CatalogReadService catalogs = mock(CatalogReadService.class);
    private final RuleSetReadService rules = mock(RuleSetReadService.class);
    private final CalculationSnapshotService service = new CalculationSnapshotService(catalogs, rules);
    private CatalogSnapshot catalog;
    private RuleSetSnapshot ruleSet;

    @BeforeEach
    void preparePair() throws Exception {
        var bundle = JsonMapper.builder().build().readValue(Files.readString(Path.of("data/rules-v1.json")), RuleSetBundle.class);
        var reference = bundle.catalog();
        UUID catalogId = UUID.randomUUID();
        catalog = new CatalogSnapshot(catalogId, reference.version(), reference.fingerprint(), reference.taxonomyVersion(), List.of());
        ruleSet = new RuleSetSnapshot(UUID.randomUUID(), catalogId, bundle.version(), "f".repeat(64), bundle);
        when(catalogs.activeSnapshot()).thenReturn(catalog);
    }

    @Test
    void selectsEachVersionExactlyOnceAndRetainsDetachedSnapshots() {
        when(rules.activeForCatalog(catalog.releaseId())).thenReturn(Optional.of(ruleSet));
        var result = service.resolve();
        assertThat(result.catalog()).isSameAs(catalog);
        assertThat(result.rules()).isSameAs(ruleSet);
        verify(catalogs, times(1)).activeSnapshot();
        verify(rules, times(1)).activeForCatalog(catalog.releaseId());
        verifyNoMoreInteractions(catalogs, rules);
    }

    @Test
    void catalogWithoutRulesIsUnavailable() {
        when(rules.activeForCatalog(catalog.releaseId())).thenReturn(Optional.empty());
        assertThatThrownBy(service::resolve).isInstanceOf(ServiceUnavailableException.class)
                .hasMessage("Calculation data is temporarily unavailable.");
    }

    @Test
    void storedRuleReferenceMustMatchTheActualCatalogFingerprint() {
        catalog = new CatalogSnapshot(catalog.releaseId(), catalog.version(), "0".repeat(64), catalog.taxonomyVersion(), List.of());
        when(catalogs.activeSnapshot()).thenReturn(catalog);
        when(rules.activeForCatalog(catalog.releaseId())).thenReturn(Optional.of(ruleSet));
        assertThatThrownBy(service::resolve).isInstanceOf(ServiceUnavailableException.class);
    }

    @Test
    void transactionBoundaryFailuresCannotLeakDatabaseDetails() {
        when(catalogs.activeSnapshot()).thenThrow(new org.springframework.transaction.CannotCreateTransactionException("secret jdbc password"));
        assertThatThrownBy(service::resolve).isInstanceOf(ServiceUnavailableException.class)
                .hasMessage("Calculation data is temporarily unavailable.");
        verifyNoInteractions(rules);
    }

    @Test
    void programmingErrorsAreNotDisguisedAsDependencyFailures() {
        var failure = new IllegalStateException("programming error");
        when(catalogs.activeSnapshot()).thenThrow(failure);
        assertThatThrownBy(service::resolve).isSameAs(failure);
    }
}
