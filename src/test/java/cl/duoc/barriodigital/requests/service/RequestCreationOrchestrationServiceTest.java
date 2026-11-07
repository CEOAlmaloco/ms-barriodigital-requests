package cl.duoc.barriodigital.requests.service;

import cl.duoc.barriodigital.requests.web.CallerContext;
import cl.duoc.barriodigital.requests.web.dto.CreateRequestDto;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class RequestCreationOrchestrationServiceTest {

    // 31-dic a las 23:30 en Chile: en UTC ya es 2027.
    private static final Instant NEW_YEAR_EVE = Instant.parse("2027-01-01T02:30:00Z");

    private final FolioCounterService folios = mock(FolioCounterService.class);
    private final RequestService requests = mock(RequestService.class);
    private final RequestCreationOrchestrationService orchestrator = new RequestCreationOrchestrationService(
            Clock.fixed(NEW_YEAR_EVE, ZoneOffset.UTC), folios, requests);

    private final CreateRequestDto body = new CreateRequestDto("Hueco", "bache", "Calle 1");
    private final CallerContext vecino = new CallerContext("oid-1", "Vecino");

    @Test
    void preparaElAnioDeChileAntesDelAltaYLePasaElMismoInstante() {
        orchestrator.create(body, vecino, null);

        var order = inOrder(folios, requests);
        order.verify(folios).ensureYear(2026);
        order.verify(requests).create(eq(body), eq(vecino), isNull(), eq(NEW_YEAR_EVE));
    }

    @Test
    void siOtraAltaCreoLaFilaDelAnioAlMismoTiempoSigueConElAlta() {
        doThrow(new DataIntegrityViolationException("PK folio_counter")).when(folios).ensureYear(2026);

        orchestrator.create(body, vecino, null);

        verify(requests).create(eq(body), eq(vecino), isNull(), eq(NEW_YEAR_EVE));
    }

    @Test
    void otroErrorAlPrepararElAnioNoSeOcultaYNoHayAlta() {
        doThrow(new IllegalStateException("base caida")).when(folios).ensureYear(2026);

        assertThrows(IllegalStateException.class, () -> orchestrator.create(body, vecino, null));

        verify(requests, never()).create(any(), any(), any(), any());
    }
}
