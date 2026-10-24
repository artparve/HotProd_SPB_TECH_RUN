package ru.repaircalc.calculation.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

import ru.repaircalc.rules.RuleSetBundle.LayerPolicy;
import ru.repaircalc.rules.RuleSetBundle.Profile;
import ru.repaircalc.rules.RuleSetBundle.ReservePolicy;
import ru.repaircalc.rules.RuleSetBundle.Strategy;

public final class MaterialCalculator {
    private static final BigDecimal ADHESIVE_RATE = new BigDecimal("4");
    private static final BigDecimal WALLPAPER_TRIM = new BigDecimal("0.10");

    public CalculationResult calculate(CalculationProduct product, CalculationInput input,
                                       Profile profile, String sourceDescription) {
        validate(product, input, profile, sourceDescription);
        Money price = new Money(product.price());
        BigDecimal factor = profile.reservePolicy() == ReservePolicy.NONE
                ? BigDecimal.ONE : Reserve.factor(input.reserve());
        if (profile.strategy() == Strategy.WALLPAPER) {
            return wallpaper(product, input, profile, sourceDescription, factor, price);
        }

        BigDecimal need;
        String formula;
        String unit = product.unit();
        switch (profile.strategy()) {
            case COVERING -> {
                need = input.area().multiply(factor);
                formula = plain(input.area()) + " м² × " + plain(factor) + " = " + plain(need) + " м²";
            }
            case PAINT -> {
                need = input.area().multiply(profile.consumption()).multiply(input.layers()).multiply(factor);
                formula = plain(input.area()) + " м² × " + plain(profile.consumption()) + " " + unit
                        + "/м²/слой × " + plain(input.layers()) + " сл. × " + plain(factor)
                        + " = " + plain(need) + " " + unit;
            }
            case PRIMER, ADHESIVE -> {
                need = input.area().multiply(profile.consumption()).multiply(factor);
                formula = plain(input.area()) + " м² × " + plain(profile.consumption()) + " " + unit
                        + "/м² × " + plain(factor) + " = " + plain(need) + " " + unit;
            }
            case SOCKET -> {
                if (!whole(input.quantity())) {
                    throw CalculationException.needsClarification("The socket count must be a whole number.", "quantity");
                }
                need = input.quantity();
                formula = plain(need) + " шт. (без процентного запаса)";
            }
            case CABLE -> {
                need = input.quantity().multiply(factor);
                formula = plain(input.quantity()) + " м × " + plain(factor) + " = " + plain(need) + " м";
            }
            default -> throw CalculationException.needsClarification("The calculation profile is not supported.", "profile");
        }
        PackageResult packages = Packaging.calculate(need, product.packageSize(), price);
        formula += "; ceil(" + plain(need) + " / " + plain(product.packageSize()) + ") = "
                + packages.quantity() + " " + product.saleUnit() + "; куплено " + plain(packages.purchased())
                + " " + unit + "; остаток " + plain(packages.remainder()) + " " + unit;
        return new CalculationResult(packages, formula, reason(profile, sourceDescription, input.reserve()));
    }

    private static CalculationResult wallpaper(CalculationProduct product, CalculationInput input,
            Profile profile, String sourceDescription, BigDecimal factor, Money price) {
        positive(product.rollWidth(), "rollWidth");
        positive(product.rollLength(), "rollLength");
        if (product.rapport() == null || product.rapport().signum() < 0) {
            throw CalculationException.needsClarification("The wallpaper rapport must be known and nonnegative.", "rapport");
        }
        BigDecimal strip = input.height().add(profile.trimAllowance());
        if (product.rapport().signum() > 0) {
            strip = strip.divide(product.rapport(), 0, RoundingMode.CEILING).multiply(product.rapport());
        }
        BigDecimal perRoll = product.rollLength().divide(strip, 0, RoundingMode.FLOOR);
        if (perRoll.signum() < 1) {
            throw CalculationException.needsClarification(
                    "The strip length with trim and pattern matching exceeds the roll length.", "height", "rollLength", "rapport");
        }
        BigDecimal need = input.perimeter().multiply(factor).divide(product.rollWidth(), 0, RoundingMode.CEILING);
        PackageResult packages = Packaging.calculate(need, perRoll, price);
        String formula = "ceil(" + plain(input.perimeter()) + " м × " + plain(factor) + " / "
                + plain(product.rollWidth()) + " м) = " + plain(need) + " полотен; длина полотна с подгоном "
                + plain(strip) + " м; " + plain(perRoll) + " полотен/рул.; ceil(" + plain(need) + " / "
                + plain(perRoll) + ") = " + packages.quantity() + " рул.; куплено " + plain(packages.purchased())
                + " полотен; остаток " + plain(packages.remainder()) + " полотен. Проёмы не вычитаются.";
        return new CalculationResult(packages, formula, reason(profile, sourceDescription, input.reserve()));
    }

