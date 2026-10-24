package ru.repaircalc.calculation.domain;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import ru.repaircalc.rules.RuleSetBundle.LayerPolicy;
import ru.repaircalc.rules.RuleSetBundle.Profile;
import ru.repaircalc.rules.RuleSetBundle.ReservePolicy;
import ru.repaircalc.rules.RuleSetBundle.Strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MaterialCalculatorTest {
    private static final String SOURCE = "ТЕСТОВЫЕ учебные нормы, не данные производителя.";
    private final MaterialCalculator calculator = new MaterialCalculator();

    @Test
    void coveringUsesAreaReserveAndWholePackages() {
        var result = calculate(product("covering", "2160", "1.44", null), input("20", "10", "2", "6"),
                profile(Strategy.COVERING, null));

        assertResult(result, 16, "22", "23.04", "1.04", "34560.00");
        assertThat(result.formula()).contains("20 м²", "1.1", "16 уп.");
        assertThat(result.reason()).contains("test-covering", "test-source", SOURCE, "Запас 10%");
    }

    @Test
    void paintAppliesThePerCoatNormAndLayersExactlyOnce() {
        var result = calculate(product("paint", "2490", "5", "0.1200"), input("12.5", "7.5", "3", "6"),
                profile(Strategy.PAINT, "0.12"));

        assertResult(result, 1, "4.8375", "5", "0.1625", "2490.00");
        assertThat(result.formula()).contains("0.12 л/м²/слой", "3 сл.");
    }

    @Test
    void explicitSourcedProfileNormCanSupplyAnUnknownCatalogConsumption() {
        var result = calculate(product("paint", "2490", "5", null), input("12.5", "7.5", "3", "6"),
                profile(Strategy.PAINT, "0.12"));

        assertResult(result, 1, "4.8375", "5", "0.1625", "2490.00");
    }

    @Test
    void primerUsesOneApplicationRegardlessOfPaintLayerInput() {
        var product = product("primer", "690", "5", "0.1");
        var profile = profile(Strategy.PRIMER, "0.1");

        assertResult(calculate(product, input("12.5", "7.5", "1", "6"), profile),
                1, "1.34375", "5", "3.65625", "690.00");
        assertResult(calculate(product, input("12.5", "7.5", "4", "6"), profile),
                1, "1.34375", "5", "3.65625", "690.00");
    }

    @Test
    void adhesiveUsesTheFixedEducationalProfileAndExposesItsAssumptions() {
        var product = product("adhesive", "790", "25", "4");
        var profile = profile(Strategy.ADHESIVE, "4");
        var result = calculate(product, input("18.75", "12.5", "4", "6"), profile);

        assertResult(result, 4, "84.375", "100", "15.625", "3160.00");
        assertThat(result.reason()).contains("условное ровное основание", "толщина и технология не проверены", SOURCE);
        assertResult(calculate(product, input("18.75", "12.5", "1", "6"), profile),
                4, "84.375", "100", "15.625", "3160.00");
    }

    @Test
    void cableAcceptsAFractionalEnteredLengthAndKeepsTheReserve() {
        var result = calculate(product("cable", "5490", "50", null), input("20", "12.5", "2", "70.5"),
                profile(Strategy.CABLE, null));

        assertResult(result, 2, "79.3125", "100", "20.6875", "10980.00");
    }

    @Test
    void socketsAcceptWholeDecimalValuesAndIgnoreTheReserve() {
        var result = calculate(product("socket", "349", "1", null), input("20", "30", "4", "6.0"),
                profile(Strategy.SOCKET, null));

        assertResult(result, 6, "6", "6", "0", "2094.00");
        assertThat(result.reason()).contains("Процентный запас к количеству розеток не применяется")
                .doesNotContain("Запас 30%");
    }

    @Test
    void fractionalSocketsAreRejectedInsteadOfRoundedUp() {
        assertField("quantity", () -> calculate(product("socket", "349", "1", null),
                input("20", "10", "2", "6.5"), profile(Strategy.SOCKET, null)));
    }

    @Test
    void socketPackagingMustContainWholePieces() {
        assertField("packageSize", () -> calculate(product("socket", "349", "1.5", null),
                input("20", "10", "2", "6"), profile(Strategy.SOCKET, null)));
    }

    @Test
    void wallpaperWithZeroRapportNeedsNoPatternRounding() {
        var result = calculate(wallpaper("0", "10.05"), wallpaperInput("8.48", "2.4", "0"),
                profile(Strategy.WALLPAPER, null));

        assertResult(result, 2, "8", "8", "0", "3780.00");
        assertThat(result.formula()).contains("2.5 м", "4 полотен/рул.", "Проёмы не вычитаются");
    }

    @Test
    void wallpaperPatternRoundingChangesTheNumberOfWholeStripsPerRoll() {
        var result = calculate(wallpaper("0.32", "10.05"), wallpaperInput("8.48", "2.4", "0"),
                profile(Strategy.WALLPAPER, null));

        assertResult(result, 3, "8", "9", "1", "5670.00");
        assertThat(result.formula()).contains("2.56 м", "3 полотен/рул.");
    }

    @Test
    void wallpaperAppliesReserveBeforeRoundingStripsAndRolls() {
        var result = calculate(wallpaper("0.32", "10.05"), wallpaperInput("2.2", "2.4", "10"),
                profile(Strategy.WALLPAPER, null));

        assertResult(result, 1, "3", "3", "0", "1890.00");
    }

    @Test
    void wallpaperRejectsUnknownRapportAndStripsLongerThanARoll() {
        assertField("rapport", () -> calculate(wallpaper(null, "10.05"), wallpaperInput("18", "2.7", "10"),
                profile(Strategy.WALLPAPER, null)));
        assertField("rollLength", () -> calculate(wallpaper("0", "10.05"), wallpaperInput("18", "10", "10"),
                profile(Strategy.WALLPAPER, null)));
    }

    @Test
    void wallpaperDoesNotInventAConstantPackageSize() {
        var product = new CalculationProduct(UUID.randomUUID(), "wallpaper", decimal("1890"), BigDecimal.ONE,
                "полотен", "рул.", null, decimal("1.06"), decimal("10.05"), decimal("0.32"));
        assertField("packageSize", () -> calculate(product, wallpaperInput("18", "2.7", "10"),
                profile(Strategy.WALLPAPER, null)));
    }

    @Test
    void noUnknownProductReceivesAGuessedProfileOrNorm() {
        assertField("profile", () -> calculate(product("paint", "790", "0.9", null),
                input("20", "10", "2", "6"), null));
        assertField("consumption", () -> calculate(product("paint", "2490", "5", null),
                input("20", "10", "2", "6"), profile(Strategy.PAINT, null)));
    }

    @Test
    void catalogAndProfileConsumptionCannotContradictEachOther() {
        assertField("consumption", () -> calculate(product("paint", "2490", "5", "0.14"),
                input("20", "10", "2", "6"), profile(Strategy.PAINT, "0.12")));
    }

    @Test
    void otherAdhesiveNormsAreNotSilentlyAccepted() {
        assertField("consumption", () -> calculate(product("adhesive", "790", "25", null),
                input("20", "10", "2", "6"), profile(Strategy.ADHESIVE, "3")));
    }

    @Test
    void unknownUnitsAndMismatchedStrategiesAreRejected() {
        var product = new CalculationProduct(UUID.randomUUID(), "paint", decimal("2490"), decimal("5"),
                "unknown", "бан.", decimal("0.12"), null, null, null);
        assertField("unit", () -> calculate(product, input("20", "10", "2", "6"), profile(Strategy.PAINT, "0.12")));
        assertField("kind", () -> calculate(product("covering", "2160", "1.44", null),
                input("20", "10", "2", "6"), profile(Strategy.PAINT, "0.12")));
    }

    @Test
    void unsupportedPoliciesAndMissingSourcesAreRejected() {
        var product = product("socket", "349", "1", null);
        var wrongReserve = new Profile("socket-test", Strategy.SOCKET, "шт.", null, null,
                ReservePolicy.USER_PERCENT, LayerPolicy.ONE, "test-source", "ТЕСТОВЫЙ профиль");
        assertField("profile", () -> calculate(product, input("20", "10", "2", "6"), wrongReserve));
        assertField("profile", () -> calculator.calculate(product, input("20", "10", "2", "6"),
                profile(Strategy.SOCKET, null), ""));
    }

    @Test
    void unknownPackagingAndNonpositivePricesAreRejected() {
        assertField("packageSize", () -> calculate(product("covering", "2160", null, null),
                input("20", "10", "2", "6"), profile(Strategy.COVERING, null)));
        assertField("price", () -> calculate(product("covering", "0", "1.44", null),
                input("20", "10", "2", "6"), profile(Strategy.COVERING, null)));
    }

    @Test
    void arithmeticNeverRoundsNeedOrRemainderToFourDecimalPlaces() {
        var result = calculate(product("paint", "2490", "5", null), input("0.1234567891", "7.5", "3", "6"),
                profile(Strategy.PAINT, "0.12"));

        assertResult(result, 1, "0.0477777773817", "5", "4.9522222226183", "2490.00");
    }

    private CalculationResult calculate(CalculationProduct product, CalculationInput input, Profile profile) {
        return calculator.calculate(product, input, profile, SOURCE);
    }

    private static void assertResult(CalculationResult result, long quantity, String need,
            String purchased, String remainder, String total) {
        assertThat(result.packages().quantity()).isEqualTo(quantity);
        assertThat(result.packages().need()).isEqualByComparingTo(need);
        assertThat(result.packages().purchased()).isEqualByComparingTo(purchased);
        assertThat(result.packages().remainder()).isEqualByComparingTo(remainder);
        assertThat(result.packages().total().amount().toPlainString()).isEqualTo(total);
    }

    private static void assertField(String field, org.assertj.core.api.ThrowableAssert.ThrowingCallable operation) {
        assertThatThrownBy(operation).isInstanceOfSatisfying(CalculationException.class, exception -> {
            assertThat(exception.code()).isEqualTo("needs_clarification");
            assertThat(exception.fields()).contains(field);
        });
    }

    private static CalculationProduct product(String kind, String price, String packageSize, String consumption) {
        Strategy strategy = Strategy.valueOf(kind.toUpperCase(Locale.ROOT));
        String saleUnit = switch (strategy) {
            case COVERING -> "уп.";
            case PAINT, PRIMER -> "бан.";
            case ADHESIVE -> "меш.";
            case SOCKET -> "шт.";
            case CABLE -> "бух.";
            case WALLPAPER -> "рул.";
        };
        return new CalculationProduct(UUID.randomUUID(), kind, decimal(price), decimal(packageSize),
                unit(strategy), saleUnit, decimal(consumption), null, null, null);
    }

    private static CalculationProduct wallpaper(String rapport, String rollLength) {
        return new CalculationProduct(UUID.randomUUID(), "wallpaper", decimal("1890"), null,
                "полотен", "рул.", null, decimal("1.06"), decimal(rollLength), decimal(rapport));
    }

    private static CalculationInput input(String area, String reserve, String layers, String quantity) {
        return new CalculationInput(decimal(area), decimal("18"), decimal("2.7"),
                decimal(reserve), decimal(layers), decimal(quantity));
    }

    private static CalculationInput wallpaperInput(String perimeter, String height, String reserve) {
        return new CalculationInput(decimal("20"), decimal(perimeter), decimal(height),
                decimal(reserve), decimal("2"), decimal("6"));
    }

    private static Profile profile(Strategy strategy, String consumption) {
        String conditions = strategy == Strategy.ADHESIVE
                ? "ТЕСТОВЫЙ профиль: условное ровное основание; толщина и технология не проверены."
                : "ТЕСТОВЫЙ профиль " + strategy.name();
        return new Profile("test-" + strategy.name().toLowerCase(Locale.ROOT), strategy, unit(strategy),
                decimal(consumption), strategy == Strategy.WALLPAPER ? decimal("0.10") : null,
                strategy == Strategy.SOCKET ? ReservePolicy.NONE : ReservePolicy.USER_PERCENT,
                strategy == Strategy.PAINT ? LayerPolicy.INPUT : LayerPolicy.ONE, "test-source", conditions);
    }

    private static String unit(Strategy strategy) {
        return switch (strategy) {
            case COVERING -> "м²";
            case PAINT, PRIMER -> "л";
            case ADHESIVE -> "кг";
            case WALLPAPER -> "полотен";
            case SOCKET -> "шт.";
            case CABLE -> "м";
        };
    }

    private static BigDecimal decimal(String value) {
        return value == null ? null : new BigDecimal(value);
    }
}
