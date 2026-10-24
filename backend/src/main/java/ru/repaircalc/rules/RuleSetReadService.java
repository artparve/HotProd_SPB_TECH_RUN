package ru.repaircalc.rules;

import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.repaircalc.common.web.ServiceUnavailableException;
import ru.repaircalc.rules.persistence.RuleSetRepository;
import ru.repaircalc.rules.persistence.RuleSetStatus;
import tools.jackson.databind.json.JsonMapper;

@Service
@Transactional(readOnly = true)
public class RuleSetReadService {
    private final RuleSetRepository repository;
    private final JsonMapper json;
    private final RuleSetValidator validator;

    public RuleSetReadService(RuleSetRepository repository, JsonMapper json, RuleSetValidator validator) {
        this.repository = repository;
        this.json = RuleSetImportService.strictMapper(json);
        this.validator = validator;
    }

    public Optional<RuleSetSnapshot> activeForCatalog(UUID catalogId) {
        try {
            return repository.findByCatalogReleaseIdAndActiveTrue(catalogId).map(entity -> {
                var bundle = json.readValue(entity.getBundle(), RuleSetBundle.class);
                validator.validateShape(bundle);
                if (entity.getStatus() != RuleSetStatus.PUBLISHED || !entity.getVersion().equals(bundle.version())
                        || !entity.getTaxonomyVersion().equals(bundle.catalog().taxonomyVersion())) {
                    throw new RuleSetImportException("Stored rules metadata is inconsistent.");
                }
                return new RuleSetSnapshot(entity.getId(), catalogId, entity.getVersion(), entity.getFingerprint(), bundle);
            });
        } catch (DataAccessException | RuleSetImportException | tools.jackson.core.JacksonException exception) {
            throw new ServiceUnavailableException("rules_unavailable", "Calculation rules are temporarily unavailable.");
        }
    }
}
