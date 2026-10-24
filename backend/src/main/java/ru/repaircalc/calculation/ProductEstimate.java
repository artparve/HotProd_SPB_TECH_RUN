package ru.repaircalc.calculation;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import ru.repaircalc.catalog.ProductView;

public record ProductEstimate(UUID id, Instant createdAt, String mode, List<Line> lines,
                              String materials, String labor, String total, List<String> warnings, Versions versions) {
    public ProductEstimate {
        lines = List.copyOf(lines);
        warnings = List.copyOf(warnings);
    }
    public record Line(String id, String roomId, String roomName, ProductView product, long quantity,
                       BigDecimal need, BigDecimal purchased, BigDecimal remainder, String total,
                       String formula, String reason) { }
    public record Versions(String catalog, String rules, String model) { }
}
