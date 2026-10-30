package ru.repaircalc.calculation;

import java.util.UUID;
import org.springframework.stereotype.Service;
import ru.repaircalc.catalog.*;
import ru.repaircalc.common.web.ServiceUnavailableException;
import ru.repaircalc.rules.*;

@Service
public class CalculationSnapshotService {
  private final CatalogReadService catalogs;
  private final RuleSetReadService rules;

  public CalculationSnapshotService(CatalogReadService catalogs, RuleSetReadService rules) {
    this.catalogs = catalogs;
    this.rules = rules;
  }

  public Snapshot resolve() {
    return resolve(null);
  }

  public Snapshot resolve(UUID productId) {
    try {
      var pointer = catalogs.pointer();
      var catalog = catalogs.snapshot(pointer.getString("catalogReleaseId"), productId);
      var ruleSet = rules.byId(pointer.getString("ruleSetId"));
      var ref = ruleSet.bundle().catalog();
      if (!catalog.releaseId().equals(ruleSet.catalogReleaseId())
          || !catalog.version().equals(ref.version())
          || !catalog.fingerprint().equals(ref.fingerprint())
          || !catalog.taxonomyVersion().equals(ref.taxonomyVersion())) throw unavailable();
      return new Snapshot(catalog, ruleSet);
    } catch (org.springframework.dao.DataAccessException
        | com.mongodb.MongoException
        | RuleSetImportException e) {
      throw unavailable();
    }
  }

  private static ServiceUnavailableException unavailable() {
    return new ServiceUnavailableException(
        "calculation_unavailable", "Calculation data is temporarily unavailable.");
  }

  public record Snapshot(CatalogSnapshot catalog, RuleSetSnapshot rules) {}
}
