package ru.repaircalc.rules;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** Operator-owned, typed data. Strategy names select Java implementations, never expressions. */
public record RuleSetBundle(
        int schemaVersion,
        @NotBlank String version,
        @NotBlank String dataClass,
        @NotNull @Valid CatalogReference catalog,
        @NotEmpty List<@NotNull @Valid Source> sources,
        @NotEmpty List<@NotNull @Valid Profile> profiles,
        @NotNull List<@NotNull @Valid Assignment> assignments,
        @NotEmpty List<@NotNull @Valid LaborTariff> laborTariffs) {

    public RuleSetBundle {
        sources = sources == null ? null : List.copyOf(sources);
        profiles = profiles == null ? null : List.copyOf(profiles);
        assignments = assignments == null ? null : List.copyOf(assignments);
        laborTariffs = laborTariffs == null ? null : List.copyOf(laborTariffs);
    }

    public record CatalogReference(@NotBlank String version,
                                   @NotNull @Pattern(regexp = "[0-9a-f]{64}") String fingerprint,
                                   @NotBlank String taxonomyVersion) { }
    public record Source(@NotBlank String id, @NotBlank String description) { }
    public enum Strategy { COVERING, PAINT, PRIMER, ADHESIVE, WALLPAPER, SOCKET, CABLE }
    public enum ReservePolicy { USER_PERCENT, NONE }
    public enum LayerPolicy { INPUT, ONE }

    public record Profile(@NotBlank String id, @NotNull Strategy strategy, @NotBlank String unit,
                          @DecimalMin(value = "0", inclusive = false) BigDecimal consumption,
                          @DecimalMin("0") BigDecimal trimAllowance,
                          @NotNull ReservePolicy reservePolicy, @NotNull LayerPolicy layerPolicy,
                          @NotBlank String sourceId, @NotBlank String conditions) { }

    /** Exactly one of profileId and blockedReason is required. */
    public record Assignment(@NotNull UUID productId, String profileId, String blockedReason) { }
    public record LaborTariff(@NotBlank String code, @NotBlank String unit,
                              @NotNull @DecimalMin(value = "0", inclusive = false)
                              @Digits(integer = 17, fraction = 2) BigDecimal amount,
                              @NotBlank String sourceId) { }
}
