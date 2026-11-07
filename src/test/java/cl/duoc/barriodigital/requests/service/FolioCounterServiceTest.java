package cl.duoc.barriodigital.requests.service;

import cl.duoc.barriodigital.requests.domain.Folio;
import cl.duoc.barriodigital.requests.domain.FolioCounter;
import cl.duoc.barriodigital.requests.persistence.FolioCounterRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FolioCounterServiceTest {

    private final FolioCounterRepository counters = mock(FolioCounterRepository.class);
    private final FolioCounterService service = new FolioCounterService(counters);

    @Test
    void ensureYearSoloInsertaSiFaltaLaFila() {
        when(counters.existsById(2026)).thenReturn(true);
        when(counters.existsById(2027)).thenReturn(false);

        service.ensureYear(2026);
        verify(counters, never()).save(any());

        service.ensureYear(2027);
        verify(counters).save(any(FolioCounter.class));
    }

    @Test
    void nextEntregaElNumeroSiguienteFormateado() {
        FolioCounter counter = new FolioCounter(2026);
        ReflectionTestUtils.setField(counter, "lastNumber", 122L);
        when(counters.findByIdForUpdate(2026)).thenReturn(Optional.of(counter));

        assertEquals("BD-2026-000123", service.next(2026));
        assertEquals(123, counter.getLastNumber());
    }

    @Test
    void sinLaFilaDelAnioEsUnErrorQueRevierteElAlta() {
        when(counters.findByIdForUpdate(2026)).thenReturn(Optional.empty());

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> service.next(2026));

        assertEquals("Falta el contador de folio del año 2026", error.getMessage());
    }

    @Test
    void pasadoElTopeNoEntregaNumeroNiMueveElContador() {
        FolioCounter counter = new FolioCounter(2026);
        ReflectionTestUtils.setField(counter, "lastNumber", Folio.MAX_NUMBER);
        when(counters.findByIdForUpdate(2026)).thenReturn(Optional.of(counter));

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> service.next(2026));

        assertEquals("Se agotaron los folios del año 2026", error.getMessage());
        assertEquals(Folio.MAX_NUMBER, counter.getLastNumber());
    }
}