    private static void validate(CalculationProduct product, CalculationInput input,
                                 Profile profile, String sourceDescription) {
        if (product == null || product.id() == null) {
            throw CalculationException.needsClarification("The product identifier must be known.", "id");
        }
        if (input == null) {
            throw CalculationException.needsClarification("The calculation input must be provided.");
        }
        if (profile == null || profile.strategy() == null || blank(profile.id()) || blank(profile.sourceId())
                || blank(profile.conditions()) || blank(sourceDescription)) {
            throw CalculationException.needsClarification("An explicit, sourced calculation profile is required.", "profile");
        }
        if (!profile.strategy().name().equalsIgnoreCase(product.kind())) {
            throw CalculationException.needsClarification("The calculation profile does not match the product kind.", "kind");
        }
        Unit expected = switch (profile.strategy()) {
            case COVERING -> Unit.SQUARE_METRE;
            case PAINT, PRIMER -> Unit.LITRE;
            case ADHESIVE -> Unit.KILOGRAM;
            case WALLPAPER -> Unit.STRIP;
            case SOCKET -> Unit.PIECE;
            case CABLE -> Unit.METRE;
        };
        if (Unit.fromLabel(product.unit()) != expected || Unit.fromLabel(profile.unit()) != expected) {
            throw CalculationException.needsClarification("The product unit does not match its calculation profile.", "unit");
        }
        if (blank(product.saleUnit())) {
            throw CalculationException.needsClarification("The sale unit must be known.", "saleUnit");
        }
        ReservePolicy reserve = profile.strategy() == Strategy.SOCKET ? ReservePolicy.NONE : ReservePolicy.USER_PERCENT;
        LayerPolicy layers = profile.strategy() == Strategy.PAINT ? LayerPolicy.INPUT : LayerPolicy.ONE;
        if (profile.reservePolicy() != reserve || profile.layerPolicy() != layers) {
            throw CalculationException.needsClarification("The calculation policies contradict the selected strategy.", "profile");
        }
        boolean consumptionRequired = switch (profile.strategy()) {
            case PAINT, PRIMER, ADHESIVE -> true;
            default -> false;
        };
        if (consumptionRequired) {
            positive(profile.consumption(), "consumption");
            if (product.consumption() != null && profile.consumption().compareTo(product.consumption()) != 0) {
                throw CalculationException.needsClarification("The profile norm conflicts with the catalog value.", "consumption");
            }
        } else if (profile.consumption() != null) {
            throw CalculationException.needsClarification("The selected strategy does not use a consumption norm.", "profile");
        }
        if (profile.strategy() == Strategy.ADHESIVE && profile.consumption().compareTo(ADHESIVE_RATE) != 0) {
            throw CalculationException.needsClarification("Only the fixed educational adhesive norm of 4 kg/m² is supported.", "consumption");
        }
        if (profile.strategy() == Strategy.WALLPAPER) {
            if (profile.trimAllowance() == null || profile.trimAllowance().compareTo(WALLPAPER_TRIM) != 0) {
                throw CalculationException.needsClarification("The supported wallpaper trim allowance is 0.10 m.", "profile");
            }
            if (product.packageSize() != null) {
                throw CalculationException.needsClarification("Wallpaper packaging must be derived from roll geometry.", "packageSize");
            }
        } else {
            if (profile.trimAllowance() != null) {
                throw CalculationException.needsClarification("Trim allowance applies only to wallpaper.", "profile");
            }
            positive(product.packageSize(), "packageSize");
            if (profile.strategy() == Strategy.SOCKET && !whole(product.packageSize())) {
                throw CalculationException.needsClarification("Socket packaging must contain a whole number of pieces.", "packageSize");
            }
        }
    }

    private static String reason(Profile profile, String sourceDescription, BigDecimal reserve) {
        String reserveReason = profile.reservePolicy() == ReservePolicy.NONE
                ? "Процентный запас к количеству розеток не применяется."
                : "Запас " + plain(reserve) + "% задан пользователем для подрезки и потерь.";
        return profile.conditions() + " Профиль: " + profile.id() + ". Источник " + profile.sourceId()
                + ": " + sourceDescription + " " + reserveReason;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean whole(BigDecimal value) {
        return value.stripTrailingZeros().scale() <= 0;
    }

    private static void positive(BigDecimal value, String field) {
        if (value == null || value.signum() <= 0) {
            throw CalculationException.needsClarification("A positive " + field + " must be known.", field);
        }
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}
