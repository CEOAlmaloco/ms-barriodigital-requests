package cl.duoc.barriodigital.requests.domain;

import java.time.ZoneId;

/** Zona de los días y años civiles del municipio: filtro de fechas, año del folio y cupos (EP1.5-03). */
public final class CivilTime {

    public static final ZoneId ZONE = ZoneId.of("America/Santiago");

    private CivilTime() {
    }
}
