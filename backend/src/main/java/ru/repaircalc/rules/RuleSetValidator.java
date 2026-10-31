package ru.repaircalc.rules;

import jakarta.validation.Validator;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ru.repaircalc.rules.RuleSetBundle.LayerPolicy;
import ru.repaircalc.rules.RuleSetBundle.ReservePolicy;
import ru.repaircalc.rules.RuleSetBundle.Strategy;

@Component
public class RuleSetValidator {
  private static final Map<Strategy, String> UNITS =
      Map.of(
          Strategy.COVERING,
          "м²",
          Strategy.PAINT,
          "л",
          Strategy.PRIMER,
          "л",
          Strategy.ADHESIVE,
          "кг",
          Strategy.WALLPAPER,
          "полотен",
          Strategy.SOCKET,
          "шт.",
          Strategy.CABLE,
          "м");
  private static final Map<String, String> TARIFF_UNITS =
      Map.of("floor", "м²", "paint", "м²", "wallpaper", "м²", "socket", "шт.", "cable", "м");
  private final Validator validator;

  public RuleSetValidator(Validator validator) {
    this.validator = validator;
  }

  public void validateShape(RuleSetBundle bundle) {
    if (bundle == null) {
      reject("Rules must be a JSON object.");
    }
    var violations = validator.validate(bundle);
    if (!violations.isEmpty()) {
      String paths =
          violations.stream()
              .map(v -> v.getPropertyPath().toString())
              .distinct()
              .sorted()
              .collect(java.util.stream.Collectors.joining(", "));
      reject("Invalid rule fields: " + paths);
    }
    if (bundle.schemaVersion() != 1 || !"test".equals(bundle.dataClass())) {
      reject("Only version-one test rules are supported.");
    }
    var sources = new HashSet<String>();
    for (var source : bundle.sources()) {
      if (!sources.add(source.id()) || !source.description().contains("ТЕСТОВЫЕ")) {
        reject("Sources must be unique and explicitly identify test data (ТЕСТОВЫЕ).");
      }
    }
    var profileIds = new HashSet<String>();
    for (var profile : bundle.profiles()) {
      if (!profileIds.add(profile.id()) || !sources.contains(profile.sourceId())) {
        reject("Profiles contain duplicate IDs or unknown sources.");
      }
      if (!UNITS.get(profile.strategy()).equals(profile.unit())) {
        reject("Profile strategy and physical unit do not match.");
      }
      var reserve =
          profile.strategy() == Strategy.SOCKET ? ReservePolicy.NONE : ReservePolicy.USER_PERCENT;
      var layers = profile.strategy() == Strategy.PAINT ? LayerPolicy.INPUT : LayerPolicy.ONE;
      if (profile.reservePolicy() != reserve || profile.layerPolicy() != layers) {
        reject("Reserve or layer policy contradicts the declared strategy.");
      }
      boolean usesConsumption =
          switch (profile.strategy()) {
            case PAINT, PRIMER, ADHESIVE -> true;
            default -> false;
          };
      if (usesConsumption != (profile.consumption() != null)) {
        reject("Consumption is required only for paint, primer and adhesive profiles.");
      }
      if (profile.strategy() == Strategy.WALLPAPER) {
        if (profile.trimAllowance() == null || profile.trimAllowance().signum() < 0) {
          reject("Wallpaper requires a nonnegative trim allowance.");
        }
      } else if (profile.trimAllowance() != null) {
        reject("Trim allowance belongs only to the wallpaper profile.");
      }
    }
    var assigned = new HashSet<UUID>();
    for (var assignment : bundle.assignments()) {
      boolean hasProfile = assignment.profileId() != null && !assignment.profileId().isBlank();
      boolean blocked = assignment.blockedReason() != null && !assignment.blockedReason().isBlank();
      if (assignment.priority() < 0
          || !assigned.add(assignment.productId())
          || hasProfile == blocked
          || (assignment.profileId() != null && !hasProfile)
          || (assignment.blockedReason() != null && !blocked)
          || (hasProfile && !profileIds.contains(assignment.profileId()))) {
        reject("Each product needs exactly one known profile or an explicit blocked reason.");
      }
    }
    var tariffs = new HashSet<String>();
    for (var tariff : bundle.laborTariffs()) {
      if (!tariffs.add(tariff.code())
          || !tariff.unit().equals(TARIFF_UNITS.get(tariff.code()))
          || !sources.contains(tariff.sourceId())) {
        reject("Tariffs contain duplicate/unknown codes, units or sources.");
      }
    }
    if (!tariffs.equals(TARIFF_UNITS.keySet())) {
      reject("The test bundle must declare all five labor tariffs.");
    }
    var combinations =
        Map.of(
            "floor",
            java.util.Set.of("covering", "adhesive"),
            "paint",
            java.util.Set.of("paint", "primer"),
            "wallpaper",
            java.util.Set.of("wallpaper", "primer"),
            "electrical",
            java.util.Set.of("socket", "cable"));
    var bases =
        Map.of(
            "covering",
            RuleSetBundle.Basis.FLOOR,
            "adhesive",
            RuleSetBundle.Basis.FLOOR,
            "paint",
            RuleSetBundle.Basis.WALL,
            "primer",
            RuleSetBundle.Basis.WALL,
            "wallpaper",
            RuleSetBundle.Basis.WALL,
            "socket",
            RuleSetBundle.Basis.SOCKET,
            "cable",
            RuleSetBundle.Basis.CABLE);
    var kits = new HashSet<String>();
    for (var kit : bundle.kits()) {
      if (!combinations.getOrDefault(kit.work(), java.util.Set.of()).contains(kit.kind())
          || bases.get(kit.kind()) != kit.basis()
          || !kits.add(kit.work() + ":" + kit.kind())
          || (kit.coveringFamily() != null
              && !(kit.work().equals("floor")
                  && kit.kind().equals("adhesive")
                  && kit.coveringFamily().equals("tile"))))
        reject("Invalid, duplicate or incompatible work kit.");
    }
    if (!kits.equals(
        java.util.Set.of(
            "floor:covering",
            "floor:adhesive",
            "paint:paint",
            "paint:primer",
            "wallpaper:wallpaper",
            "wallpaper:primer",
            "electrical:socket",
            "electrical:cable")))
      reject("Declare all eight material dependencies for the supported works.");
  }

