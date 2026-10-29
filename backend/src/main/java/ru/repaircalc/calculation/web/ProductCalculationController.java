package ru.repaircalc.calculation.web;

import java.math.BigDecimal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.repaircalc.calculation.ProductCalculationService;
import ru.repaircalc.calculation.ProductEstimate;
import ru.repaircalc.calculation.domain.CalculationInput;

@RestController
@RequestMapping("/api/v1/product")
public class ProductCalculationController {
    private final ProductCalculationService calculations;

    public ProductCalculationController(ProductCalculationService calculations) { this.calculations = calculations; }

    @PostMapping("/{id}/calc")
    public ProductEstimate calculate(@PathVariable String id, @RequestBody @Valid Input body) {
        return calculations.calculate(id, new CalculationInput(body.area(), body.perimeter(), body.height(),
                body.reserve(), body.layers(), body.quantity()));
    }

    public record Input(
            @NotNull @DecimalMin(value = "0", inclusive = false) @DecimalMax("10000") BigDecimal area,
            @NotNull @DecimalMin(value = "0", inclusive = false) @DecimalMax("1000") BigDecimal perimeter,
            @NotNull @DecimalMin("1") @DecimalMax("10") BigDecimal height,
            @NotNull @DecimalMin("0") @DecimalMax("30") BigDecimal reserve,
            @NotNull @DecimalMin("1") @DecimalMax("4") BigDecimal layers,
            @NotNull @DecimalMin(value = "0", inclusive = false) @DecimalMax("5000") BigDecimal quantity) { }
}
