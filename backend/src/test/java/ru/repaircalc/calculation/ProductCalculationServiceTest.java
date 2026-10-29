package ru.repaircalc.calculation;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import ru.repaircalc.calculation.domain.CalculationInput;
import ru.repaircalc.calculation.domain.MaterialCalculator;
import ru.repaircalc.catalog.CatalogSnapshot;
import ru.repaircalc.catalog.ProductView;
import ru.repaircalc.rules.RuleSetBundle;
import ru.repaircalc.rules.RuleSetSnapshot;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ProductCalculationServiceTest {
    @Test
    void buildsOneLineFromOneSnapshotWithAnInjectedUtcClockAndExactTotals() throws Exception {
        var resolver = mock(CalculationSnapshotService.class);
        var bundle = JsonMapper.builder().build().readValue(Files.readString(Path.of("data/rules-v1.json")), RuleSetBundle.class);
        UUID tileId = UUID.fromString("ac1f222a-f5e9-3b5d-a91e-c943b053e92e");
        var tile = new ProductView(tileId, "Тестовая плитка", "Плитка", "covering", "2160.00", new BigDecimal("1.44"),
                "м²", "уп.", List.of("floor"), true, true, true, "Тест", "ТЕСТОВЫЕ данные", null, null, null, null);
        UUID catalogId = UUID.randomUUID();
        var catalog = new CatalogSnapshot(catalogId, bundle.catalog().version(), bundle.catalog().fingerprint(),
                bundle.catalog().taxonomyVersion(), List.of(tile));
        var rules = new RuleSetSnapshot(UUID.randomUUID(), catalogId, bundle.version(), "f".repeat(64), bundle);
        when(resolver.resolve()).thenReturn(new CalculationSnapshotService.Snapshot(catalog, rules));
        Instant now = Instant.parse("2026-10-24T00:00:00Z");
        var service = new ProductCalculationService(resolver, new MaterialCalculator(), Clock.fixed(now, ZoneOffset.UTC));
        var input = new CalculationInput(new BigDecimal("20"), new BigDecimal("18"), new BigDecimal("2.7"),
                new BigDecimal("10"), new BigDecimal("2"), new BigDecimal("6"));

        var estimate = service.calculate(tileId.toString(), input);

        assertThat(estimate.createdAt()).isEqualTo(now);
        assertThat(estimate.id()).isNotNull();
        assertThat(estimate.mode()).isEqualTo("api");
        assertThat(estimate.lines()).hasSize(1);
        assertThat(estimate.lines().getFirst().product()).isSameAs(tile);
        assertThat(estimate.lines().getFirst().quantity()).isEqualTo(16);
        assertThat(estimate.lines().getFirst().roomId()).isEqualTo("product");
        assertThat(estimate.lines().getFirst().roomName()).isEqualTo("Расчёт товара");
        assertThat(estimate.materials()).isEqualTo("34560.00");
        assertThat(estimate.total()).isEqualTo(estimate.materials());
        assertThat(estimate.labor()).isEqualTo("0.00");
        assertThat(estimate.versions().catalog()).isEqualTo(catalog.version());
        assertThat(estimate.versions().rules()).isEqualTo(rules.version());
        assertThat(estimate.versions().model()).isEqualTo("not-used");
        verify(resolver, times(1)).resolve();
    }
}
