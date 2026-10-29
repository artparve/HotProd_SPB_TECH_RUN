package ru.repaircalc.calculation.domain;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GeometryTest {
    @Test
    void calculatesFloorPerimeterAndWallsAfterOpeningsIndependently() {
        var result = Geometry.calculate(decimal("4.25"), decimal("3.2"), decimal("2.65"), decimal("1.75"));

        assertThat(result.area()).isEqualByComparingTo("13.6");
        assertThat(result.perimeter()).isEqualByComparingTo("14.9");
        assertThat(result.walls()).isEqualByComparingTo("37.735");
    }

    @Test
    void retainsSmallDimensionsAndAllDecimalPlaces() {
        var result = Geometry.calculate(decimal("0.1"), decimal("0.2"), decimal("1.000000001"), BigDecimal.ZERO);

        assertThat(result.area()).isEqualByComparingTo("0.02");
        assertThat(result.perimeter()).isEqualByComparingTo("0.6");
        assertThat(result.walls()).isEqualByComparingTo("0.6000000006");
    }

    @Test
    void rejectsMissingZeroNegativeAndOversizedDimensions() {
        for (BigDecimal length : new BigDecimal[]{null, BigDecimal.ZERO, decimal("-1"), decimal("100.0001")}) {
            assertThatThrownBy(() -> Geometry.calculate(length, decimal("4"), decimal("2.7"), BigDecimal.ZERO))
                    .isInstanceOfSatisfying(CalculationException.class,
                            exception -> assertThat(exception.fields()).contains("length"));
        }
        for (BigDecimal height : new BigDecimal[]{null, decimal("0.99"), decimal("10.0001")}) {
            assertThatThrownBy(() -> Geometry.calculate(decimal("5"), decimal("4"), height, BigDecimal.ZERO))
                    .isInstanceOfSatisfying(CalculationException.class,
                            exception -> assertThat(exception.fields()).contains("height"));
        }
    }

    @Test
    void openingsMustLeaveAPositiveWallArea() {
        for (BigDecimal openings : new BigDecimal[]{null, decimal("-1"), decimal("48.6"), decimal("50")}) {
            assertThatThrownBy(() -> Geometry.calculate(decimal("5"), decimal("4"), decimal("2.7"), openings))
                    .isInstanceOfSatisfying(CalculationException.class,
                            exception -> assertThat(exception.fields()).contains("openings"));
        }
    }

    @Test
    void openingAreaAlsoRespectsTheRoomContractLimit() {
        var maximum = Geometry.calculate(decimal("100"), decimal("100"), decimal("10"), decimal("2000"));
        assertThat(maximum.walls()).isEqualByComparingTo("2000");

        // 2500 is smaller than the 4000 m² gross wall area, but exceeds the input contract.
        assertThatThrownBy(() -> Geometry.calculate(decimal("100"), decimal("100"), decimal("10"), decimal("2500")))
                .isInstanceOfSatisfying(CalculationException.class,
                        exception -> assertThat(exception.fields()).containsExactly("openings"));
    }

    private static BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }
}
