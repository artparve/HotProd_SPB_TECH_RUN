package ru.repaircalc.catalog;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import ru.repaircalc.catalog.persistence.CatalogReleaseRepository;
import ru.repaircalc.catalog.persistence.ProductRevisionRepository;
import ru.repaircalc.catalog.persistence.ProductWorkRepository;
import ru.repaircalc.common.web.ServiceUnavailableException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CatalogReadServiceTest {
    @Mock
    private CatalogReleaseRepository releases;
    @Mock
    private ProductRevisionRepository revisions;
    @Mock
    private ProductWorkRepository productWorks;
    @InjectMocks
    private CatalogReadService catalog;

    @Test
    void missingPublicationIsUnavailable() {
        when(releases.findByActiveTrue()).thenReturn(Optional.empty());

        assertThatThrownBy(catalog::listProducts).isInstanceOf(ServiceUnavailableException.class)
                .hasMessage(CatalogReadService.UNAVAILABLE_MESSAGE);
        verifyNoInteractions(revisions, productWorks);
    }

    @Test
    void dataAccessFailureUsesSafeUnavailableMessage() {
        when(releases.findByActiveTrue()).thenThrow(new DataAccessResourceFailureException("private database detail"));

        assertThatThrownBy(catalog::listProducts).isInstanceOf(ServiceUnavailableException.class)
                .hasMessage(CatalogReadService.UNAVAILABLE_MESSAGE);
    }

    @Test
    void programmingErrorsRemainUnexpectedFailures() {
        var unexpected = new IllegalStateException("Programming error");
        when(releases.findByActiveTrue()).thenThrow(unexpected);

        assertThatThrownBy(catalog::listProducts).isSameAs(unexpected);
    }
}
