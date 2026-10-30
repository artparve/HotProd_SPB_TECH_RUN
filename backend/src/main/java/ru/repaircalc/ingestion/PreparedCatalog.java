package ru.repaircalc.ingestion;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.JsonNode;

/** Version-one operator input; independent from HTTP response and BSON models. */
public final class PreparedCatalog {
    private PreparedCatalog() { }

    public record Catalog(int schemaVersion, @NotBlank String version, @NotBlank String dataClass,
                          @NotNull List<@NotNull @Valid Product> products) { }

    public record Product(
            @NotNull UUID id, @NotBlank String name, @NotBlank String category, @NotBlank String kind,
            @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 17, fraction = 2) BigDecimal price,
            @DecimalMin(value = "0", inclusive = false) @Digits(integer = 16, fraction = 8) BigDecimal packageSize,
            @NotBlank String unit, @NotBlank String saleUnit,
            Boolean wet, Boolean highLoad, Boolean unheated,
            @NotNull String description, @NotBlank String source,
            @DecimalMin(value = "0", inclusive = false) @Digits(integer = 16, fraction = 8) BigDecimal consumption,
            @DecimalMin(value = "0", inclusive = false) @Digits(integer = 16, fraction = 8) BigDecimal rollWidth,
            @DecimalMin(value = "0", inclusive = false) @Digits(integer = 16, fraction = 8) BigDecimal rollLength,
            @DecimalMin("0") @Digits(integer = 16, fraction = 8) BigDecimal rapport,
            @NotEmpty List<@NotNull @Valid Source> sources, String materialFamily) { }

    public record Source(@NotBlank String namespace, @NotBlank String externalId, @NotNull JsonNode raw) { }

    public record Mappings(int schemaVersion, @NotBlank String version, @NotBlank String taxonomyVersion,
                           @NotNull List<@NotNull @Valid Mapping> mappings) { }

    public record Mapping(@NotNull UUID productId, @NotNull List<@NotBlank String> works,
                          @NotBlank String evidence) { }
}
