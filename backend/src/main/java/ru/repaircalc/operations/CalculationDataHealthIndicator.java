package ru.repaircalc.operations;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/** Keeps calculation readiness closed until rule publication is implemented. */
@Component("calculationData")
public class CalculationDataHealthIndicator implements HealthIndicator {
    @Override
    public Health health() {
        return Health.outOfService()
                .withDetail("reason", "calculation_rules_not_published")
                .build();
    }
}
