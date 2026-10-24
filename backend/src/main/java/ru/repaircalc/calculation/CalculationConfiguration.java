package ru.repaircalc.calculation;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.repaircalc.calculation.domain.MaterialCalculator;

@Configuration(proxyBeanMethods = false)
public class CalculationConfiguration {
    @Bean Clock calculationClock() { return Clock.systemUTC(); }
    @Bean MaterialCalculator materialCalculator() { return new MaterialCalculator(); }
}
