package ru.repaircalc.calculation.domain;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PackagingTest {
    @Test
    void buysWholePackagesAndKeepsTheExactRemainderAndCost() {
        var result = Packaging.calculate(decimal("22"), decimal("1.44"), new Money(decimal("2160")));

        assertThat(result.quantity()).isEqualTo(16);
        assertThat(result.need()).isEqualByComparingTo("22");
        assertThat(result.purchased()).isEqualByComparingTo("23.04");
        assertThat(result.remainder()).isEqualByComparingTo("1.04");
        assertThat(result.total().amount().toPlainString()).isEqualTo("34560.00");
    }

    @Test
    void decimalBoundaryDoesNotCreateAnExtraPackage() {
        var result = Packaging.calculate(decimal("0.3"), decimal("0.1"), new Money(decimal("0.10")));

        assertThat(result.quantity()).isEqualTo(3);
        assertThat(result.purchased()).isEqualByComparingTo("0.3");
        assertThat(result.remainder()).isEqualByComparingTo("0");
        assertThat(result.total().amount().toPlainString()).isEqualTo("0.30");
    }

    @Test
    void doesNotRoundSmallNeedsBeforePackaging() {
        var result = Packaging.calculate(decimal("0.000000000001"), BigDecimal.ONE, new Money(decimal("1")));

        assertThat(result.quantity()).isEqualTo(1);
        assertThat(result.need()).isEqualByComparingTo("0.000000000001");
        assertThat(result.remainder()).isEqualByComparingTo("0.999999999999");
    }

    @Test
    void enforcesTheJavascriptSafeIntegerBoundary() {
        var result = Packaging.calculate(decimal("9007199254740991"), BigDecimal.ONE, new Money(decimal("0.01")));
        assertThat(result.quantity()).isEqualTo(9_007_199_254_740_991L);
        assertThat(result.total().amount().toPlainString()).isEqualTo("90071992547409.91");

        assertThatThrownBy(() -> Packaging.calculate(decimal("9007199254740992"), BigDecimal.ONE,
                new Money(decimal("0.01")))).isInstanceOfSatisfying(CalculationException.class,
                exception -> assertThat(exception.fields()).contains("quantity"));
    }

    @Test
    void unknownPackagingAndNonpositiveNeedsCannotProduceAnEstimateLine() {
        for (BigDecimal size : new BigDecimal[]{null, BigDecimal.ZERO, decimal("-1")}) {
            assertThatThrownBy(() -> Packaging.calculate(BigDecimal.ONE, size, new Money(decimal("1"))))
                    .isInstanceOfSatisfying(CalculationException.class,
                            exception -> assertThat(exception.fields()).contains("packageSize"));
        }
        for (BigDecimal need : new BigDecimal[]{null, BigDecimal.ZERO, decimal("-1")}) {
            assertThatThrownBy(() -> Packaging.calculate(need, BigDecimal.ONE, new Money(decimal("1"))))
                    .isInstanceOf(CalculationException.class);
        }
    }

    private static BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }
}
