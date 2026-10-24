package ru.repaircalc.operations;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;
import ru.repaircalc.calculation.CalculationSnapshotService;
import ru.repaircalc.common.web.ServiceUnavailableException;

/** A catalog without its matching published rules is available only to the storefront. */
@Component("calculationData")
public class CalculationDataHealthIndicator implements HealthIndicator {
    private final CalculationSnapshotService snapshots;

    public CalculationDataHealthIndicator(CalculationSnapshotService snapshots) {
        this.snapshots = snapshots;
    }

    @Override
    public Health health() {
        try {
            if (snapshots.resolve() == null) { return Health.outOfService().build(); }
            return Health.up().build();
        } catch (ServiceUnavailableException exception) {
            return Health.outOfService().build();
        } catch (RuntimeException exception) {
            return Health.down().build();
        }
    }
}
