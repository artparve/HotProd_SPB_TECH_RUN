package ru.repaircalc.calculation;

import java.sql.SQLException;

import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;
import ru.repaircalc.catalog.CatalogReadService;
import ru.repaircalc.catalog.CatalogSnapshot;
import ru.repaircalc.common.web.ServiceUnavailableException;
import ru.repaircalc.rules.RuleSetReadService;
import ru.repaircalc.rules.RuleSetSnapshot;

/** Only immutable, detached snapshots cross the catalog/rules module boundaries. */
@Service
public class CalculationSnapshotService {
    private final CatalogReadService catalogs;
    private final RuleSetReadService rules;

    public CalculationSnapshotService(CatalogReadService catalogs, RuleSetReadService rules) {
        this.catalogs = catalogs;
        this.rules = rules;
    }

    public Snapshot resolve() {
        try {
            var catalog = catalogs.activeSnapshot();
            var ruleSet = rules.activeForCatalog(catalog.releaseId()).orElseThrow(CalculationSnapshotService::unavailable);
            var reference = ruleSet.bundle().catalog();
            if (!catalog.releaseId().equals(ruleSet.catalogReleaseId())
                    || !catalog.version().equals(reference.version())
                    || !catalog.fingerprint().equals(reference.fingerprint())
                    || !catalog.taxonomyVersion().equals(reference.taxonomyVersion())) {
                throw unavailable();
            }
            return new Snapshot(catalog, ruleSet);
        } catch (ServiceUnavailableException | CannotCreateTransactionException | DataAccessException exception) {
            throw unavailable();
        } catch (TransactionSystemException exception) {
            if (exception.contains(SQLException.class) || exception.contains(DataAccessException.class)) {
                throw unavailable();
            }
            throw exception;
        }
    }

    private static ServiceUnavailableException unavailable() {
        return new ServiceUnavailableException("calculation_unavailable", "Calculation data is temporarily unavailable.");
    }

    public record Snapshot(CatalogSnapshot catalog, RuleSetSnapshot rules) { }
}
