package ru.repaircalc.calculation.domain;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReserveTest {
    @Test
    void usesTheRequestedPercentWithoutAHiddenDefaultOrDecimalRounding() {
        assertThat(Reserve.factor(new BigDecimal("0"))).isEqualByComparingTo("1");
        assertThat(Reserve.factor(new BigDecimal("7.5"))).isEqualByComparingTo("1.075");
        assertThat(Reserve.factor(new BigDecimal("30"))).isEqualByComparingTo("1.3");
        assertThat(Reserve.factor(new BigDecimal("0.000000001"))).isEqualByComparingTo("1.00000000001");
    }

    @Test
    void rejectsMissingNegativeAndExcessiveReserve() {
        for (BigDecimal value : new BigDecimal[]{null, new BigDecimal("-0.1"), new BigDecimal("30.00001")}) {
            assertThatThrownBy(() -> Reserve.factor(value)).isInstanceOfSatisfying(CalculationException.class,
                    exception -> assertThat(exception.fields()).containsExactly("reserve"));
        }
    }
}
