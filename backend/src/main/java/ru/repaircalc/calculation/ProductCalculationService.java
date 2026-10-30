package ru.repaircalc.calculation;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import ru.repaircalc.calculation.domain.CalculationException;
import ru.repaircalc.calculation.domain.CalculationInput;
import ru.repaircalc.calculation.domain.CalculationProduct;
import ru.repaircalc.calculation.domain.MaterialCalculator;
import ru.repaircalc.common.web.ProductNotFoundException;

@Service
public class ProductCalculationService {
  private final CalculationSnapshotService snapshots;
  private final MaterialCalculator calculator;
  private final Clock clock;

  public ProductCalculationService(
      CalculationSnapshotService snapshots, MaterialCalculator calculator, Clock clock) {
    this.snapshots = snapshots;
    this.calculator = calculator;
    this.clock = clock;
  }

  public ProductEstimate calculate(String id, CalculationInput input) {
    UUID productId;
    try {
      productId = UUID.fromString(id);
    } catch (IllegalArgumentException exception) {
      throw new ProductNotFoundException();
    }
    var snapshot = snapshots.resolve(productId);
    var product =
        snapshot.catalog().products().stream()
            .filter(row -> row.id().equals(productId))
            .findFirst()
            .orElseThrow(ProductNotFoundException::new);
    var bundle = snapshot.rules().bundle();
    var assignment =
        bundle.assignments().stream()
            .filter(row -> row.productId().equals(productId))
            .findFirst()
            .orElseThrow(
                () ->
                    CalculationException.needsClarification(
                        "No calculation profile is assigned.", "productId"));
    if (assignment.blockedReason() != null) {
      throw CalculationException.needsClarification(assignment.blockedReason(), "consumption");
    }
    var profile =
        bundle.profiles().stream()
            .filter(row -> row.id().equals(assignment.profileId()))
            .findFirst()
            .orElseThrow(
                () ->
                    CalculationException.needsClarification(
                        "The calculation profile is unavailable.", "productId"));
    String source =
        bundle.sources().stream()
            .filter(row -> row.id().equals(profile.sourceId()))
            .findFirst()
            .orElseThrow()
            .description();
    var value =
        new CalculationProduct(
            product.id(),
            product.kind(),
            new BigDecimal(product.price()),
            product.packageSize(),
            product.unit(),
            product.saleUnit(),
            product.consumption(),
            product.rollWidth(),
            product.rollLength(),
            product.rapport());
    var result = calculator.calculate(value, input, profile, source);
    var amounts = result.packages();
    String cost = amounts.total().amount().toPlainString();
    var line =
        new ProductEstimate.Line(
            "product:" + productId,
            "product",
            "Расчёт товара",
            product,
            amounts.quantity(),
            amounts.need(),
            amounts.purchased(),
            amounts.remainder(),
            cost,
            result.formula(),
            result.reason());
    return new ProductEstimate(
        UUID.randomUUID(),
        clock.instant(),
        "api",
        List.of(line),
        cost,
        "0.00",
        cost,
        List.of(
            "ТЕСТОВЫЕ товары, цены, нормы и тарифы. Это не коммерческая смета и не результат ML.",
            "Работы и проверка пригодности материала для конкретного объекта не включены.",
            profile.conditions()),
        new ProductEstimate.Versions(
            snapshot.catalog().version(), snapshot.rules().version(), "not-used"));
  }
}
