package ru.repaircalc.catalog;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.repaircalc.catalog.persistence.CatalogReleaseEntity;
import ru.repaircalc.catalog.persistence.CatalogReleaseRepository;
import ru.repaircalc.catalog.persistence.ProductRevisionEntity;
import ru.repaircalc.catalog.persistence.ProductRevisionRepository;
import ru.repaircalc.catalog.persistence.ProductWorkRepository;
import ru.repaircalc.common.web.ServiceUnavailableException;

@Service
@Transactional(readOnly = true)
public class CatalogReadService {
    public static final String UNAVAILABLE_MESSAGE = "Catalog is temporarily unavailable.";

    private final CatalogReleaseRepository releases;
    private final ProductRevisionRepository revisions;
    private final ProductWorkRepository productWorks;

    public CatalogReadService(CatalogReleaseRepository releases, ProductRevisionRepository revisions,
                              ProductWorkRepository productWorks) {
        this.releases = releases;
        this.revisions = revisions;
        this.productWorks = productWorks;
    }

    public List<ProductView> listProducts() {
        try {
            // Published content is immutable: retain this release ID across every subsequent query.
            UUID releaseId = releases.findByActiveTrue().map(CatalogReleaseEntity::getId)
                    .orElseThrow(CatalogReadService::unavailable);
            var products = revisions.findByReleaseIdOrderByProductIdAsc(releaseId);
            Map<UUID, List<String>> worksByProduct = new HashMap<>();
            for (var assignment : productWorks
                    .findPublishedAssignments(releaseId)) {
                var identity = assignment.getId();
                worksByProduct.computeIfAbsent(identity.getProductId(), ignored -> new ArrayList<>())
                        .add(identity.getWorkCode());
            }
            return products.stream()
                    .map(product -> view(product, worksByProduct.getOrDefault(product.getProductId(), List.of())))
                    .toList();
        } catch (DataAccessException exception) {
            throw unavailable();
        }
    }

    private static ProductView view(ProductRevisionEntity product, List<String> works) {
        return new ProductView(product.getProductId(), product.getName(), product.getCategory(), product.getKind(),
                product.getPrice().setScale(2).toPlainString(), product.getPackageSize(), product.getUnit(),
                product.getSaleUnit(), works, product.getWet(), product.getHighLoad(), product.getUnheated(),
                product.getDescription(), product.getSource(), product.getConsumption(), product.getRollWidth(),
                product.getRollLength(), product.getRapport());
    }

    private static ServiceUnavailableException unavailable() {
        return new ServiceUnavailableException("catalog_unavailable", UNAVAILABLE_MESSAGE);
    }
}
