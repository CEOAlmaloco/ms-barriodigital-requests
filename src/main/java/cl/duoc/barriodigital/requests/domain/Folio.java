package cl.duoc.barriodigital.requests.domain;

import java.time.Instant;

/**
 * Número de seguimiento del trámite, con formato BD-AAAA-NNNNNN (contrato EP3-05, sección 1.1).
 * El año es el de created_at en America/Santiago y el correlativo reinicia cada año.
 */
public final class Folio {

    /** Mayor correlativo que cabe en 6 dígitos. Pasarlo es un límite por diseño (EP3-05, sección 10). */
    public static final long MAX_NUMBER = 999_999L;

    /** Largo de BD-AAAA-NNNNNN. */
    public static final int LENGTH = 14;

    private Folio() {
    }

    /** Año del folio: el del instante en Chile. Un alta del 31-dic a las 23:30 en Chile es de ese año. */
    public static int yearOf(Instant instant) {
        return instant.atZone(CivilTime.ZONE).getYear();
    }

    /** Arma el folio con ceros a la izquierda. El número va de 1 a 999.999. */
    public static String format(int year, long number) {
        if (number < 1 || number > MAX_NUMBER) {
            throw new IllegalArgumentException("Correlativo de folio fuera de rango: " + number);
        }
        return String.format("BD-%04d-%06d", year, number);
    }
}
