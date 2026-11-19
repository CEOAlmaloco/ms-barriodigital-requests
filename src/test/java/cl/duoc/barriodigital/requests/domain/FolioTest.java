package cl.duoc.barriodigital.requests.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FolioTest {

    @ParameterizedTest
    @CsvSource({
            "2026, 123, BD-2026-000123",
            "2026, 1, BD-2026-000001",
            "2027, 999999, BD-2027-999999"
    })
    void formatoBdAnioYSeisDigitos(int year, long number, String expected) {
        assertEquals(expected, Folio.format(year, number));
        assertEquals(Folio.LENGTH, expected.length());
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, 1_000_000})
    void unCorrelativoFueraDeRangoEsUnError(long number) {
        assertThrows(IllegalArgumentException.class, () -> Folio.format(2026, number));
    }

    @ParameterizedTest
    @CsvSource({
            // 31-dic a las 23:30 en Chile (UTC-3 en verano): en UTC ya es 1-ene.
            "2027-01-01T02:30:00Z, 2026",
            // 1-ene a las 00:00 en Chile.
            "2027-01-01T03:00:00Z, 2027",
            "2026-11-07T15:00:00Z, 2026"
    })
    void elAnioEsElDeChileYNoElDeUtc(String instant, int year) {
        assertEquals(year, Folio.yearOf(Instant.parse(instant)));
    }

    @Test
    void elFolioSeAsignaUnaSolaVez() {
        MunicipalRequest request = new MunicipalRequest("t", "d", "bache", "dir", "vecino");
        request.assignFolio("BD-2026-000001");

        assertThrows(IllegalStateException.class, () -> request.assignFolio("BD-2026-000002"));
        assertEquals("BD-2026-000001", request.getFolio());
    }
}
