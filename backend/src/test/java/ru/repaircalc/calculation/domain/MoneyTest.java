package ru.repaircalc.calculation.domain;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {
    @Test
    void preservesWholeKopecksWithoutRounding() {
        assertThat(new Money(new BigDecimal("2490")).amount().toPlainString()).isEqualTo("2490.00");
        assertThat(new Money(new BigDecimal("0.10")).amount().toPlainString()).isEqualTo("0.10");
        assertThat(new Money(new BigDecimal("1.000")).amount().toPlainString()).isEqualTo("1.00");
    }

    @Test
    void rejectsUnknownFreeNegativeAndFractionalKopeckPrices() {
        for (BigDecimal amount : new BigDecimal[]{null, BigDecimal.ZERO, new BigDecimal("-1"), new BigDecimal("0.001")}) {
            assertThatThrownBy(() -> new Money(amount)).isInstanceOfSatisfying(CalculationException.class,
                    exception -> assertThat(exception.fields()).contains("price"));
        }
    }
}
