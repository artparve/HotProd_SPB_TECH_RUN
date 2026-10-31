package ru.repaircalc.rules;

import java.util.*;
import org.bson.Document;
import org.springframework.stereotype.Service;
import ru.repaircalc.common.web.ServiceUnavailableException;
import ru.repaircalc.storage.MongoStore;

@Service
public class RuleSetReadService {
  private final MongoStore store;
  private final RuleSetValidator validator;

  public RuleSetReadService(MongoStore store, RuleSetValidator validator) {
    this.store = store;
    this.validator = validator;
  }

  public Optional<RuleSetSnapshot> activeForCatalog(UUID id) {
    var p = store.id("publication_pointer", "active");
    return p == null || !id.toString().equals(p.getString("catalogReleaseId"))
        ? Optional.empty()
        : Optional.of(byId(p.getString("ruleSetId")));
  }

  public RuleSetSnapshot byId(String id) {
    var d = store.id("rule_set", id);
    if (d == null) throw new ServiceUnavailableException("rules_unavailable", "Rules unavailable.");
    var b = store.read(d.get("bundle", Document.class), RuleSetBundle.class);
    validator.validateShape(b);
    return new RuleSetSnapshot(
        UUID.fromString(id),
        UUID.fromString(d.getString("catalogReleaseId")),
        d.getString("version"),
        d.getString("fingerprint"),
        b);
  }
}