  public void validateProducts(RuleSetBundle bundle, List<ProductFacts> products) {
    Map<UUID, ProductFacts> facts = new HashMap<>();
    products.forEach(product -> facts.put(product.id(), product));
    var assignments =
        bundle.assignments().stream()
            .map(RuleSetBundle.Assignment::productId)
            .collect(java.util.stream.Collectors.toSet());
    if (!assignments.equals(facts.keySet())) {
      reject(
          "Assignments must cover every catalog product exactly once and contain no unknown"
              + " references.");
    }
    Map<String, RuleSetBundle.Profile> profiles = new HashMap<>();
    bundle.profiles().forEach(profile -> profiles.put(profile.id(), profile));
    for (var assignment : bundle.assignments()) {
      if (assignment.profileId() == null) {
        continue;
      }
      var profile = profiles.get(assignment.profileId());
      var product = facts.get(assignment.productId());
      if (!profile.strategy().name().equalsIgnoreCase(product.kind())
          || !profile.unit().equals(product.unit())) {
        reject("Assigned profile does not match the product kind or physical unit.");
      }
      if (profile.consumption() != null
          && product.consumption() != null
          && profile.consumption().compareTo(product.consumption()) != 0) {
        reject("Rule consumption conflicts with the catalog value.");
      }
      if (profile.strategy() == Strategy.WALLPAPER) {
        if (product.packageSize() != null
            || !positive(product.rollWidth())
            || !positive(product.rollLength())
            || product.rapport() == null
            || product.rapport().signum() < 0) {
          reject(
              "Supported wallpaper requires roll geometry and a known rapport; packageSize must be"
                  + " null.");
        }
      } else if (!positive(product.packageSize())) {
        reject("Supported product requires a known positive package size.");
      }
      if (profile.strategy() == Strategy.SOCKET
          && product.packageSize().stripTrailingZeros().scale() > 0) {
        reject("Socket packaging must contain a whole number of pieces.");
      }
    }
  }

  private static boolean positive(BigDecimal value) {
    return value != null && value.signum() > 0;
  }

  private static void reject(String message) {
    throw new RuleSetImportException(message);
  }

  public record ProductFacts(
      UUID id,
      String kind,
      String unit,
      BigDecimal packageSize,
      BigDecimal consumption,
      BigDecimal rollWidth,
      BigDecimal rollLength,
      BigDecimal rapport) {}
}
