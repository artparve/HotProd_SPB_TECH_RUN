package ru.repaircalc.operations;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/** Keeps readiness closed until catalog and rule publication are implemented. */
@Component("calculationData")
public class CalculationDataHealthIndicator implements HealthIndicator {
    @Override
    public Health health() {
        return Health.outOfService()
                .withDetail("reason", "catalog_and_rules_not_published")
                .build();
    }
}
