package cl.duoc.barriodigital.requests;

import cl.duoc.barriodigital.requests.domain.Folio;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Folios únicos para tests que guardan trámites directo con el repositorio, sin pasar por el alta.
 * Usan el año 2099 para no chocar con los folios que generan los tests del alta.
 */
public final class TestFolios {

    private static final AtomicLong NEXT = new AtomicLong();

    private TestFolios() {
    }

    public static String unique() {
        return Folio.format(2099, NEXT.incrementAndGet());
    }
}
