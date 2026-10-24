package ru.repaircalc.calculation.domain;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CalculationInputTest {
    @Test
    void missingFieldsReportEveryRequiredPath() {
        assertThatThrownBy(() -> new CalculationInput(null, null, null, null, null, null))
                .isInstanceOfSatisfying(CalculationException.class,
                        exception -> assertThat(exception.fields())
                                .containsExactly("area", "perimeter", "height", "reserve", "layers", "quantity"));
    }

    @ParameterizedTest
    @CsvSource({
            "0,18,2.7,10,2,6,area",
            "10000.0001,18,2.7,10,2,6,area",
            "20,0,2.7,10,2,6,perimeter",
            "20,1000.0001,2.7,10,2,6,perimeter",
            "20,18,0.99,10,2,6,height",
            "20,18,10.0001,10,2,6,height",
            "20,18,2.7,-0.1,2,6,reserve",
            "20,18,2.7,30.0001,2,6,reserve",
            "20,18,2.7,10,0,6,layers",
            "20,18,2.7,10,5,6,layers",
            "20,18,2.7,10,1.999999999,6,layers",
            "20,18,2.7,10,2,0,quantity",
            "20,18,2.7,10,2,5000.01,quantity"
    })
    void enforcesBoundsWithoutTruncatingFractionalLayers(String area, String perimeter, String height,
            String reserve, String layers, String quantity, String field) {
        assertThatThrownBy(() -> input(area, perimeter, height, reserve, layers, quantity))
                .isInstanceOfSatisfying(CalculationException.class,
                        exception -> assertThat(exception.fields()).containsExactly(field));
    }

    @Test
    void wholeLayersWithDecimalScaleAndContractBoundariesAreAccepted() {
        assertThatCode(() -> input("10000", "1000", "10", "30", "4.000", "5000"))
                .doesNotThrowAnyException();
        assertThatCode(() -> input("0.000001", "0.000001", "1", "0", "1.0", "0.1"))
                .doesNotThrowAnyException();
    }

    private static CalculationInput input(String area, String perimeter, String height,
            String reserve, String layers, String quantity) {
        return new CalculationInput(new BigDecimal(area), new BigDecimal(perimeter), new BigDecimal(height),
                new BigDecimal(reserve), new BigDecimal(layers), new BigDecimal(quantity));
    }
}
